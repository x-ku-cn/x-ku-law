package cn.xku.law.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ParseRepairCreateRequest {
    @NotBlank
    private String bizType;
    @NotNull
    private Long bizId;
    private String parserType;
    private String layoutType;
    private String source;
    @NotBlank
    private String reason;
    private Long qualityIssueId;
}
