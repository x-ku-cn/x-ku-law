package cn.xku.law.ops;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ParseRepairPreviewRequest {
    private String bizType;
    private String parserType;
    @NotBlank
    private String text;
}
