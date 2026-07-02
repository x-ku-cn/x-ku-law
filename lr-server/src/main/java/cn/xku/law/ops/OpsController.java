package cn.xku.law.ops;

import cn.xku.law.collect.domain.CollectRecordDO;
import cn.xku.law.collect.domain.CollectTaskDO;
import cn.xku.law.common.annotation.OperLog;
import cn.xku.law.common.exception.AppException;
import cn.xku.law.common.exception.ErrorCode;
import cn.xku.law.common.result.CommonResult;
import cn.xku.law.common.result.PageResult;
import cn.xku.law.law.domain.LawAiTaskDO;
import cn.xku.law.law.domain.LawProcessTaskDO;
import cn.xku.law.law.domain.SearchIndexTaskDO;
import cn.xku.law.law.domain.VectorSyncTaskDO;
import cn.xku.law.law.schedule.SearchIndexTaskProcessor;
import cn.xku.law.law.schedule.VectorSyncTaskProcessor;
import cn.xku.law.law.domain.DataAuditRecordDO;
import cn.xku.law.law.domain.DataQualityIssueDO;
import cn.xku.law.process.LawAiTaskProcessor;
import cn.xku.law.process.LawProcessTaskProcessor;
import cn.xku.law.subscription.domain.AlertDeliveryDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 平台运维监控 API：只读查看采集 / 处理管线 / 检索索引 / 向量同步等调度任务状态，
 * 并对 failed 任务发起手动重试。仅平台管理员（system:ops:view）可用。
 */
