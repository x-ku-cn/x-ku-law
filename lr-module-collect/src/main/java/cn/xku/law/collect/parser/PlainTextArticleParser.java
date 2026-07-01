package cn.xku.law.collect.parser;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本条款解析器：当输入带有纯文本正文时，按法规条款起始标记切分为扁平条款。
 * 仅处理易解析的纯文本；docx/pdf/HTML 等需先抽取为文本（后续扩展），本解析器不触碰二进制。
 */
@Component
@Order(100)
public class PlainTextArticleParser implements RawDocumentParser {

    /** 条款起始标记：第一条 / 第 12 条 / 第二十三条 / 第十条之一 等。 */
    private static final Pattern ARTICLE_MARKER =
            Pattern.compile("(第\\s*[零一二三四五六七八九十百千两0-9]+\\s*条\\s*(?:之\\s*[零一二三四五六七八九十百千两0-9]+)?)");

    /** 末尾章节标题，如“。 第四章 法律责任”，不应归入上一条内容。 */
    private static final Pattern TRAILING_CHAPTER =
            Pattern.compile("\\s*第\\s*[零一二三四五六七八九十百千两0-9]+\\s*[章节编部篇][\\s　\\u4e00-\\u9fa5]*$");

    /**
     * 章/节/编/篇标题行：整行仅由「第X章/节/编/篇 + 可选标题」构成（不含句末标点、不含条文正文）。
     * 只在「行首」识别，天然排除正文里的句中引用（如「本法第二章的规定」）；标题不含 。；: 保证不误吞正文。
     * group(1)=标记（第一章）；group(2)=标题（可空）。匹配前需先剥去行首/行尾空白（含全角空格 U+3000）。
     */
    private static final Pattern HEADING_LINE =
            Pattern.compile("^(第\\s*[零一二三四五六七八九十百千两0-9]+\\s*[章节编篇])(?:[\\s\\u3000]+([^。；;：:]*))?$");

    private static final String[] REFERENCE_PREFIXES = {
            "本法", "本条例", "本规定", "本办法", "本细则", "本规则",
            "依据", "根据", "按照", "依照", "参照",
            "违反", "适用", "援引", "引用", "规定的",
            "前款", "上述", "上列", "该", "同法"
    };

    @Override
    public String parserCode() {
        return "plain-text";
    }

    @Override
    public boolean supports(ParseInput input) {
        return input != null && StringUtils.hasText(input.getText());
    }

    @Override
    public ParseResult parse(ParseInput input) {
        String text = input.getText();
        List<ParsedArticle> articles = splitArticles(text);
        return ParseResult.parsed(text, articles);
    }

    private List<ParsedArticle> splitArticles(String text) {
        List<ParsedArticle> articles = new ArrayList<>();
        Matcher m = ARTICLE_MARKER.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> nos = new ArrayList<>();
        while (m.find()) {
            if (isReferenceMatch(text, m.start())) {
                continue;
            }
            spans.add(new int[]{m.start(), m.end()});
            nos.add(m.group(1).replaceAll("\\s+", ""));
        }
        if (spans.isEmpty()) {
            return articles;
        }

        // 真实条号一定单调递增（第三条必在第二条与第四条之间）。正文里前缀逃过 isReferenceMatch 的
        // 句中交叉引用（如「…不符合第五十三条规定的…」夹在第七十条里）会被误切成新条款，破坏递增。
        // 取按条号数值「严格递增的最长子序列」作为真实条款边界，其余标记判为误切引用；切分内容时
        // 只在被接受的边界处下刀，被丢弃标记的文本自然并回它所在的上一条（还原拼接）。
        boolean[] accepted = selectMonotonicMarkers(nos);
        List<Integer> acceptedIdx = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            if (accepted[i]) {
                acceptedIdx.add(i);
            }
        }

        // 扫描全文的行首章/节标题，按 offset 升序。用于给每个条款附上其所属章/节归属：
        // 条款按 contentStart 取「其前最近生效」的章/节状态。「目录」块里连续标题之间没有条款，
        // 状态会被后续正文的同级标题逐个覆盖，首条条款拿到的必是正文里紧邻它的那个标题 → 目录不污染。
        List<Heading> headings = scanHeadings(text);

