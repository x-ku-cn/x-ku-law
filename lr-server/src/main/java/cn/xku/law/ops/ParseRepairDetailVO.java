package cn.xku.law.ops;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ParseRepairDetailVO {
    private String bizType;
    private Long bizId;
    private String bizTitle;
    private String parserType;
    private String layoutType;
    private String contentText;
    private Long fileId;
    private Long repairIssueId;
    private Long qualityIssueId;
    private Map<String, Object> metadata;
    private List<ParsedBlockDraft> blocks;
}
