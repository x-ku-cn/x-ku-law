package cn.xku.law.process.stage;

import cn.xku.law.collect.parser.ParseInput;
import cn.xku.law.collect.parser.ParseResult;
import cn.xku.law.collect.parser.ParsedArticle;
import cn.xku.law.collect.parser.ParserRegistry;
import cn.xku.law.law.domain.LawArticleSegmentDO;
import cn.xku.law.law.domain.dto.LawArticleCreateDTO;
import cn.xku.law.law.mapper.LawArticleMapper;
import cn.xku.law.law.mapper.LawArticleSegmentMapper;
import cn.xku.law.law.service.LawArticleService;
import cn.xku.law.process.DataGovernanceRecorder;
import cn.xku.law.process.LawProcessingContext;
import cn.xku.law.process.LawProcessingStage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段 20：把提取的正文拆为条款（lr_law_article）并切分为向量化分片（lr_law_article_segment，
 * embedding_status=pending），供 VectorSyncTaskProcessor 嵌入。复用 {@link ParserRegistry} 的
 * 纯文本拆条解析器（按「第X条」）。无正文时跳过（保留仅元数据语义）；正文无可识别条款时
 * 退化为单条「全文条款」，保证检索/向量覆盖。
 *
 * <p>幂等：写入前物理清空本版本旧条款与分片，任务重跑安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentationStage implements LawProcessingStage {

    @Value("${app.process.segment-max-chars:1000}")
    private int segmentMaxChars;

    private final ParserRegistry parserRegistry;
    private final LawArticleService lawArticleService;
    private final LawArticleMapper lawArticleMapper;
    private final LawArticleSegmentMapper segmentMapper;
    private final DataGovernanceRecorder governanceRecorder;

    @Override
    public String name() {
        return "segmentation";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    // READ_COMMITTED：本阶段「按 version_id 范围删旧条款/分片 → 连续 INSERT」。MySQL 默认 REPEATABLE_READ 下
    // 范围 DELETE 会在 idx_article_version / idx_segment_version 上加间隙锁（version 首跑无旧行时命中 0 行，
    // 退化为纯 gap lock），并发处理不同 version 的事务彼此的 gap lock 与 insert-intent lock 交叉 → 死锁
    // （MySQLTransactionRollbackException: Deadlock found）。RC 下范围 DELETE 不加间隙锁，只锁命中的真实行，
    // 不同 version 的 INSERT 互不冲突，从根上消除该类死锁。
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void process(LawProcessingContext ctx) {
        String text = ctx.getExtractedText();
        if (!StringUtils.hasText(text)) {
            log.debug("[Segmentation] versionId={} has no extracted text, skip", ctx.getVersionId());
            return;
        }

        // 幂等清场：物理删除旧条款/分片（逻辑删除会占用唯一键导致重插冲突）。
        segmentMapper.physicalDeleteByVersion(ctx.getVersionId());
        lawArticleMapper.physicalDeleteByVersion(ctx.getVersionId());

        ParseResult parse = parserRegistry.parse(ParseInput.ofText(text, null));
        List<ParsedArticle> articles = parse.getArticles();

        int articleCount = 0;
        int segmentCount = 0;
        if (articles == null || articles.isEmpty()) {
            Long articleId = createArticle(ctx, null, text, 1);
            segmentCount += createSegments(ctx, articleId, text);
            articleCount = 1;
            governanceRecorder.recordQualityIssue("law_version", ctx.getVersionId(), "parse_error",
                    "normal", "未识别「第X条」结构，已退化为单条全文条款，建议人工复核条款拆分");
        } else {
            // 防御纵深：解析器已用条号递增保证同版本条号不重复，此处再按条号去重一道，确保任何
            // 残留重复都不会撞 uk_article_no(tenant_id,version_id,article_no) 唯一键而回滚整篇文档。
            // articleNo 为空的退化「全文条款」只会有一条，不参与去重。
            Set<String> seenNos = new HashSet<>();
            for (ParsedArticle a : articles) {
                if (!StringUtils.hasText(a.getContentText())) continue;
                if (StringUtils.hasText(a.getArticleNo()) && !seenNos.add(a.getArticleNo())) {
                    log.warn("[Segmentation] versionId={} duplicate articleNo={} skipped",
                            ctx.getVersionId(), a.getArticleNo());
                    continue;
                }
                Long articleId = createArticle(ctx, a, a.getContentText(),
                        a.getArticleOrder() != null ? a.getArticleOrder() : articleCount + 1);
                segmentCount += createSegments(ctx, articleId, a.getContentText());
                articleCount++;
            }
        }
        log.info("[Segmentation] versionId={} created {} articles, {} segments",
                ctx.getVersionId(), articleCount, segmentCount);
    }

    /**
     * 建一条 lr_law_article。{@code parsed} 为解析出的条款（含章/节归属），退化「全文条款」传 null。
     * 章/节列直接透传解析结果，无解析上下文时为 null。
     */
    private Long createArticle(LawProcessingContext ctx, ParsedArticle parsed, String content, int order) {
        LawArticleCreateDTO dto = new LawArticleCreateDTO();
        dto.setDocumentId(ctx.getDocumentId());
        dto.setVersionId(ctx.getVersionId());
        if (parsed != null) {
            dto.setArticleNo(parsed.getArticleNo());
            dto.setArticleTitle(parsed.getArticleTitle());
            dto.setChapterNo(parsed.getChapterNo());
            dto.setChapterTitle(parsed.getChapterTitle());
            dto.setSectionNo(parsed.getSectionNo());
            dto.setSectionTitle(parsed.getSectionTitle());
        }
        dto.setArticleOrder(order);
        dto.setArticleLevel(1);
        dto.setContentText(content);
        dto.setStatus("normal");
        return lawArticleService.createArticle(dto);
    }

    /** 把条款正文切成 ≤segmentMaxChars 的分片入库，返回分片数。 */
    private int createSegments(LawProcessingContext ctx, Long articleId, String content) {
        List<String> chunks = splitByLength(content, segmentMaxChars);
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            LawArticleSegmentDO seg = new LawArticleSegmentDO();
            seg.setArticleId(articleId);
            seg.setVersionId(ctx.getVersionId());
            seg.setSegmentNo(i + 1);
            seg.setSegmentText(chunk);
            seg.setSegmentHash(sha256(chunk));
            seg.setTokenCount(chunk.length());
            seg.setEmbeddingStatus("pending");
            segmentMapper.insert(seg);
        }
        return chunks.size();
    }

    /** 按字符长度切分；尽量在长度上限内整段返回，至少返回一片。 */
    private static List<String> splitByLength(String text, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String t = text.trim();
        if (t.length() <= maxChars) {
            chunks.add(t);
            return chunks;
        }
        for (int start = 0; start < t.length(); start += maxChars) {
            chunks.add(t.substring(start, Math.min(t.length(), start + maxChars)));
        }
        return chunks;
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
