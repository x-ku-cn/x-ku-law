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
import cn.xku.law.law.domain.LawArticleSegmentDO;
import cn.xku.law.law.domain.LawDocumentDO;
import cn.xku.law.law.domain.LawProcessTaskDO;
import cn.xku.law.law.domain.LawVersionDO;
import cn.xku.law.law.domain.SearchIndexTaskDO;
import cn.xku.law.law.domain.VectorSyncTaskDO;
import cn.xku.law.law.mapper.LawAiTaskMapper;
import cn.xku.law.law.mapper.LawArticleMapper;
import cn.xku.law.law.mapper.LawArticleSegmentMapper;
import cn.xku.law.law.mapper.LawDocumentMapper;
import cn.xku.law.law.mapper.LawProcessTaskMapper;
import cn.xku.law.law.mapper.LawVersionMapper;
import cn.xku.law.law.service.LawDocumentService;
import cn.xku.law.law.service.TimelinessReconcileResult;
import cn.xku.law.ops.mapper.ParseRepairIssueMapper;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final LawArticleSegmentMapper lawArticleSegmentMapper;
    private final LawDocumentMapper lawDocumentMapper;
    private final ParseRepairIssueMapper parseRepairIssueMapper;
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
    public PageResult<ParseRepairIssueVO> pageParseRepairIssues(String status, String bizType, String parserType,
                                                                long pageNo, long pageSize) {
        String st = StringUtils.hasText(status) ? status : "open";
        List<ParseRepairIssueVO> rows = new ArrayList<>();

        LambdaQueryWrapper<ParseRepairIssueDO> issueWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(st)) {
            issueWrapper.eq(ParseRepairIssueDO::getStatus, st);
        }
        if (StringUtils.hasText(bizType)) {
            issueWrapper.eq(ParseRepairIssueDO::getBizType, bizType);
        }
        if (StringUtils.hasText(parserType)) {
            issueWrapper.eq(ParseRepairIssueDO::getParserType, parserType);
        }
        issueWrapper.orderByDesc(ParseRepairIssueDO::getId);
        Page<ParseRepairIssueDO> manualPage = parseRepairIssueMapper.selectPage(Page.of(pageNo, pageSize), issueWrapper);
        List<ParseRepairIssueDO> manualIssues = manualPage.getRecords();
        Set<String> existingOpenKeys = new LinkedHashSet<>();
        for (ParseRepairIssueDO issue : manualIssues) {
            rows.add(toRepairIssueVO(issue, null, null));
            if ("open".equals(issue.getStatus())) {
                existingOpenKeys.add(issue.getBizType() + ":" + issue.getBizId());
            }
        }

        // 系统 parse_error 质量问题可直接进入修复队列；若已有人工修复单，则不重复展示。
        if (!StringUtils.hasText(bizType) || "law_version".equals(bizType)) {
            LambdaQueryWrapper<DataQualityIssueDO> qualityWrapper = new LambdaQueryWrapper<DataQualityIssueDO>()
                    .eq(DataQualityIssueDO::getIssueType, "parse_error")
                    .eq(DataQualityIssueDO::getRefType, "law_version");
            if (StringUtils.hasText(st)) {
                qualityWrapper.eq(DataQualityIssueDO::getStatus, "resolved".equals(st) ? "resolved" : "open");
            }
            qualityWrapper.orderByDesc(DataQualityIssueDO::getId);
            Page<DataQualityIssueDO> qualityPage = qualityIssueMapper.selectPage(Page.of(pageNo, pageSize), qualityWrapper);
            List<DataQualityIssueDO> qualityIssues = qualityPage.getRecords();
            for (DataQualityIssueDO quality : qualityIssues) {
                String key = quality.getRefType() + ":" + quality.getRefId();
                if (existingOpenKeys.contains(key)) {
                    continue;
                }
                ParseRepairIssueVO vo = fromQualityIssue(quality);
                rows.add(vo);
            }
            hydrateLawVersionIssueRows(rows);
            if (StringUtils.hasText(parserType)) {
                rows.removeIf(row -> !parserType.equals(row.getParserType()));
            }
            rows.sort(Comparator.comparing(ParseRepairIssueVO::getCreateTime,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed());
            if (rows.size() > pageSize) {
                rows = new ArrayList<>(rows.subList(0, (int) pageSize));
            }
            return PageResult.of(manualPage.getTotal() + qualityPage.getTotal(), rows);
        }

        hydrateLawVersionIssueRows(rows);
        return PageResult.of(manualPage.getTotal(), rows);
    }

    @Override
    public ParseRepairIssueVO createParseRepairIssue(ParseRepairCreateRequest request) {
        ensureSupportedBizType(request.getBizType());
        LambdaQueryWrapper<ParseRepairIssueDO> existing = new LambdaQueryWrapper<ParseRepairIssueDO>()
                .eq(ParseRepairIssueDO::getBizType, request.getBizType())
                .eq(ParseRepairIssueDO::getBizId, request.getBizId())
                .eq(ParseRepairIssueDO::getStatus, "open")
                .last("limit 1");
        ParseRepairIssueDO old = parseRepairIssueMapper.selectOne(existing);
        if (old != null) {
            return toRepairIssueVO(old, null, null);
        }

        ParseRepairDetailVO target = getParseRepairTarget(request.getBizType(), request.getBizId());
        ParseRepairIssueDO issue = new ParseRepairIssueDO();
        issue.setBizType(request.getBizType());
        issue.setBizId(request.getBizId());
        issue.setParserType(firstText(request.getParserType(), target.getParserType(), "law_article"));
        issue.setLayoutType(firstText(request.getLayoutType(), target.getLayoutType(), layoutForParser(issue.getParserType())));
        issue.setSource(firstText(request.getSource(), "manual"));
        issue.setReason(request.getReason());
        issue.setStatus("open");
        issue.setCreatedBy(SecurityUtils.getCurrentUsername());
        issue.setQualityIssueId(request.getQualityIssueId());
        parseRepairIssueMapper.insert(issue);
        return toRepairIssueVO(issue, null, target.getBizTitle());
    }

    @Override
    public ParseRepairDetailVO getParseRepairTarget(String bizType, Long bizId) {
        ensureSupportedBizType(bizType);
        LawVersionDO version = lawVersionMapper.selectById(bizId);
        if (version == null) {
            throw new AppException(ErrorCode.LAW_VERSION_NOT_FOUND);
        }
        LawDocumentDO doc = lawDocumentMapper.selectById(version.getDocumentId());
        String parserType = inferParserType(doc, version);

        ParseRepairDetailVO vo = new ParseRepairDetailVO();
        vo.setBizType(bizType);
        vo.setBizId(bizId);
        vo.setBizTitle(doc != null ? doc.getTitle() : ("版本 " + bizId));
        vo.setParserType(parserType);
        vo.setLayoutType(layoutForParser(parserType));
        vo.setContentText(version.getContentText());
        vo.setFileId(version.getFileId());
        vo.setRepairIssueId(openRepairIssueId(bizType, bizId));
        vo.setQualityIssueId(openQualityIssueId("law_version", bizId, "parse_error"));
        vo.setMetadata(Map.of(
                "documentId", version.getDocumentId(),
                "documentNo", doc != null ? nullToEmpty(doc.getDocumentNo()) : "",
                "lawType", doc != null ? nullToEmpty(doc.getLawType()) : "",
                "revisionType", nullToEmpty(version.getRevisionType()),
                "publishDate", version.getPublishDate() != null ? version.getPublishDate().toString() : "",
                "effectiveDate", version.getEffectiveDate() != null ? version.getEffectiveDate().toString() : ""
        ));
        vo.setBlocks(loadBlocks(version.getId(), parserType, version.getContentText()));
        return vo;
    }

    @Override
    public List<ParsedBlockDraft> previewParseRepair(ParseRepairPreviewRequest request) {
        String parserType = firstText(request.getParserType(), "law_article");
        if ("decision_text".equals(parserType)) {
            return parseDecisionBlocks(request.getText());
        }
        return parseArticleBlocks(request.getText());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveParseRepairBlocks(String bizType, Long bizId, ParseRepairSaveRequest request) {
        ensureSupportedBizType(bizType);
        LawVersionDO version = lawVersionMapper.selectById(bizId);
        if (version == null) {
            throw new AppException(ErrorCode.LAW_VERSION_NOT_FOUND);
        }
        String parserType = firstText(request.getParserType(), "law_article");
        List<ParsedBlockDraft> blocks = request.getBlocks();
        validateBlocks(parserType, blocks);

        if (StringUtils.hasText(request.getContentText())) {
            version.setContentText(request.getContentText());
            version.setContentHash(sha256(request.getContentText()));
            lawVersionMapper.updateById(version);
        }

        enqueueVectorDeletesForExistingSegments(bizId);
        lawArticleSegmentMapper.physicalDeleteByVersion(bizId);
        lawArticleMapper.physicalDeleteByVersion(bizId);
        List<LawArticleDO> articles = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            ParsedBlockDraft block = blocks.get(i);
            articles.add(toLawArticle(version, block, i + 1, parserType));
        }
        if (!articles.isEmpty()) {
            lawArticleMapper.insertBatch(articles);
            lawArticleSegmentMapper.insertBatch(createSegments(articles));
        }

        closeRepairIssue(request.getRepairIssueId());
        Long qualityIssueId = request.getQualityIssueId();
        if (qualityIssueId == null) {
            qualityIssueId = openQualityIssueId("law_version", bizId, "parse_error");
        }
        if (qualityIssueId != null) {
            resolveQualityIssue(qualityIssueId);
        }
        if (Boolean.TRUE.equals(request.getTriggerSync())) {
            enqueueRepairSync(bizId);
        }
    }

    private void ensureSupportedBizType(String bizType) {
        if (!"law_version".equals(bizType)) {
            throw new AppException(ErrorCode.PARAM_ERROR, "暂不支持的解析修复对象类型：" + bizType);
        }
    }

    private ParseRepairIssueVO toRepairIssueVO(ParseRepairIssueDO issue, String issueDesc, String bizTitle) {
        ParseRepairIssueVO vo = new ParseRepairIssueVO();
        vo.setId(issue.getId());
        vo.setBizType(issue.getBizType());
        vo.setBizId(issue.getBizId());
        vo.setBizTitle(bizTitle);
        vo.setParserType(issue.getParserType());
        vo.setLayoutType(issue.getLayoutType());
        vo.setSource(issue.getSource());
        vo.setReason(issue.getReason());
        vo.setStatus(issue.getStatus());
        vo.setQualityIssueId(issue.getQualityIssueId());
        vo.setIssueDesc(issueDesc);
        vo.setCreateTime(issue.getCreateTime());
        vo.setResolvedTime(issue.getResolvedTime());
        return vo;
    }

    private ParseRepairIssueVO fromQualityIssue(DataQualityIssueDO quality) {
        ParseRepairIssueVO vo = new ParseRepairIssueVO();
        vo.setBizType("law_version");
        vo.setBizId(quality.getRefId());
        vo.setBizTitle("版本 " + quality.getRefId());
        vo.setParserType("law_article");
        vo.setLayoutType("article_reader");
        vo.setSource("quality_issue");
        vo.setReason(quality.getIssueDesc());
        vo.setStatus(quality.getStatus());
        vo.setQualityIssueId(quality.getId());
        vo.setIssueDesc(quality.getIssueDesc());
        vo.setCreateTime(quality.getCreateTime());
        vo.setResolvedTime(quality.getResolvedTime());
        return vo;
    }

    private void hydrateLawVersionIssueRows(List<ParseRepairIssueVO> rows) {
        List<Long> versionIds = rows.stream()
                .filter(row -> "law_version".equals(row.getBizType()) && row.getBizId() != null)
                .map(ParseRepairIssueVO::getBizId)
                .distinct()
                .toList();
        if (versionIds.isEmpty()) {
            return;
        }

        List<LawVersionDO> versions = lawVersionMapper.selectList(new LambdaQueryWrapper<LawVersionDO>()
                .select(LawVersionDO::getId, LawVersionDO::getDocumentId,
                        LawVersionDO::getVersionName, LawVersionDO::getVersionNo)
                .in(LawVersionDO::getId, versionIds));
        Map<Long, LawVersionDO> versionById = new HashMap<>();
        Set<Long> documentIds = new LinkedHashSet<>();
        for (LawVersionDO version : versions) {
            versionById.put(version.getId(), version);
            if (version.getDocumentId() != null) {
                documentIds.add(version.getDocumentId());
            }
        }

        Map<Long, LawDocumentDO> docById = new HashMap<>();
        if (!documentIds.isEmpty()) {
            List<LawDocumentDO> docs = lawDocumentMapper.selectList(new LambdaQueryWrapper<LawDocumentDO>()
                    .select(LawDocumentDO::getId, LawDocumentDO::getTitle, LawDocumentDO::getLawType)
                    .in(LawDocumentDO::getId, documentIds));
            for (LawDocumentDO doc : docs) {
                docById.put(doc.getId(), doc);
            }
        }

        for (ParseRepairIssueVO row : rows) {
            if (!"law_version".equals(row.getBizType()) || row.getBizId() == null) {
                continue;
            }
            LawVersionDO version = versionById.get(row.getBizId());
            LawDocumentDO doc = version != null ? docById.get(version.getDocumentId()) : null;
            if (doc != null && StringUtils.hasText(doc.getTitle())) {
                row.setBizTitle(doc.getTitle());
            } else if (version != null && StringUtils.hasText(version.getVersionName())) {
                row.setBizTitle(version.getVersionName());
            } else if (version != null && StringUtils.hasText(version.getVersionNo())) {
                row.setBizTitle(version.getVersionNo());
            }
            if ("quality_issue".equals(row.getSource()) && doc != null) {
                String parserType = inferParserType(doc, version);
                row.setParserType(parserType);
                row.setLayoutType(layoutForParser(parserType));
            }
        }
    }

    private Long openRepairIssueId(String bizType, Long bizId) {
        ParseRepairIssueDO issue = parseRepairIssueMapper.selectOne(new LambdaQueryWrapper<ParseRepairIssueDO>()
                .select(ParseRepairIssueDO::getId)
                .eq(ParseRepairIssueDO::getBizType, bizType)
                .eq(ParseRepairIssueDO::getBizId, bizId)
                .eq(ParseRepairIssueDO::getStatus, "open")
                .last("limit 1"));
        return issue != null ? issue.getId() : null;
    }

    private Long openQualityIssueId(String refType, Long refId, String issueType) {
        DataQualityIssueDO issue = qualityIssueMapper.selectOne(new LambdaQueryWrapper<DataQualityIssueDO>()
                .select(DataQualityIssueDO::getId)
                .eq(DataQualityIssueDO::getRefType, refType)
                .eq(DataQualityIssueDO::getRefId, refId)
                .eq(DataQualityIssueDO::getIssueType, issueType)
                .eq(DataQualityIssueDO::getStatus, "open")
                .last("limit 1"));
        return issue != null ? issue.getId() : null;
    }

    private String inferParserType(LawDocumentDO doc, LawVersionDO version) {
        String lawType = doc != null ? doc.getLawType() : null;
        String title = doc != null ? doc.getTitle() : null;
        if (isDecisionType(lawType) || isDecisionTitle(title)) {
            return "decision_text";
        }
        return "law_article";
    }

    private boolean isDecisionType(String lawType) {
        return StringUtils.hasText(lawType)
                && (lawType.contains("决定") || lawType.contains("修正案"));
    }

    private boolean isDecisionTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return false;
        }
        return title.contains("关于修改") && title.endsWith("的决定")
                || title.contains("关于废止") && title.endsWith("的决定")
                || title.contains("问题的决定")
                || title.endsWith("的决定");
    }

    private String layoutForParser(String parserType) {
        if ("decision_text".equals(parserType)) {
            return "decision_reader";
        }
        if ("generic_section".equals(parserType)) {
            return "generic_reader";
        }
        return "article_reader";
    }

    private List<ParsedBlockDraft> loadBlocks(Long versionId, String parserType, String contentText) {
        List<LawArticleDO> articles = lawArticleMapper.selectList(new LambdaQueryWrapper<LawArticleDO>()
                .eq(LawArticleDO::getVersionId, versionId)
                .orderByAsc(LawArticleDO::getArticleOrder));
        if (articles.isEmpty() && StringUtils.hasText(contentText)) {
            return previewParseRepair(textPreviewRequest(parserType, contentText));
        }
        List<ParsedBlockDraft> blocks = new ArrayList<>();
        for (LawArticleDO article : articles) {
            ParsedBlockDraft block = new ParsedBlockDraft();
            block.setBlockType("decision_text".equals(parserType) ? inferDecisionBlockType(article.getContentText()) : "article");
            block.setBlockNo(article.getArticleNo());
            block.setBlockTitle(article.getArticleTitle());
            block.setBlockOrder(article.getArticleOrder());
            block.setBlockLevel(article.getArticleLevel());
            block.setChapterNo(article.getChapterNo());
            block.setChapterTitle(article.getChapterTitle());
            block.setSectionNo(article.getSectionNo());
            block.setSectionTitle(article.getSectionTitle());
            block.setContentText(article.getContentText());
            blocks.add(block);
        }
        return blocks;
    }

    private ParseRepairPreviewRequest textPreviewRequest(String parserType, String text) {
        ParseRepairPreviewRequest request = new ParseRepairPreviewRequest();
        request.setBizType("law_version");
        request.setParserType(parserType);
        request.setText(text);
        return request;
    }

    private List<ParsedBlockDraft> parseArticleBlocks(String text) {
        ParseResult parse = parserRegistry.parse(ParseInput.ofText(text, null));
        List<ParsedBlockDraft> blocks = new ArrayList<>();
        List<ParsedArticle> articles = parse.getArticles();
        if (articles != null) {
            for (ParsedArticle article : articles) {
                ParsedBlockDraft block = new ParsedBlockDraft();
                block.setBlockType("article");
                block.setBlockNo(article.getArticleNo());
                block.setBlockTitle(article.getArticleTitle());
                block.setBlockOrder(article.getArticleOrder());
                block.setBlockLevel(article.getArticleLevel());
                block.setChapterNo(article.getChapterNo());
                block.setChapterTitle(article.getChapterTitle());
                block.setSectionNo(article.getSectionNo());
                block.setSectionTitle(article.getSectionTitle());
                block.setContentText(article.getContentText());
                blocks.add(block);
            }
        }
        if (blocks.isEmpty() && StringUtils.hasText(text)) {
            ParsedBlockDraft fallback = new ParsedBlockDraft();
            fallback.setBlockType("article");
            fallback.setBlockOrder(1);
            fallback.setBlockLevel(1);
            fallback.setContentText(text.trim());
            blocks.add(fallback);
        }
        return blocks;
    }

    private List<ParsedBlockDraft> parseDecisionBlocks(String text) {
        List<ParsedBlockDraft> blocks = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return blocks;
        }
        String[] paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n|\\n");
        int order = 0;
        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.length() < 2) {
                continue;
            }
            ParsedBlockDraft block = new ParsedBlockDraft();
            block.setBlockType(inferDecisionBlockType(p));
            block.setBlockOrder(++order);
            block.setBlockLevel(1);
            block.setContentText(p);
            if (p.length() <= 80 && (p.endsWith("决定") || p.endsWith("公告"))) {
                block.setBlockTitle(p);
            }
            blocks.add(block);
        }
        return blocks;
    }

    private String inferDecisionBlockType(String text) {
        if (!StringUtils.hasText(text)) {
            return "body_paragraph";
        }
        String t = text.trim();
        if (t.contains("修改为") || t.contains("删去") || t.contains("增加") || t.contains("改为")) {
            return "amendment_item";
        }
        if (t.contains("自") && (t.contains("施行") || t.contains("公布之日起"))) {
            return "effective_clause";
        }
        if (t.endsWith("决定") || t.contains("为了") || t.contains("根据")) {
            return "preamble";
        }
        return "body_paragraph";
    }

    private void validateBlocks(String parserType, List<ParsedBlockDraft> blocks) {
        Set<String> articleNos = new LinkedHashSet<>();
        for (ParsedBlockDraft block : blocks) {
            if (!StringUtils.hasText(block.getContentText())) {
                throw new AppException(ErrorCode.PARAM_ERROR, "结构块正文不能为空");
            }
            if ("law_article".equals(parserType) && StringUtils.hasText(block.getBlockNo())
                    && !articleNos.add(block.getBlockNo())) {
                throw new AppException(ErrorCode.PARAM_ERROR, "条号重复：" + block.getBlockNo());
            }
        }
    }

    private LawArticleDO toLawArticle(LawVersionDO version, ParsedBlockDraft block, int defaultOrder, String parserType) {
        LawArticleDO article = new LawArticleDO();
        article.setDocumentId(version.getDocumentId());
        article.setVersionId(version.getId());
        article.setParentArticleId(0L);
        article.setArticleNo("decision_text".equals(parserType) ? null : block.getBlockNo());
        article.setArticleTitle(block.getBlockTitle());
        article.setChapterNo(block.getChapterNo());
        article.setChapterTitle(block.getChapterTitle());
        article.setSectionNo(block.getSectionNo());
        article.setSectionTitle(block.getSectionTitle());
        article.setArticleOrder(block.getBlockOrder() != null ? block.getBlockOrder() : defaultOrder);
        article.setArticleLevel(block.getBlockLevel() != null ? block.getBlockLevel() : 1);
        article.setContentText(block.getContentText().trim());
        article.setContentHash(sha256(article.getContentText()));
        article.setObligationFlag(false);
        article.setPenaltyFlag(false);
        article.setStatus("normal");
        return article;
    }

    private List<LawArticleSegmentDO> createSegments(List<LawArticleDO> articles) {
        List<LawArticleSegmentDO> segments = new ArrayList<>();
        for (LawArticleDO article : articles) {
            segments.addAll(createSegments(article.getVersionId(), article.getId(), article.getContentText()));
        }
        return segments;
    }

    private List<LawArticleSegmentDO> createSegments(Long versionId, Long articleId, String content) {
        int maxChars = env.getProperty("app.process.segment-max-chars", Integer.class, 1000);
        List<String> chunks = splitByLength(content, maxChars);
        List<LawArticleSegmentDO> segments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            LawArticleSegmentDO segment = new LawArticleSegmentDO();
            segment.setArticleId(articleId);
            segment.setVersionId(versionId);
            segment.setSegmentNo(i + 1);
            segment.setSegmentText(chunk);
            segment.setSegmentHash(sha256(chunk));
            segment.setTokenCount(chunk.length());
            segment.setEmbeddingStatus("pending");
            segments.add(segment);
        }
        return segments;
    }

    private List<String> splitByLength(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String t = text == null ? "" : text.trim();
        if (t.length() <= maxChars) {
            chunks.add(t);
            return chunks;
        }
        for (int start = 0; start < t.length(); start += maxChars) {
            chunks.add(t.substring(start, Math.min(t.length(), start + maxChars)));
        }
        return chunks;
    }

    private void enqueueVectorDeletesForExistingSegments(Long versionId) {
        String index = env.getProperty("app.vector.index-name", "law_segment");
        List<LawArticleSegmentDO> oldSegments = lawArticleSegmentMapper.selectList(new LambdaQueryWrapper<LawArticleSegmentDO>()
                .select(LawArticleSegmentDO::getId, LawArticleSegmentDO::getVectorId)
                .eq(LawArticleSegmentDO::getVersionId, versionId));
        for (LawArticleSegmentDO segment : oldSegments) {
            String vectorId = firstText(segment.getVectorId(), segment.getId() != null ? String.valueOf(segment.getId()) : null);
            if (!StringUtils.hasText(vectorId)) {
                continue;
            }
            VectorSyncTaskDO task = new VectorSyncTaskDO();
            task.setRefType("law_article_segment");
            task.setRefId(segment.getId());
            task.setActionType("delete");
            task.setSyncStatus("pending");
            task.setVectorIndex(index);
            task.setVectorId(vectorId);
            task.setRetryCount(0);
            vectorTaskMapper.insert(task);
        }
    }

    private void closeRepairIssue(Long id) {
        if (id == null) {
            return;
        }
        ParseRepairIssueDO issue = parseRepairIssueMapper.selectById(id);
        if (issue == null || !"open".equals(issue.getStatus())) {
            return;
        }
        issue.setStatus("resolved");
        issue.setResolvedBy(SecurityUtils.getCurrentUsername());
        issue.setResolvedTime(LocalDateTime.now());
        parseRepairIssueMapper.updateById(issue);
    }

    private void enqueueRepairSync(Long versionId) {
        LawVersionDO version = lawVersionMapper.selectById(versionId);
        SearchIndexTaskDO indexTask = new SearchIndexTaskDO();
        indexTask.setRefType("law_version");
        indexTask.setRefId(versionId);
        indexTask.setIndexName("law_document");
        indexTask.setActionType("upsert");
        indexTask.setSyncStatus("pending");
        indexTask.setRetryCount(0);
        indexTaskMapper.insert(indexTask);

        VectorSyncTaskDO vectorTask = new VectorSyncTaskDO();
        vectorTask.setRefType("law_version");
        vectorTask.setRefId(versionId);
        vectorTask.setActionType("upsert");
        vectorTask.setSyncStatus("pending");
        vectorTask.setVectorIndex(env.getProperty("app.vector.index-name", "law_segment"));
        vectorTask.setRetryCount(0);
        vectorTaskMapper.insert(vectorTask);

        enqueueRepairAiTask(version);
    }

    private void enqueueRepairAiTask(LawVersionDO version) {
        if (version == null || version.getId() == null) {
            return;
        }
        LawAiTaskDO existing = aiTaskMapper.selectOne(new LambdaQueryWrapper<LawAiTaskDO>()
                .eq(LawAiTaskDO::getVersionId, version.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            if ("processing".equals(existing.getProcessStatus())) {
                log.info("[ParseRepair] versionId={} already has processing AI task, skip enqueue", version.getId());
                return;
            }
            LambdaUpdateWrapper<LawAiTaskDO> update = new LambdaUpdateWrapper<LawAiTaskDO>()
                    .eq(LawAiTaskDO::getId, existing.getId())
                    .ne(LawAiTaskDO::getProcessStatus, "processing")
                    .set(LawAiTaskDO::getDocumentId, version.getDocumentId())
                    .set(LawAiTaskDO::getFileId, version.getFileId())
                    .set(LawAiTaskDO::getProcessStatus, "pending")
                    .set(LawAiTaskDO::getRetryCount, 0)
                    .set(LawAiTaskDO::getErrorMessage, null)
                    .set(LawAiTaskDO::getStartedAt, null)
                    .set(LawAiTaskDO::getFinishedAt, null);
            aiTaskMapper.update(null, update);
            return;
        }

        LawAiTaskDO task = new LawAiTaskDO();
        task.setDocumentId(version.getDocumentId());
        task.setVersionId(version.getId());
        task.setFileId(version.getFileId());
        task.setProcessStatus("pending");
        task.setRetryCount(0);
        aiTaskMapper.insert(task);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
