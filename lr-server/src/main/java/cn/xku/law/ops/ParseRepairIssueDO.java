package cn.xku.law.ops;

import cn.xku.law.common.domain.BaseDO;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 通用内容解析修复单，对应 lr_parse_repair_issue。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("lr_parse_repair_issue")
public class ParseRepairIssueDO extends BaseDO {

    /** 业务对象类型，如 law_version；未来可扩展 case_document/policy_document 等。 */
    private String bizType;
    private Long bizId;
    private String parserType;
    private String layoutType;
    /** manual/system/quality_issue 等来源。 */
    private String source;
    private String reason;
    /** open/resolved/cancelled */
    private String status;
    private String createdBy;
    private String resolvedBy;
    private Long qualityIssueId;
    private LocalDateTime resolvedTime;
}
