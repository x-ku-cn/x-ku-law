package cn.xku.law.collect.parser;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 解析出的单条条款。条款本身仍是扁平列表，但每条额外携带其所属的章/节归属
 * （由 {@code PlainTextArticleParser} 的行首标题识别得出），供落库到
 * lr_law_article 的 chapter_no/chapter_title/section_no/section_title 列。
 */
@Data
@NoArgsConstructor
public class ParsedArticle {

    /** 条款号，如 "第一条" */
    private String articleNo;
    private String articleTitle;
    private String contentText;
    /** 文档内顺序，从 1 开始 */
    private Integer articleOrder;
    /** 层级深度，扁平解析固定为 1 */
    private Integer articleLevel;

    /** 所属章编号，如 "第一章"（含"编/篇"，就近者；无则 null） */
    private String chapterNo;
    private String chapterTitle;
    /** 所属节编号，如 "第一节"（无则 null） */
    private String sectionNo;
    private String sectionTitle;

    /** 保留原有 5 参构造：章节字段由调用方在构造后按需 setter 赋值。 */
    public ParsedArticle(String articleNo, String articleTitle, String contentText,
                         Integer articleOrder, Integer articleLevel) {
        this.articleNo = articleNo;
        this.articleTitle = articleTitle;
        this.contentText = contentText;
        this.articleOrder = articleOrder;
        this.articleLevel = articleLevel;
    }
}
