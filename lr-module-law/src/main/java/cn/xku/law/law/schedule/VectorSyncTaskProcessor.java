package cn.xku.law.law.schedule;

import cn.xku.law.common.client.EmbeddingClient;
import cn.xku.law.common.client.SearchClient;
import cn.xku.law.law.domain.LawArticleSegmentDO;
import cn.xku.law.law.domain.LawVersionDO;
import cn.xku.law.law.domain.VectorSyncTaskDO;
import cn.xku.law.law.mapper.LawArticleSegmentMapper;
import cn.xku.law.law.mapper.LawVersionMapper;
import cn.xku.law.law.mapper.VectorSyncTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 向量同步任务消费者：读取 lr_vector_sync_task pending 任务，
 * 对法规版本下的条款分片做 embedding 并写入 ES dense_vector 索引。
 * 结构对照 {@link SearchIndexTaskProcessor}（同样的 CAS 领取 / 重试 / 超时恢复模型）。
 * 仅在 app.vector.enabled=true 时启用；嵌入模型未接入时由 NoOpEmbeddingClient 抛错触发重试。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.vector", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VectorSyncTaskProcessor {

    @Value("${app.vector.index-name:law_segment}")
    private String vectorIndex;

    /** 每批领取的向量任务数。 */
    @Value("${app.vector.batch-size:20}")
    private int batchSize;

    /** 受控并发数；嵌入为远程 API 调用，注意别超过模型 QPS 限流。 */
    @Value("${app.vector.concurrency:4}")
    private int concurrency;

    /** 单次 embedBatch 提交的文本条数（受嵌入服务单请求条数上限约束）。 */
    @Value("${app.vector.embedding.batch-size:10}")
    private int embedBatchSize;

    @Value("${app.vector.max-retry:3}")
    private int maxRetry;

    @Value("${app.vector.processing-timeout-minutes:5}")
    private int processingTimeoutMinutes;

    private final VectorSyncTaskMapper taskMapper;
    private final LawArticleSegmentMapper segmentMapper;
    private final LawVersionMapper lawVersionMapper;
    private final SearchClient searchClient;
    private final EmbeddingClient embeddingClient;

    /** 受控并发执行器：每批 pending 任务并行处理，CAS 领取保证不重复。 */
    private ExecutorService executor;

    @PostConstruct
    public void init() {
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrency));
        log.info("[VectorTask] processor started (batchSize={}, concurrency={}, embedBatchSize={})",
                batchSize, Math.max(1, concurrency), Math.max(1, embedBatchSize));
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${app.vector.sync-task-interval-ms:10000}")
    public void processPendingTasks() {
        List<VectorSyncTaskDO> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<VectorSyncTaskDO>()
                        .eq(VectorSyncTaskDO::getSyncStatus, "pending")
                        .lt(VectorSyncTaskDO::getRetryCount, maxRetry)
                        .last("LIMIT " + batchSize));

        if (tasks.isEmpty()) return;

        log.debug("[VectorTask] processing {} pending tasks", tasks.size());
        // 受控并发：提交到固定线程池并等待本批完成；CAS 领取保证同一任务不被重复处理。
        List<Future<?>> futures = new ArrayList<>(tasks.size());
        for (VectorSyncTaskDO task : tasks) {
            futures.add(executor.submit(() -> processOneTask(task)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("[VectorTask] batch task execution error: {}", e.getMessage(), e);
            }
        }
    }

    private void processOneTask(VectorSyncTaskDO task) {
        if (taskMapper.claimTask(task.getId()) == 0) {
            log.debug("[VectorTask] task {} already claimed by another instance, skipping", task.getId());
            return;
        }
        try {
            if ("upsert".equals(task.getActionType())) {
                doUpsert(task);
            } else if ("delete".equals(task.getActionType())) {
                doDelete(task);
            } else {
                log.warn("[VectorTask] unknown actionType '{}', taskId={}", task.getActionType(), task.getId());
            }
            markDone(task);
        } catch (Exception e) {
            log.error("[VectorTask] task {} failed (attempt {}): {}", task.getId(), task.getRetryCount() + 1, e.getMessage());
            markFailed(task, e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.vector.sync-task-interval-ms:10000}")
    public void recoverStuckTasks() {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(processingTimeoutMinutes);
        List<VectorSyncTaskDO> stuckTasks = taskMapper.selectList(
                new LambdaQueryWrapper<VectorSyncTaskDO>()
                        .eq(VectorSyncTaskDO::getSyncStatus, "processing")
                        .lt(VectorSyncTaskDO::getUpdateTime, timeoutThreshold));

        if (stuckTasks.isEmpty()) return;

        log.warn("[VectorTask] found {} stuck processing tasks (timeout={}min)", stuckTasks.size(), processingTimeoutMinutes);
        for (VectorSyncTaskDO task : stuckTasks) {
            int retries = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retries + 1 >= maxRetry) {
                taskMapper.update(null, new LambdaUpdateWrapper<VectorSyncTaskDO>()
                        .eq(VectorSyncTaskDO::getId, task.getId())
                        .eq(VectorSyncTaskDO::getSyncStatus, "processing")
                        .set(VectorSyncTaskDO::getSyncStatus, "failed")
                        .set(VectorSyncTaskDO::getRetryCount, retries + 1)
                        .set(VectorSyncTaskDO::getErrorMessage, "processing timeout, retries exhausted"));
                log.error("[VectorTask] task {} timed out and exhausted retries, marked failed", task.getId());
            } else {
                taskMapper.update(null, new LambdaUpdateWrapper<VectorSyncTaskDO>()
                        .eq(VectorSyncTaskDO::getId, task.getId())
                        .eq(VectorSyncTaskDO::getSyncStatus, "processing")
                        .set(VectorSyncTaskDO::getSyncStatus, "pending")
                        .set(VectorSyncTaskDO::getRetryCount, retries + 1));
                log.warn("[VectorTask] task {} timed out, reset to pending (attempt {})", task.getId(), retries + 1);
            }
        }
    }

    private void doUpsert(VectorSyncTaskDO task) {
        if (!"law_version".equals(task.getRefType())) {
            log.warn("[VectorTask] unsupported refType '{}', taskId={}", task.getRefType(), task.getId());
            return;
        }
        Long versionId = task.getRefId();
        String index = resolveIndex(task);
        LawVersionDO version = lawVersionMapper.selectById(versionId);
        Long documentId = version != null ? version.getDocumentId() : null;

        List<LawArticleSegmentDO> segments = segmentMapper.selectList(
                new LambdaQueryWrapper<LawArticleSegmentDO>()
                        .eq(LawArticleSegmentDO::getVersionId, versionId));
        if (segments.isEmpty()) {
            log.debug("[VectorTask] no segments for versionId={}, nothing to embed", versionId);
            return;
        }

        // 大幅降低 HTTP 往返（原来每分片一次调用）。一块成功即整块写入索引。
        int chunkSize = Math.max(1, embedBatchSize);
        for (int start = 0; start < segments.size(); start += chunkSize) {
            List<LawArticleSegmentDO> chunk = segments.subList(start, Math.min(segments.size(), start + chunkSize));
            List<String> texts = chunk.stream()
                    .map(s -> s.getSegmentText() != null ? s.getSegmentText() : "")
                    .toList();
            List<float[]> embeddings = embeddingClient.embedBatch(texts);
            if (embeddings.size() != chunk.size()) {
                throw new IllegalStateException("嵌入返回条数与分片数不一致: expected "
                        + chunk.size() + ", got " + embeddings.size());
            }
            for (int i = 0; i < chunk.size(); i++) {
                LawArticleSegmentDO segment = chunk.get(i);
                String docId = String.valueOf(segment.getId());

                Map<String, Object> source = new HashMap<>();
                source.put("segmentId",   segment.getId());
                source.put("articleId",   segment.getArticleId());
                source.put("versionId",   versionId);
                source.put("documentId",  documentId);
                source.put("segmentText", segment.getSegmentText() != null ? segment.getSegmentText() : "");
                source.put("tenantId",    0L);
                source.put("isPublic",    true);
                source.put("embedding",   embeddings.get(i));

                searchClient.indexDocument(index, docId, source);

                segment.setVectorId(docId);
                segment.setEmbeddingStatus("done");
                segmentMapper.updateById(segment);
            }
        }
    }

    private void doDelete(VectorSyncTaskDO task) {
        String index = resolveIndex(task);
        if (StringUtils.hasText(task.getVectorId())) {
            searchClient.deleteDocument(index, task.getVectorId());
            return;
        }
        List<LawArticleSegmentDO> segments = segmentMapper.selectList(
                new LambdaQueryWrapper<LawArticleSegmentDO>()
                        .eq(LawArticleSegmentDO::getVersionId, task.getRefId()));
        for (LawArticleSegmentDO segment : segments) {
            searchClient.deleteDocument(index, String.valueOf(segment.getId()));
        }
    }

    /** 优先用任务入队时记录的目标索引，缺失时回退到当前配置，避免配置漂移写错索引。 */
    private String resolveIndex(VectorSyncTaskDO task) {
        return StringUtils.hasText(task.getVectorIndex()) ? task.getVectorIndex() : vectorIndex;
    }

    private void markDone(VectorSyncTaskDO task) {
        task.setSyncStatus("done");
        task.setVectorIndex(resolveIndex(task));
        task.setLastSyncTime(LocalDateTime.now());
        task.setErrorMessage(null);
        taskMapper.updateById(task);
    }

    private void markFailed(VectorSyncTaskDO task, String errorMsg) {
        int retries = task.getRetryCount() == null ? 0 : task.getRetryCount();
        task.setRetryCount(retries + 1);
        task.setErrorMessage(errorMsg != null && errorMsg.length() > 500
                ? errorMsg.substring(0, 500) : errorMsg);
        task.setLastSyncTime(LocalDateTime.now());
        if (retries + 1 >= maxRetry) {
            task.setSyncStatus("failed");
            log.error("[VectorTask] task {} exhausted retries, marked failed", task.getId());
        } else {
            task.setSyncStatus("pending");
        }
        taskMapper.updateById(task);
    }
}