        int order = 0;
        int hIdx = 0;
        String curChapterNo = null, curChapterTitle = null, curSectionNo = null, curSectionTitle = null;
        for (int k = 0; k < acceptedIdx.size(); k++) {
            int i = acceptedIdx.get(k);
            int contentStart = spans.get(i)[0];
            // 推进章节状态到本条起点：应用所有 offset <= contentStart 的标题。
            while (hIdx < headings.size() && headings.get(hIdx).offset <= contentStart) {
                Heading h = headings.get(hIdx++);
                if (h.section) {
                    curSectionNo = h.no;
                    curSectionTitle = h.title;
                } else {
                    curChapterNo = h.no;
                    curChapterTitle = h.title;
                    curSectionNo = null; // 进入新章，清空当前节归属
                    curSectionTitle = null;
                }
            }
            int contentEnd = (k + 1 < acceptedIdx.size()) ? spans.get(acceptedIdx.get(k + 1))[0] : text.length();
            String content = cleanArticleContent(text.substring(contentStart, contentEnd));
            if (k == acceptedIdx.size() - 1 && isIncompleteArticle(content)) {
                continue;
            }
            if (content.length() >= 10) {
                ParsedArticle article = new ParsedArticle(nos.get(i), null, content, ++order, 1);
                article.setChapterNo(curChapterNo);
                article.setChapterTitle(curChapterTitle);
                article.setSectionNo(curSectionNo);
                article.setSectionTitle(curSectionTitle);
                articles.add(article);
            }
        }
        return articles;
    }

    /** 章/节标题及其在全文中的起始偏移。 */
    private static final class Heading {
        final int offset;
        final boolean section; // true=节，false=章/编/篇
        final String no;
        final String title;

        Heading(int offset, boolean section, String no, String title) {
            this.offset = offset;
            this.section = section;
            this.no = no;
            this.title = title;
        }
    }

    /** 逐行扫描，识别独占一行的章/节标题，返回按 offset 升序的列表。 */
    private static List<Heading> scanHeadings(String text) {
        List<Heading> list = new ArrayList<>();
        int lineStart = 0;
        int len = text.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || text.charAt(i) == '\n') {
                Heading h = parseHeadingLine(text.substring(lineStart, i), lineStart);
                if (h != null) {
                    list.add(h);
                }
                lineStart = i + 1;
            }
        }
        return list;
    }

    /** 把单行解析为章/节标题；非标题行返回 null。剥去行首尾空白（含全角空格 U+3000）后整行匹配。 */
    private static Heading parseHeadingLine(String line, int lineStart) {
        String s = stripEdges(line);
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = HEADING_LINE.matcher(s);
        if (!m.matches()) {
            return null;
        }
        String marker = m.group(1).replaceAll("\\s+", "");
        String title = m.group(2) == null ? null : stripEdges(m.group(2));
        if (title != null && title.isEmpty()) {
            title = null;
        }
        // 章节标题通常很短；超长十有八九是把正文误当标题，保守放弃以免污染归属。
        if (title != null && title.length() > 60) {
            return null;
        }
        boolean section = marker.endsWith("节");
        return new Heading(lineStart, section, marker, title);
    }

    /** 去除首尾空白，包含全角空格 U+3000 与 NBSP（Java 的 \\s 不覆盖它们）。 */
    private static String stripEdges(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isBlank(s.charAt(a))) a++;
        while (b > a && isBlank(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == ' ' || c == '　';
    }

    /**
     * 在所有条款标记中选出「条号数值严格递增的最长子序列」作为真实条款边界。
     * 用最长递增子序列（LIS）而非简单贪心，是为兼容「靠前出现一个数值偏大的误引用」会错杀
     * 后面真实低值条款的情形；严格递增同时天然丢弃完全重复的条号（如两个第五十三条只留其一）。
     * 标记数通常几十到几百，O(n²) DP + 父指针重建足矣。条号无法解析（理论上正则已保证有数字）
     * 时给哨兵值并强制保留为边界，宁可多切不可丢条。
     */
    private static boolean[] selectMonotonicMarkers(List<String> nos) {
        int n = nos.size();
        long[] values = new long[n];
        boolean[] accepted = new boolean[n];
        for (int i = 0; i < n; i++) {
            values[i] = parseArticleValue(nos.get(i));
            if (values[i] < 0) {
                accepted[i] = true; // 解析失败：保守保留为边界
            }
        }

        int[] dp = new int[n];
        int[] parent = new int[n];
        int best = -1;
        for (int i = 0; i < n; i++) {
            dp[i] = 1;
            parent[i] = -1;
            if (values[i] < 0) {
                continue; // 哨兵不参与递增链
            }
            for (int j = 0; j < i; j++) {
                if (values[j] >= 0 && values[j] < values[i] && dp[j] + 1 > dp[i]) {
                    dp[i] = dp[j] + 1;
                    parent[i] = j;
                }
            }
            if (best < 0 || dp[i] > dp[best]) {
                best = i;
            }
        }
        for (int k = best; k >= 0; k = parent[k]) {
            accepted[k] = true;
        }
        return accepted;
    }

    /** 把条款号解析为可比较数值：major*10000 + minor（「之X」进 minor，使 第十条 < 第十条之一 < 第十一条）。无法解析返回 -1。 */
    private static long parseArticleValue(String articleNo) {
        if (articleNo == null) {
            return -1;
        }
        String s = articleNo.replace("第", "");
        int tiaoIdx = s.indexOf('条');
        if (tiaoIdx <= 0) {
            return -1;
        }
        long major = cnNum(s.substring(0, tiaoIdx));
        if (major < 0) {
            return -1;
        }
        long minor = 0;
        int zhiIdx = s.indexOf('之', tiaoIdx);
        if (zhiIdx >= 0 && zhiIdx + 1 < s.length()) {
            long mn = cnNum(s.substring(zhiIdx + 1));
            if (mn > 0) {
                minor = mn;
            }
        }
        return major * 10000L + minor;
    }

    /** 解析中文/阿拉伯数字（零一二三四五六七八九十百千万两，及 0-9）；无法解析返回 -1。 */
    private static long cnNum(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        boolean allDigits = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            return Long.parseLong(s);
        }
        long total = 0;
        long section = 0;
        long number = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int d = cnDigit(ch);
            if (d >= 0) {
                number = d;
                continue;
            }
            long unit = cnUnit(ch);
            if (unit == 0) {
                return -1; // 未知字符
            }
            if (unit == 10000) {
                section = (section + number) * unit;
                total += section;
                section = 0;
            } else {
                if (number == 0) {
                    number = 1; // 「十三」中的「十」= 10
                }
                section += number * unit;
            }
            number = 0;
        }
        return total + section + number;
    }

    private static int cnDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        switch (c) {
            case '零': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    private static long cnUnit(char c) {
        switch (c) {
            case '十': return 10;
            case '百': return 100;
            case '千': return 1000;
            case '万': return 10000;
            default: return 0;
        }
    }

    private boolean isReferenceMatch(String text, int matchStart) {
        if (matchStart == 0) {
            return false;
        }

        char prev = text.charAt(matchStart - 1);
        if (prev == '、' || prev == '，' || prev == ',') {
            return true;
        }

        int lookbackStart = Math.max(0, matchStart - 10);
        String prefix = text.substring(lookbackStart, matchStart);
        for (String refPrefix : REFERENCE_PREFIXES) {
            if (prefix.endsWith(refPrefix)) {
                return true;
            }
        }

        return prefix.endsWith("和")
                || prefix.endsWith("及")
                || prefix.endsWith("与")
                || prefix.endsWith("或者")
                || prefix.endsWith("或");
    }

    private String cleanArticleContent(String content) {
        if (content == null) {
            return "";
        }
        String cleaned = content.replaceAll("\\s+", " ").trim();
        return TRAILING_CHAPTER.matcher(cleaned).replaceAll("").trim();
    }

    private boolean isIncompleteArticle(String content) {
        if (content == null || content.trim().length() < 10) {
            return true;
        }
        String trimmed = content.trim();
        char last = trimmed.charAt(trimmed.length() - 1);
        return last != '。'
                && last != '；'
                && last != '：'
                && last != '"'
                && last != ')'
                && last != '）';
    }
}
