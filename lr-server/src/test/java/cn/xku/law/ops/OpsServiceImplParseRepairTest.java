package cn.xku.law.ops;

import cn.xku.law.collect.parser.ParseInput;
import cn.xku.law.collect.parser.ParseResult;
import cn.xku.law.collect.parser.ParsedArticle;
import cn.xku.law.collect.parser.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsServiceImplParseRepairTest {

    @Mock
    private ParserRegistry parserRegistry;

    @InjectMocks
    private OpsServiceImpl opsService;

    @Test
    void decisionPreviewClassifiesAmendmentAndEffectiveBlocks() {
        ParseRepairPreviewRequest request = new ParseRepairPreviewRequest();
        request.setParserType("decision_text");
        request.setText("""
                全国人民代表大会常务委员会关于修改《中华人民共和国注册会计师法》的决定
                将第二条修改为：注册会计师行业应当加强监督管理。
                本决定自公布之日起施行。
                """);

        List<ParsedBlockDraft> blocks = opsService.previewParseRepair(request);

        assertThat(blocks).extracting(ParsedBlockDraft::getBlockType)
                .contains("preamble", "amendment_item", "effective_clause");
    }

    @Test
    void articlePreviewMapsParsedArticlesToBlocks() {
        ParsedArticle article = new ParsedArticle("第一条", null, "第一条 为加强管理，制定本法。", 1, 1);
        article.setChapterNo("第一章");
        article.setChapterTitle("总则");
        when(parserRegistry.parse(any(ParseInput.class)))
                .thenReturn(ParseResult.parsed("text", List.of(article)));
        ParseRepairPreviewRequest request = new ParseRepairPreviewRequest();
        request.setParserType("law_article");
        request.setText("第一章 总则\n第一条 为加强管理，制定本法。");

        List<ParsedBlockDraft> blocks = opsService.previewParseRepair(request);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getBlockType()).isEqualTo("article");
        assertThat(blocks.get(0).getBlockNo()).isEqualTo("第一条");
        assertThat(blocks.get(0).getChapterTitle()).isEqualTo("总则");
    }
}
