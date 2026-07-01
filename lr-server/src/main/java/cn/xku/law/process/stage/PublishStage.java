package cn.xku.law.process.stage;

import cn.xku.law.law.domain.LawVersionDO;
import cn.xku.law.law.service.LawDocumentService;
import cn.xku.law.law.service.LawVersionService;
import cn.xku.law.process.DataGovernanceRecorder;
import cn.xku.law.process.LawProcessingContext;
import cn.xku.law.process.LawProcessingStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阶段 30：发布版本并重算文档现行版。
 * 复用 {@link LawVersionService#publishVersion}（置 published、更新 currentVersionId、
 * 入队搜索索引 + 向量同步任务、发布订阅事件），随后调用
 * {@link LawDocumentService#reconcileTimeliness} 按生效日期对齐现行版与时效 status。
 * 幂等：版本已发布则跳过 publish（避免重复入队/抛错），仍重算时效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishStage implements LawProcessingStage {

    /**
     * 是否抑制订阅预警。沿用采集侧的同一开关：初始全量历史回填置 true 避免海量预警，
     * 增量接入（定时/上传）保持 false 正常预警。
     */
    @Value("${app.collect.suppress-alerts:false}")
    private boolean suppressAlerts;

    private final LawVersionService lawVersionService;
    private final LawDocumentService lawDocumentService;
    private final DataGovernanceRecorder governanceRecorder;

    @Override
    public String name() {
        return "publish";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void process(LawProcessingContext ctx) {
        LawVersionDO version = lawVersionService.getById(ctx.getVersionId());
        if (version == null) {
            log.warn("[Publish] versionId={} not found, skip", ctx.getVersionId());
            return;
        }
        if (!"published".equals(version.getVersionStatus())) {
            lawVersionService.publishVersion(ctx.getVersionId(), suppressAlerts);
            log.info("[Publish] versionId={} published (suppressAlerts={})", ctx.getVersionId(), suppressAlerts);
            governanceRecorder.recordPublishAudit(ctx.getVersionId(), ctx.getDocumentId(),
                    "{\"versionId\":" + ctx.getVersionId() + ",\"documentId\":" + ctx.getDocumentId()
                            + ",\"versionNo\":\"" + safe(version.getVersionNo()) + "\"}");
        } else {
            log.debug("[Publish] versionId={} already published, skip publish", ctx.getVersionId());
        }
        lawDocumentService.reconcileTimeliness(ctx.getDocumentId(), java.time.LocalDate.now(), false);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