@Tag(name = "运维监控", description = "调度任务状态查看与失败重试")
@SecurityRequirement(name = "Bearer")
@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsService opsService;
    // 调度处理器均为条件装配（app.process/app.search/app.vector enabled），用 ObjectProvider 软依赖：
    // 未启用时 getIfAvailable()==null，触发接口给出明确「未启用」提示而非 500。
    private final ObjectProvider<LawProcessTaskProcessor> processProcessorProvider;
    private final ObjectProvider<LawAiTaskProcessor> aiProcessorProvider;
    private final ObjectProvider<SearchIndexTaskProcessor> indexProcessorProvider;
    private final ObjectProvider<VectorSyncTaskProcessor> vectorProcessorProvider;

    @Operation(summary = "定时任务配置（只读）", description = "返回各调度任务当前的启用状态、cron/间隔、重试与对应配置项；改动需改 yaml/env 后重启")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/config")
    public CommonResult<List<OpsConfigVO>> config() {
        return CommonResult.success(opsService.schedulerConfig());
    }

    // ===== 法规处理管线 =====

    @Operation(summary = "分页查询法规处理管线任务", description = "status 可选：pending/processing/done/failed")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/process-tasks")
    public CommonResult<PageResult<LawProcessTaskDO>> processTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageProcessTasks(status, pageNo, pageSize));
    }

    @Operation(summary = "重试失败的处理任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/process-tasks/{id}/retry")
    public CommonResult<Boolean> retryProcessTask(@PathVariable Long id) {
        return CommonResult.success(opsService.retryProcessTask(id));
    }

    @Operation(summary = "重试全部失败的处理任务", description = "将所有 failed 处理任务重置为 pending，返回重置条数")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/process-tasks/retry-all")
    public CommonResult<Integer> retryAllProcessTasks() {
        return CommonResult.success(opsService.retryAllProcessTasks());
    }

    @Operation(summary = "立即处理一批待处理任务", description = "不等待轮询，立刻消费一批 pending 处理任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/process-tasks/run")
    public CommonResult<String> runProcessTasks() {
        LawProcessTaskProcessor processor = processProcessorProvider.getIfAvailable();
        if (processor == null) {
            throw new AppException(ErrorCode.PARAM_ERROR, "处理管线未启用，请设置 app.process.enabled=true 后重启");
        }
        processor.processPendingTasks();
        return CommonResult.success("已触发一批处理任务");
    }

    // ===== AI 旁路（摘要/解读） =====

    @Operation(summary = "分页查询 AI 旁路任务", description = "status 可选：pending/processing/done/failed")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/ai-tasks")
    public CommonResult<PageResult<LawAiTaskDO>> aiTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageAiTasks(status, pageNo, pageSize));
    }

    @Operation(summary = "重试失败的 AI 旁路任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/ai-tasks/{id}/retry")
    public CommonResult<Boolean> retryAiTask(@PathVariable Long id) {
        return CommonResult.success(opsService.retryAiTask(id));
    }

    @Operation(summary = "立即处理一批 AI 旁路任务", description = "不等待轮询，立刻消费一批 pending AI 任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/ai-tasks/run")
    public CommonResult<String> runAiTasks() {
        LawAiTaskProcessor processor = aiProcessorProvider.getIfAvailable();
        if (processor == null) {
            throw new AppException(ErrorCode.PARAM_ERROR, "AI 旁路未启用，请设置 app.process.ai.enabled=true 并配置 AI 模型 apikey 后重启");
        }
        processor.processPendingTasks();
        return CommonResult.success("已触发一批 AI 旁路任务");
    }

    @Operation(summary = "存量回填 AI 任务", description = "为已发布且尚无 AI 任务/解读的版本批量入队，用于首次开启 AI 后扫描全库")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/ai-tasks/backfill")
    public CommonResult<Integer> backfillAiTasks() {
        int enqueued = opsService.backfillAiTasks();
        return CommonResult.success(enqueued);
    }

    // ===== 条款章节回填 =====

    @Operation(summary = "存量回填条款章节归属",
            description = "重解析各版本已存正文，按条号匹配并逐字校验后回填 chapter/section 列；"
                    + "只碰四列不触分片，零重嵌入。dryRun=true（默认）只预演统计不落库；"
                    + "fromVersionId 用于中断后从某版本 id 之后续跑（幂等可重跑）。")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/articles/backfill-chapters")
    public CommonResult<ChapterBackfillResultVO> backfillArticleChapters(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) Long fromVersionId) {
        return CommonResult.success(opsService.backfillArticleChapters(dryRun, fromVersionId));
    }

    // ===== 时效状态重算 =====

    @Operation(summary = "按生效日期重算法规时效状态",
            description = "扫全库文档：未生效且实施日期已到→现行有效；现行且失效日期已到→已失效；"
                    + "多版本按生效日期重算现行版。源权威的 repealed/amended 跳过。"
                    + "dryRun=true（默认）只预演统计不落库。")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/law-status/reconcile")
    public CommonResult<LawStatusReconcileResultVO> reconcileLawStatus(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return CommonResult.success(opsService.reconcileLawStatus(dryRun));
    }

    // ===== 采集接入 =====

    @Operation(summary = "分页查询采集记录", description = "status 可选：pending/processing/success/failed")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/collect/records")
    public CommonResult<PageResult<CollectRecordDO>> collectRecords(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageCollectRecords(status, pageNo, pageSize));
    }

    @Operation(summary = "分页查询采集任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/collect/tasks")
    public CommonResult<PageResult<CollectTaskDO>> collectTasks(
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageCollectTasks(pageNo, pageSize));
    }

    // ===== 检索索引同步 =====

    @Operation(summary = "分页查询检索索引同步任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/index-tasks")
    public CommonResult<PageResult<SearchIndexTaskDO>> indexTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageIndexTasks(status, pageNo, pageSize));
    }

    @Operation(summary = "重试失败的索引同步任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/index-tasks/{id}/retry")
    public CommonResult<Boolean> retryIndexTask(@PathVariable Long id) {
        return CommonResult.success(opsService.retryIndexTask(id));
    }

    @Operation(summary = "重试全部失败的索引同步任务", description = "将所有 failed 索引同步任务重置为 pending，返回重置条数")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/index-tasks/retry-all")
    public CommonResult<Integer> retryAllIndexTasks() {
        return CommonResult.success(opsService.retryAllIndexTasks());
    }

    @Operation(summary = "存量回填检索索引", description = "为已发布且为当前版本的法规批量入队 upsert 索引任务，用于新增索引字段（如 regionCode）后回填全库")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/index-tasks/backfill")
    public CommonResult<Integer> backfillIndexTasks() {
        int enqueued = opsService.backfillIndexTasks();
        return CommonResult.success(enqueued);
    }

    @Operation(summary = "立即处理一批索引同步任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/index-tasks/run")
    public CommonResult<String> runIndexTasks() {
        SearchIndexTaskProcessor processor = indexProcessorProvider.getIfAvailable();
        if (processor == null) {
            throw new AppException(ErrorCode.PARAM_ERROR, "检索索引同步未启用，请设置 app.search.enabled=true 后重启");
        }
        processor.processPendingTasks();
        return CommonResult.success("已触发一批索引同步任务");
    }

    // ===== 向量同步 =====

    @Operation(summary = "分页查询向量同步任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/vector-tasks")
    public CommonResult<PageResult<VectorSyncTaskDO>> vectorTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageVectorTasks(status, pageNo, pageSize));
    }

    @Operation(summary = "重试失败的向量同步任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/vector-tasks/{id}/retry")
    public CommonResult<Boolean> retryVectorTask(@PathVariable Long id) {
        return CommonResult.success(opsService.retryVectorTask(id));
    }

    @Operation(summary = "重试全部失败的向量同步任务", description = "将所有 failed 向量同步任务重置为 pending，返回重置条数")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/vector-tasks/retry-all")
    public CommonResult<Integer> retryAllVectorTasks() {
        return CommonResult.success(opsService.retryAllVectorTasks());
    }

    @Operation(summary = "立即处理一批向量同步任务")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "trigger")
    @PostMapping("/vector-tasks/run")
    public CommonResult<String> runVectorTasks() {
        VectorSyncTaskProcessor processor = vectorProcessorProvider.getIfAvailable();
        if (processor == null) {
            throw new AppException(ErrorCode.PARAM_ERROR, "向量同步未启用，请设置 app.vector.enabled=true 后重启");
        }
        processor.processPendingTasks();
        return CommonResult.success("已触发一批向量同步任务");
    }

    // ===== 订阅预警投递 =====

    @Operation(summary = "分页查询订阅预警投递记录", description = "status 可选：pending/sent/failed")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @GetMapping("/alert-deliveries")
    public CommonResult<PageResult<AlertDeliveryDO>> alertDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageAlertDeliveries(status, pageNo, pageSize));
    }

    @Operation(summary = "重投失败/待发的预警投递", description = "走真实站内信投递并回写 sent/failed")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/alert-deliveries/{id}/retry")
    public CommonResult<?> retryAlertDelivery(@PathVariable Long id) {
        opsService.retryAlertDelivery(id);
        return CommonResult.success();
    }

    @Operation(summary = "重投全部失败的预警投递", description = "对所有 failed 预警投递发起真实重投，返回处理条数")
    @PreAuthorize("hasAuthority('system:ops:view')")
    @OperLog(module = "运维监控", type = "retry")
    @PostMapping("/alert-deliveries/retry-all")
    public CommonResult<Integer> retryAllAlertDeliveries() {
        return CommonResult.success(opsService.retryAllAlertDeliveries());
    }

    // ===== 数据治理：质量问题 / 审核留痕 =====

    @Operation(summary = "分页查询数据质量问题", description = "status 可选：open/resolved；issueType 可选：parse_error/missing_field/...")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @GetMapping("/quality-issues")
    public CommonResult<PageResult<DataQualityIssueDO>> qualityIssues(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String issueType,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageQualityIssues(status, issueType, pageNo, pageSize));
    }

    @Operation(summary = "标记质量问题已解决")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @OperLog(module = "运维监控", type = "resolve")
    @PutMapping("/quality-issues/{id}/resolve")
    public CommonResult<?> resolveQualityIssue(@PathVariable Long id) {
        opsService.resolveQualityIssue(id);
        return CommonResult.success();
    }

    // ===== 内容解析修复 =====

    @Operation(summary = "分页查询内容解析修复单", description = "支持 bizType/parserType/status 过滤；默认聚合系统 parse_error 与人工修复单")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @GetMapping("/parse-repair/issues")
    public CommonResult<PageResult<ParseRepairIssueVO>> parseRepairIssues(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String parserType,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageParseRepairIssues(status, bizType, parserType, pageNo, pageSize));
    }

    @Operation(summary = "主动创建内容解析修复单")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @OperLog(module = "解析修复", type = "create")
    @PostMapping("/parse-repair/issues")
    public CommonResult<ParseRepairIssueVO> createParseRepairIssue(
            @Valid @RequestBody ParseRepairCreateRequest request) {
        return CommonResult.success(opsService.createParseRepairIssue(request));
    }

    @Operation(summary = "查询内容解析修复目标详情")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @GetMapping("/parse-repair/targets/{bizType}/{bizId}")
    public CommonResult<ParseRepairDetailVO> parseRepairTarget(
            @PathVariable String bizType,
            @PathVariable Long bizId) {
        return CommonResult.success(opsService.getParseRepairTarget(bizType, bizId));
    }

    @Operation(summary = "预览内容解析结果")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @PostMapping("/parse-repair/preview")
    public CommonResult<List<ParsedBlockDraft>> previewParseRepair(
            @Valid @RequestBody ParseRepairPreviewRequest request) {
        return CommonResult.success(opsService.previewParseRepair(request));
    }

    @Operation(summary = "保存内容解析修复结果")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @PutMapping("/parse-repair/targets/{bizType}/{bizId}/blocks")
    public CommonResult<?> saveParseRepairBlocks(
            @PathVariable String bizType,
            @PathVariable Long bizId,
            @Valid @RequestBody ParseRepairSaveRequest request) {
        opsService.saveParseRepairBlocks(bizType, bizId, request);
        return CommonResult.success();
    }

    @Operation(summary = "分页查询数据审核留痕", description = "auditType 可选：version_publish/law_import/...")
    @PreAuthorize("hasAuthority('system:ops:audit')")
    @GetMapping("/audit-records")
    public CommonResult<PageResult<DataAuditRecordDO>> auditRecords(
            @RequestParam(required = false) String auditType,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        return CommonResult.success(opsService.pageAuditRecords(auditType, pageNo, pageSize));
    }
}
