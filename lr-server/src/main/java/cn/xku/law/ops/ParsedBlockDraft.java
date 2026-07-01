package cn.xku.law.ops;

import lombok.Data;

import java.util.Map;

/** 解析修复编辑器使用的通用结构块。 */
@Data
public class ParsedBlockDraft {
    /** article/body_paragraph/amendment_item/effective_clause/preamble 等。 */
    private String blockType;
    private String blockNo;
    private String blockTitle;
    private Integer blockOrder;
    private Integer blockLevel;
    private String chapterNo;
    private String chapterTitle;
    private String sectionNo;
    private String sectionTitle;
    private String contentText;
    private Map<String, Object> metadata;
}
