package cn.xku.law.ops;

import lombok.Data;

/** 时效状态按日期重算的统计结果。计数项非互斥（同一文档可同时计入多项）。 */
@Data
public class LawStatusReconcileResultVO {

    /** 是否仅预演（不落库/不留痕/不入队）。 */
    private boolean dryRun;
    /** 扫描的文档数。 */
    private int documentsScanned;
    /** 状态变为「现行有效」的文档数（未生效到达实施日期）。 */
    private int becameEffective;
    /** 状态变为「已失效」的文档数（到达失效日期）。 */
    private int becameExpired;
    /** 现行版指向/日期被重算对齐的文档数（多版本切换现行版）。 */
    private int currentVersionRecomputed;
    /** 无需变更的文档数。 */
    private int unchanged;
    /** 处理失败被跳过的文档数（异常留痕，不拖垮整体）。 */
    private int documentsFailed;
}
