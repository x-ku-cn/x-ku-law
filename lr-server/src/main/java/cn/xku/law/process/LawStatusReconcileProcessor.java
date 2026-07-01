package cn.xku.law.process;

import cn.xku.law.ops.LawStatusReconcileResultVO;
import cn.xku.law.ops.OpsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 时效状态定时重算：按 cron（默认每日 02:30）跑一次全库 {@link OpsService#reconcileLawStatus}，
 * 让「尚未生效」到达实施日期后自动转「现行有效」、到达失效日期转「已失效」、多版本切换现行版。
 * 仅在 {@code app.law-status.enabled=true}（默认开）时启用。手动预演/执行走
 * {@code POST /ops/law-status/reconcile}。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.law-status", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class LawStatusReconcileProcessor {

    private final OpsService opsService;

    @Scheduled(cron = "${app.law-status.cron:0 30 2 * * ?}")
    public void scheduledReconcile() {
        LawStatusReconcileResultVO r = opsService.reconcileLawStatus(false);
        log.info("[LawStatusReconcile] scheduled done: scanned={} becameEffective={} becameExpired={} currentRecomputed={} failed={}",
                r.getDocumentsScanned(), r.getBecameEffective(), r.getBecameExpired(),
                r.getCurrentVersionRecomputed(), r.getDocumentsFailed());
    }
}
