package cn.xku.law.ops;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ParseRepairIssueVO {
    private Long id;
    private String bizType;
    private Long bizId;
    private String bizTitle;
    private String parserType;
    private String layoutType;
    private String source;
    private String reason;
    private String status;
    private Long qualityIssueId;
    private String issueDesc;
    private LocalDateTime createTime;
    private LocalDateTime resolvedTime;
}
