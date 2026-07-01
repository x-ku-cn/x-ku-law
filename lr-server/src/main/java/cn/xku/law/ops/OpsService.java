package cn.xku.law.ops;

import cn.xku.law.collect.domain.CollectRecordDO;
import cn.xku.law.collect.domain.CollectTaskDO;
import cn.xku.law.common.result.PageResult;
import cn.xku.law.law.domain.LawAiTaskDO;
import cn.xku.law.law.domain.LawProcessTaskDO;
import cn.xku.law.law.domain.DataAuditRecordDO;
import cn.xku.law.law.domain.DataQualityIssueDO;
import cn.xku.law.law.domain.SearchIndexTaskDO;
import cn.xku.law.law.domain.VectorSyncTaskDO;
import cn.xku.law.subscription.domain.AlertDeliveryDO;

import java.util.List;

/**
 * 平台运维只读监控 + 手动重试服务。面向平台管理员，统一查看采集/处理管线/索引/向量等
 * 调度任务表状态并对 failed 任务发起重试。所有任务表均为平台表（tenant_id=0），
 * 由 TenantLineHandlerImpl 白名单放行，不受租户过滤影响。
 */
public interface OpsService {

    PageResult<LawProcessTaskDO> pageProcessTasks(String status, long pageNo, long pageSize);

    /** 将 failed 处理任务重置为 pending（清错误、重置重试计数与时间戳），下轮调度重跑。 */
    boolean retryProcessTask(Long id);

    /** 批量将所有 failed 处理任务重置为 pending，返回重置条数。 */
    int retryAllProcessTasks();

    PageResult<CollectRecordDO> pageCollectRecords(String status, long pageNo, long pageSize);

    PageResult<CollectTaskDO> pageCollectTasks(long pageNo, long pageSize);

    PageResult<SearchIndexTaskDO> pageIndexTasks(String status, long pageNo, long pageSize);

    boolean retryIndexTask(Long id);

    /** 批量将所有 failed 索引同步任务重置为 pending，返回重置条数。 */
    int retryAllIndexTasks();

    /** 存量回填：为已发布且为当前版本、尚无在途 upsert 的版本批量入队 upsert 任务，返回入队条数。 */
    int backfillIndexTasks();

    PageResult<VectorSyncTaskDO> pageVectorTasks(String status, long pageNo, long pageSize);

    boolean retryVectorTask(Long id);

    /** 批量将所有 failed 向量同步任务重置为 pending，返回重置条数。 */
    int retryAllVectorTasks();

    PageResult<LawAiTaskDO> pageAiTasks(String status, long pageNo, long pageSize);

    boolean retryAiTask(Long id);

    /** 存量回填：为已发布且尚无 AI 任务/解读的版本批量入队 AI 任务，返回入队条数。 */
    int backfillAiTasks();

    /**
     * 存量回填条款的章/节归属：重解析各版本已存的 content_text，按条号匹配现有条款，
     * 仅当重解析条文与库中逐字一致时才更新章/节四列（不动正文与分片，零重嵌入）。
     * 按版本 id 升序游标翻页，单页失败只跳过该页并留痕，不拖垮整体；幂等，可安全重跑。
     * @param dryRun true 时只统计不落库，用于先预演全库健康度。
     * @param fromVersionId 从该版本 id 之后开始（含义为 version_id &gt; fromVersionId），用于中断后续跑；null/0 从头。
     */
    ChapterBackfillResultVO backfillArticleChapters(boolean dryRun, Long fromVersionId);

    /**
     * 按生效日期重算全库法规时效状态：游标翻页扫描 lr_law_document，逐文档调用
     * {@link cn.xku.law.law.service.LawDocumentService#reconcileTimeliness}
     * （未生效→现行有效、现行→已失效、多版本现行版重算；repealed/amended 跳过）。
     * 单文档失败只跳过并计数，不拖垮整体；幂等，可安全重跑。
     *
     * @param dryRun true 时只统计不落库，用于先预演。
     */
    LawStatusReconcileResultVO reconcileLawStatus(boolean dryRun);

    PageResult<AlertDeliveryDO> pageAlertDeliveries(String status, long pageNo, long pageSize);

    /** 重投失败/待发的订阅预警投递记录（走真实站内信投递）。 */
    void retryAlertDelivery(Long id);

    /** 批量重投所有 failed 预警投递（走真实站内信投递），返回处理条数。 */
    int retryAllAlertDeliveries();

    PageResult<DataQualityIssueDO> pageQualityIssues(String status, String issueType, long pageNo, long pageSize);

    /** 标记质量问题已解决（置处理人/解决时间）。 */
    void resolveQualityIssue(Long id);

    PageResult<DataAuditRecordDO> pageAuditRecords(String auditType, long pageNo, long pageSize);

    PageResult<ParseRepairIssueVO> pageParseRepairIssues(String status, String bizType, String parserType,
                                                         long pageNo, long pageSize);

    ParseRepairIssueVO createParseRepairIssue(ParseRepairCreateRequest request);

    ParseRepairDetailVO getParseRepairTarget(String bizType, Long bizId);

    List<ParsedBlockDraft> previewParseRepair(ParseRepairPreviewRequest request);

    void saveParseRepairBlocks(String bizType, Long bizId, ParseRepairSaveRequest request);

    /** 当前定时任务配置（只读，来自 application.yml / 环境变量）。 */
    List<OpsConfigVO> schedulerConfig();
}
