package cn.xku.law.law.service;

/**
 * 单文档时效重算的结果，供批量作业累计计数。
 *
 * @param changed               是否发生了落库变更（status 或现行版任一变化；dryRun 时表示「本会变」）
 * @param oldStatus             变更前 status
 * @param newStatus             目标 status（未变时等于 oldStatus）
 * @param currentVersionChanged 现行版指向/日期是否变化（与 status 变化可同时发生）
 */
public record TimelinessReconcileResult(boolean changed, String oldStatus, String newStatus,
                                        boolean currentVersionChanged) {

    public static TimelinessReconcileResult unchanged(String status) {
        return new TimelinessReconcileResult(false, status, status, false);
    }
}
