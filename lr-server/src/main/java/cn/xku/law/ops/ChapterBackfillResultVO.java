package cn.xku.law.ops;

import lombok.Data;

/** 章节存量回填的统计结果。 */
@Data
public class ChapterBackfillResultVO {

    /** 是否仅预演（不落库）。 */
    private boolean dryRun;
    /** 本次起始游标（version_id &gt; fromVersionId 开始）。 */
    private long fromVersionId;
    /** 本次结束时的游标（最后处理到的 version_id），用于中断后续跑。 */
    private long lastVersionId;
    /** 扫描的版本数（content_text 非空）。 */
    private int versionsScanned;
    /** 命中并更新章节列的条款数（dryRun 时为「可更新」条数）。 */
    private int articlesUpdated;
    /** 存在「重解析条文与库中不一致」被跳过条款的版本数（这些版本已记质量问题备查）。 */
    private int versionsWithMismatch;
    /** 被跳过的条款数（条号不匹配或正文逐字校验不通过）。 */
    private int articlesSkipped;
    /** 整页失败被跳过的版本数（如远程连接闪断/超时/锁）；可用 fromVersionId 续跑补齐。 */
    private int versionsFailed;
}
