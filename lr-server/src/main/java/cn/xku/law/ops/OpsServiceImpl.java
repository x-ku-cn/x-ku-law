package cn.xku.law.ops;

import cn.xku.law.collect.domain.CollectRecordDO;
import cn.xku.law.collect.domain.CollectTaskDO;
import cn.xku.law.collect.mapper.CollectRecordMapper;
import cn.xku.law.collect.mapper.CollectTaskMapper;
import cn.xku.law.collect.parser.ParseInput;
import cn.xku.law.collect.parser.ParseResult;
import cn.xku.law.collect.parser.ParsedArticle;
import cn.xku.law.collect.parser.ParserRegistry;
import cn.xku.law.common.result.PageResult;
import cn.xku.law.law.domain.LawAiTaskDO;
import cn.xku.law.law.domain.LawArticleDO;
import cn.xku.law.law.domain.LawDocumentDO;
import cn.xku.law.law.domain.LawProcessTaskDO;
import cn.xku.law.law.domain.LawVersionDO;
import cn.xku.law.law.domain.SearchIndexTaskDO;
import cn.xku.law.law.domain.VectorSyncTaskDO;
import cn.xku.law.law.mapper.LawAiTaskMapper;
import cn.xku.law.law.mapper.LawArticleMapper;
import cn.xku.law.law.mapper.LawDocumentMapper;
import cn.xku.law.law.mapper.LawProcessTaskMapper;
import cn.xku.law.law.mapper.LawVersionMapper;
import cn.xku.law.law.service.LawDocumentService;
import cn.xku.law.law.service.TimelinessReconcileResult;
import cn.xku.law.law.domain.DataAuditRecordDO;
import cn.xku.law.law.domain.DataQualityIssueDO;
import cn.xku.law.law.mapper.DataAuditRecordMapper;
import cn.xku.law.law.mapper.DataQualityIssueMapper;
import cn.xku.law.law.mapper.SearchIndexTaskMapper;
import cn.xku.law.law.mapper.VectorSyncTaskMapper;
import cn.xku.law.common.exception.AppException;
import cn.xku.law.common.exception.ErrorCode;
import cn.xku.law.common.security.SecurityUtils;
import cn.xku.law.process.DataGovernanceRecorder;
import cn.xku.law.subscription.domain.AlertDeliveryDO;
import cn.xku.law.subscription.service.AlertDeliveryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsServiceImpl implements OpsService {

    private final LawProcessTaskMapper processTaskMapper;
    private final LawAiTaskMapper aiTaskMapper;
    private final CollectRecordMapper collectRecordMapper;
    private final CollectTaskMapper collectTaskMapper;
    private final SearchIndexTaskMapper indexTaskMapper;
    private final VectorSyncTaskMapper vectorTaskMapper;
    private final AlertDeliveryService alertDeliveryService;
    private final DataQualityIssueMapper qualityIssueMapper;
    private final DataAuditRecordMapper auditRecordMapper;
    private final LawVersionMapper lawVersionMapper;
    private final LawArticleMapper lawArticleMapper;
    private final LawDocumentMapper lawDocumentMapper;
    private final LawDocumentService lawDocumentService;
    private final ParserRegistry parserRegistry;
    private final DataGovernanceRecorder governanceRecorder;
    private final SqlSessionFactory sqlSessionFactory;
    private final Environment env;

    @Override
    public PageResult<LawProcessTaskDO> pageProcessTasks(String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<LawProcessTaskDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            w.eq(LawProcessTaskDO::getProcessStatus, status);
        }
        w.orderByDesc(LawProcessTaskDO::getId);
        return PageResult.of(processTaskMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public boolean retryProcessTask(Long id) {
        LambdaUpdateWrapper<LawProcessTaskDO> u = new LambdaUpdateWrapper<>();
        u.eq(LawProcessTaskDO::getId, id)
                .eq(LawProcessTaskDO::getProcessStatus, "failed")
                .set(LawProcessTaskDO::getProcessStatus, "pending")
                .set(LawProcessTaskDO::getRetryCount, 0)
                .set(LawProcessTaskDO::getErrorMessage, null)
                .set(LawProcessTaskDO::getStartedAt, null)
                .set(LawProcessTaskDO::getFinishedAt, null);
        return processTaskMapper.update(null, u) > 0;
    }

    @Override
    public int retryAllProcessTasks() {
        LambdaUpdateWrapper<LawProcessTaskDO> u = new LambdaUpdateWrapper<>();
        u.eq(LawProcessTaskDO::getProcessStatus, "failed")
                .set(LawProcessTaskDO::getProcessStatus, "pending")
                .set(LawProcessTaskDO::getRetryCount, 0)
                .set(LawProcessTaskDO::getErrorMessage, null)
                .set(LawProcessTaskDO::getStartedAt, null)
                .set(LawProcessTaskDO::getFinishedAt, null);
        return processTaskMapper.update(null, u);
    }

    @Override
    public PageResult<CollectRecordDO> pageCollectRecords(String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<CollectRecordDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            w.eq(CollectRecordDO::getCollectStatus, status);
        }
        w.orderByDesc(CollectRecordDO::getId);
        return PageResult.of(collectRecordMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public PageResult<CollectTaskDO> pageCollectTasks(long pageNo, long pageSize) {
        LambdaQueryWrapper<CollectTaskDO> w = new LambdaQueryWrapper<>();
        w.orderByDesc(CollectTaskDO::getId);
        return PageResult.of(collectTaskMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public PageResult<SearchIndexTaskDO> pageIndexTasks(String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<SearchIndexTaskDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            w.eq(SearchIndexTaskDO::getSyncStatus, status);
        }
        w.orderByDesc(SearchIndexTaskDO::getId);
        return PageResult.of(indexTaskMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public boolean retryIndexTask(Long id) {
        LambdaUpdateWrapper<SearchIndexTaskDO> u = new LambdaUpdateWrapper<>();
        u.eq(SearchIndexTaskDO::getId, id)
                .eq(SearchIndexTaskDO::getSyncStatus, "failed")
                .set(SearchIndexTaskDO::getSyncStatus, "pending")
                .set(SearchIndexTaskDO::getRetryCount, 0)
                .set(SearchIndexTaskDO::getErrorMessage, null);
        return indexTaskMapper.update(null, u) > 0;
    }

    @Override
    public int retryAllIndexTasks() {
        LambdaUpdateWrapper<SearchIndexTaskDO> u = new LambdaUpdateWrapper<>();
        u.eq(SearchIndexTaskDO::getSyncStatus, "failed")
                .set(SearchIndexTaskDO::getSyncStatus, "pending")
                .set(SearchIndexTaskDO::getRetryCount, 0)
                .set(SearchIndexTaskDO::getErrorMessage, null);
        return indexTaskMapper.update(null, u);
    }

    @Override
    public int backfillIndexTasks() {
        return indexTaskMapper.backfillPublishedCurrent();
    }

    @Override
    public PageResult<VectorSyncTaskDO> pageVectorTasks(String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<VectorSyncTaskDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            w.eq(VectorSyncTaskDO::getSyncStatus, status);
        }
        w.orderByDesc(VectorSyncTaskDO::getId);
        return PageResult.of(vectorTaskMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public boolean retryVectorTask(Long id) {
        LambdaUpdateWrapper<VectorSyncTaskDO> u = new LambdaUpdateWrapper<>();
        u.eq(VectorSyncTaskDO::getId, id)
                .eq(VectorSyncTaskDO::getSyncStatus, "failed")
                .set(VectorSyncTaskDO::getSyncStatus, "pending")
                .set(VectorSyncTaskDO::getRetryCount, 0)
                .set(VectorSyncTaskDO::getErrorMessage, null);
        return vectorTaskMapper.update(null, u) > 0;
    }

    @Override
    public int retryAllVectorTasks() {
        LambdaUpdateWrapper<VectorSyncTaskDO> u = new LambdaUpdateWrapper<>();
        u.eq(VectorSyncTaskDO::getSyncStatus, "failed")
                .set(VectorSyncTaskDO::getSyncStatus, "pending")
                .set(VectorSyncTaskDO::getRetryCount, 0)
                .set(VectorSyncTaskDO::getErrorMessage, null);
        return vectorTaskMapper.update(null, u);
    }

    @Override
    public PageResult<LawAiTaskDO> pageAiTasks(String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<LawAiTaskDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            w.eq(LawAiTaskDO::getProcessStatus, status);
        }
        w.orderByDesc(LawAiTaskDO::getId);
        return PageResult.of(aiTaskMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public boolean retryAiTask(Long id) {
        LambdaUpdateWrapper<LawAiTaskDO> u = new LambdaUpdateWrapper<>();
        u.eq(LawAiTaskDO::getId, id)
                .eq(LawAiTaskDO::getProcessStatus, "failed")
                .set(LawAiTaskDO::getProcessStatus, "pending")
                .set(LawAiTaskDO::getRetryCount, 0)
                .set(LawAiTaskDO::getErrorMessage, null)
                .set(LawAiTaskDO::getStartedAt, null)
                .set(LawAiTaskDO::getFinishedAt, null);
        return aiTaskMapper.update(null, u) > 0;
    }

    @Override
    public int backfillAiTasks() {
        return aiTaskMapper.backfillPublishedMissing();
    }

    @Override
    public ChapterBackfillResultVO backfillArticleChapters(boolean dryRun, Long fromVersionId) {
        ChapterBackfillResultVO result = new ChapterBackfillResultVO();
        result.setDryRun(dryRun);
        long lastId = fromVersionId != null && fromVersionId > 0 ? fromVersionId : 0L;
        result.setFromVersionId(lastId);
        final int batch = 200;
        while (true) {
            // 按 id 升序游标翻页，只取 content_text 非空的版本（有正文才谈得上重解析出章节）。
            List<LawVersionDO> versions = lawVersionMapper.selectList(new LambdaQueryWrapper<LawVersionDO>()
                    .select(LawVersionDO::getId, LawVersionDO::getContentText)
                    .gt(LawVersionDO::getId, lastId)
                    .isNotNull(LawVersionDO::getContentText)
                    .ne(LawVersionDO::getContentText, "")
                    .orderByAsc(LawVersionDO::getId)
                    .last("limit " + batch));
            if (versions.isEmpty()) {
                break;
            }
            long pageMaxId = versions.get(versions.size() - 1).getId();
            try {
                backfillOnePage(versions, dryRun, result);
            } catch (Exception e) {
                // 单页失败（如远程连接闪断/超时/锁等）只跳过本页并留痕，绝不拖垮整个回填。
                result.setVersionsFailed(result.getVersionsFailed() + versions.size());
                log.warn("[ChapterBackfill] page [{}..{}] failed, skipped: {}",
                        versions.get(0).getId(), pageMaxId, e.toString());
            } finally {
                lastId = pageMaxId; // 无论成败都推进游标，避免卡死或死循环
            }
        }
        result.setLastVersionId(lastId);
        log.info("[ChapterBackfill] dryRun={} from={} scanned={} updated={} skipped={} versionsWithMismatch={} versionsFailed={} lastVersionId={}",
                dryRun, result.getFromVersionId(), result.getVersionsScanned(), result.getArticlesUpdated(),
                result.getArticlesSkipped(), result.getVersionsWithMismatch(), result.getVersionsFailed(), lastId);
        return result;
    }

    /** 处理一页版本：加载条款、分组、按 dryRun 决定是否用 BATCH 会话写入。异常向上抛由调用方按页跳过。 */
    private void backfillOnePage(List<LawVersionDO> versions, boolean dryRun, ChapterBackfillResultVO result) {
        // 一次性取回本页所有版本的条款并按 versionId 分组，避免「每版本一次查询」的 N+1 远程往返。
        List<Long> versionIds = new ArrayList<>(versions.size());
        for (LawVersionDO v : versions) {
            versionIds.add(v.getId());
        }
        Map<Long, Map<String, LawArticleDO>> byVersion = new HashMap<>();
        List<LawArticleDO> pageArticles = lawArticleMapper.selectList(new LambdaQueryWrapper<LawArticleDO>()
                .select(LawArticleDO::getId, LawArticleDO::getVersionId,
                        LawArticleDO::getArticleNo, LawArticleDO::getContentText)
                .in(LawArticleDO::getVersionId, versionIds));
        for (LawArticleDO a : pageArticles) {
            if (StringUtils.hasText(a.getArticleNo())) {
                byVersion.computeIfAbsent(a.getVersionId(), k -> new HashMap<>())
                        .put(a.getArticleNo(), a);
            }
        }
        if (dryRun) {
            for (LawVersionDO v : versions) {
                result.setVersionsScanned(result.getVersionsScanned() + 1);
                Map<String, LawArticleDO> byNo = byVersion.getOrDefault(v.getId(), Collections.emptyMap());
                backfillOneVersion(v.getId(), v.getContentText(), byNo, null, result);
            }
        } else {
            // BATCH 会话累积 UPDATE，但每积满 FLUSH_EVERY 条就 flush+commit 一次，而非整页一把梭。
            // 关键：远程库链路不稳时，一次 flush 的「几千条」大 payload 会阻塞在 socket 写上（socketTimeout
            // 只管读、不管写，于是无限挂死）。拆成小批 flush 后每次网络写都很小、能即时送达，随后的读再由
            // socketTimeout 兜底，从根上消除大批写阻塞。
            final int flushEvery = 500;
            try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
                LawArticleMapper batchMapper = session.getMapper(LawArticleMapper.class);
                int sinceFlush = 0;
                for (LawVersionDO v : versions) {
                    result.setVersionsScanned(result.getVersionsScanned() + 1);
                    Map<String, LawArticleDO> byNo = byVersion.getOrDefault(v.getId(), Collections.emptyMap());
                    int before = result.getArticlesUpdated();
                    backfillOneVersion(v.getId(), v.getContentText(), byNo, batchMapper, result);
                    sinceFlush += result.getArticlesUpdated() - before;
                    if (sinceFlush >= flushEvery) {
                        session.flushStatements();
                        session.commit();
                        sinceFlush = 0;
                    }
                }
                session.flushStatements();
                session.commit();
            }
        }
    }

    /**
     * 回填单个版本：重解析 content_text，按条号匹配现有条款（{@code byNo} 为本版本已加载的条款），
     * 逐字校验通过才更新章/节列。{@code writeMapper} 为 null 表示 dry-run（只统计不写、不留痕，
     * 真正无副作用）；非 null 时用它执行批量 UPDATE，并对存在 mismatch 的版本记一条质量问题。
     */
    private void backfillOneVersion(Long versionId, String contentText, Map<String, LawArticleDO> byNo,
                                    LawArticleMapper writeMapper, ChapterBackfillResultVO result) {
        boolean write = writeMapper != null;
        ParseResult parse = parserRegistry.parse(ParseInput.ofText(contentText, null));
        List<ParsedArticle> parsed = parse.getArticles();
        if (parsed == null || parsed.isEmpty()) {
            return; // 无「第X条」结构（退化全文条款），无章节可归属
        }

        int mismatch = 0;
        for (ParsedArticle pa : parsed) {
            if (!StringUtils.hasText(pa.getArticleNo())) {
                continue;
            }
            if (pa.getChapterNo() == null && pa.getSectionNo() == null) {
                continue; // 无章节可写，跳过（不算 mismatch）
            }
            LawArticleDO dbo = byNo.get(pa.getArticleNo());
            // 逐字校验闸门：条号必须匹配，且重解析条文与库中 content_text 完全相等，才允许写章节列。
            if (dbo == null || !Objects.equals(dbo.getContentText(), pa.getContentText())) {
                mismatch++;
                result.setArticlesSkipped(result.getArticlesSkipped() + 1);
                continue;
            }
            if (write) {
                writeMapper.updateChapterById(dbo.getId(), pa.getChapterNo(),
                        pa.getChapterTitle(), pa.getSectionNo(), pa.getSectionTitle());
            }
            result.setArticlesUpdated(result.getArticlesUpdated() + 1);
        }
        if (mismatch > 0) {
            result.setVersionsWithMismatch(result.getVersionsWithMismatch() + 1);
            // 仅真跑留痕：dry-run 不写质量问题，避免预演在治理表里累积噪声。
            if (write) {
                governanceRecorder.recordQualityIssue("law_version", versionId, "chapter_backfill_skip",
                        "normal", "章节回填跳过 " + mismatch + " 条（条号不匹配或重解析条文与库中不一致）；"
                                + "本版本重解析共 " + parsed.size() + " 条，请人工复核该版本条款拆分。");
            }
        }
    }

    @Override
    public PageResult<AlertDeliveryDO> pageAlertDeliveries(String status, long pageNo, long pageSize) {
        LambdaQueryWrapper<AlertDeliveryDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            w.eq(AlertDeliveryDO::getSendStatus, status);
        }
        w.orderByDesc(AlertDeliveryDO::getId);
        return PageResult.of(alertDeliveryService.getBaseMapper().selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public void retryAlertDelivery(Long id) {
        alertDeliveryService.retry(id);
    }

    @Override
    public int retryAllAlertDeliveries() {
        LambdaQueryWrapper<AlertDeliveryDO> w = new LambdaQueryWrapper<>();
        w.eq(AlertDeliveryDO::getSendStatus, "failed").select(AlertDeliveryDO::getId);
        List<AlertDeliveryDO> failed = alertDeliveryService.getBaseMapper().selectList(w);
        int n = 0;
        for (AlertDeliveryDO d : failed) {
            try {
                alertDeliveryService.retry(d.getId());
                n++;
            } catch (Exception ignored) {
                // 单条重投异常不影响其余记录，继续重投
            }
        }
        return n;
    }

    @Override
    public PageResult<DataQualityIssueDO> pageQualityIssues(String status, String issueType,
                                                            long pageNo, long pageSize) {
        LambdaQueryWrapper<DataQualityIssueDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            w.eq(DataQualityIssueDO::getStatus, status);
        }
        if (StringUtils.hasText(issueType)) {
            w.eq(DataQualityIssueDO::getIssueType, issueType);
        }
        w.orderByDesc(DataQualityIssueDO::getId);
        return PageResult.of(qualityIssueMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public void resolveQualityIssue(Long id) {
        DataQualityIssueDO issue = qualityIssueMapper.selectById(id);
        if (issue == null) throw new AppException(ErrorCode.NOT_FOUND);
        issue.setStatus("resolved");
        issue.setOwnerUserId(SecurityUtils.getCurrentUserId());
        issue.setResolvedTime(java.time.LocalDateTime.now());
        qualityIssueMapper.updateById(issue);
    }

    @Override
    public PageResult<DataAuditRecordDO> pageAuditRecords(String auditType, long pageNo, long pageSize) {
        LambdaQueryWrapper<DataAuditRecordDO> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(auditType)) {
            w.eq(DataAuditRecordDO::getAuditType, auditType);
        }
        w.orderByDesc(DataAuditRecordDO::getId);
        return PageResult.of(auditRecordMapper.selectPage(Page.of(pageNo, pageSize), w));
    }

    @Override
    public LawStatusReconcileResultVO reconcileLawStatus(boolean dryRun) {
        LawStatusReconcileResultVO result = new LawStatusReconcileResultVO();
        result.setDryRun(dryRun);
        java.time.LocalDate today = java.time.LocalDate.now();
        long lastId = 0L;
        final int batch = 500;
        while (true) {
            // 按 id 升序游标翻页扫全库文档，只取 id（重算逻辑内部自查版本）。
            List<LawDocumentDO> docs = lawDocumentMapper.selectList(new LambdaQueryWrapper<LawDocumentDO>()
                    .select(LawDocumentDO::getId)
                    .gt(LawDocumentDO::getId, lastId)
                    .orderByAsc(LawDocumentDO::getId)
                    .last("limit " + batch));
            if (docs.isEmpty()) {
                break;
            }
            for (LawDocumentDO d : docs) {
                result.setDocumentsScanned(result.getDocumentsScanned() + 1);
                try {
                    TimelinessReconcileResult r = lawDocumentService.reconcileTimeliness(d.getId(), today, dryRun);
                    accumulate(result, r);
                } catch (Exception e) {
                    // 单文档失败（锁/连接闪断等）只跳过并计数，绝不拖垮整个重算。
                    result.setDocumentsFailed(result.getDocumentsFailed() + 1);
                    log.warn("[LawStatusReconcile] documentId={} failed, skipped: {}", d.getId(), e.toString());
                }
            }
            lastId = docs.get(docs.size() - 1).getId();
        }
        log.info("[LawStatusReconcile] dryRun={} scanned={} becameEffective={} becameExpired={} currentRecomputed={} unchanged={} failed={}",
                dryRun, result.getDocumentsScanned(), result.getBecameEffective(), result.getBecameExpired(),
                result.getCurrentVersionRecomputed(), result.getUnchanged(), result.getDocumentsFailed());
        return result;
    }

    /** 把单文档重算结果并入统计。计数项非互斥：状态变更与现行版重算可同时命中。 */
    private static void accumulate(LawStatusReconcileResultVO r, TimelinessReconcileResult t) {
        if (!t.changed()) {
            r.setUnchanged(r.getUnchanged() + 1);
            return;
        }
        if ("effective".equals(t.newStatus()) && !"effective".equals(t.oldStatus())) {
            r.setBecameEffective(r.getBecameEffective() + 1);
        }
        if ("expired".equals(t.newStatus()) && !"expired".equals(t.oldStatus())) {
            r.setBecameExpired(r.getBecameExpired() + 1);
        }
        if (t.currentVersionChanged()) {
            r.setCurrentVersionRecomputed(r.getCurrentVersionRecomputed() + 1);
        }
    }

    @Override
    public List<OpsConfigVO> schedulerConfig() {
        List<OpsConfigVO> list = new ArrayList<>();

        list.add(new OpsConfigVO(
                "collect", "采集接入",
                env.getProperty("app.collect.enabled", Boolean.class, false),
                "cron",
                env.getProperty("app.collect.cron", "0 0 3 1 * ?"),
                null,
                "app.collect.* (COLLECT_ENABLED / COLLECT_CRON / COLLECT_BATCH_SIZE)",
                "定时扫描 MinIO 采集产物并接入；单批 "
                        + env.getProperty("app.collect.batch-size", "5") + " 个运行文件夹；"
                        + "卡住记录重置轮询 " + env.getProperty("app.collect.scan-interval-ms", "300000") + "ms"));

        list.add(new OpsConfigVO(
                "process", "处理管线",
                env.getProperty("app.process.enabled", Boolean.class, false),
                "interval",
                interval(env.getProperty("app.process.task-interval-ms", "10000")),
                env.getProperty("app.process.max-retry", Integer.class, 3),
                "app.process.* (PROCESS_ENABLED / PROCESS_INTERVAL / PROCESS_MAX_RETRY)",
                "结构化主管线：文本提取→分段→发布→变更分析（不触发 LLM）；单批 "
                        + env.getProperty("app.process.batch-size", "20") + "，并发 "
                        + env.getProperty("app.process.concurrency", "4")));

        list.add(new OpsConfigVO(
                "process-ai", "AI 旁路（摘要/解读）",
                env.getProperty("app.process.ai.enabled", Boolean.class, false),
                "interval",
                interval(env.getProperty("app.process.ai.task-interval-ms", "10000")),
                env.getProperty("app.process.ai.max-retry", Integer.class, 3),
                "app.process.ai.* (PROCESS_AI_ENABLED) + app.ai.*（需 AI 模型 apikey）",
                "元数据富集（摘要/标签）→ 整篇解读；单批 "
                        + env.getProperty("app.process.ai.batch-size", "5") + "，并发 "
                        + env.getProperty("app.process.ai.concurrency", "2")
                        + "；存量回填 POST /ops/ai-tasks/backfill"));

        list.add(new OpsConfigVO(
                "search", "检索索引同步",
                env.getProperty("app.search.enabled", Boolean.class, true),
                "interval",
                interval(env.getProperty("app.search.index-task-interval-ms", "10000")),
                env.getProperty("app.search.max-retry", Integer.class, 3),
                "app.search.* (SEARCH_ENABLED)",
                "同步法规文档到检索引擎；存量回填 POST /ops/index-tasks/backfill"));

        list.add(new OpsConfigVO(
                "vector", "向量同步",
                env.getProperty("app.vector.enabled", Boolean.class, false),
                "interval",
                interval(env.getProperty("app.vector.sync-task-interval-ms", "10000")),
                env.getProperty("app.vector.max-retry", Integer.class, 3),
                "app.vector.* (VECTOR_ENABLED / EMBED_ENABLED)",
                "条款分片向量化并写入向量索引；处理超时 "
                        + env.getProperty("app.vector.processing-timeout-minutes", "5") + " 分钟"));

        list.add(new OpsConfigVO(
                "law-status", "时效状态重算",
                env.getProperty("app.law-status.enabled", Boolean.class, true),
                "cron",
                env.getProperty("app.law-status.cron", "0 30 2 * * ?"),
                null,
                "app.law-status.* (LAW_STATUS_ENABLED / LAW_STATUS_CRON)",
                "按生效日期重算法规时效：未生效→现行有效、现行→已失效、多版本现行版重算；"
                        + "repealed/amended 跳过。手动预演/执行 POST /ops/law-status/reconcile?dryRun=true|false"));

        return list;
    }

    private static String interval(String ms) {
        try {
            long v = Long.parseLong(ms);
            return v % 1000 == 0 ? ("每 " + (v / 1000) + " 秒") : ("每 " + v + " 毫秒");
        } catch (NumberFormatException e) {
            return "每 " + ms + " 毫秒";
        }
    }
}
