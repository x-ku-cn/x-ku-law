package cn.xku.law.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class ParseRepairSaveRequest {
    private Long repairIssueId;
    private Long qualityIssueId;
    private String parserType;
    private String layoutType;
    private String contentText;
    private Boolean triggerSync;
    @Valid
    @NotEmpty
    private List<ParsedBlockDraft> blocks;
}
