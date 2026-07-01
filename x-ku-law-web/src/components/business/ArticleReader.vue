<template>
  <div class="reader">
    <template v-for="(article, idx) in articles" :key="article.id">
      <div v-if="isNewChapter(idx)" class="chapter-head">
        <div class="section-kicker">
          § {{ article.chapterTitle || article.chapterNo }}
          <template v-if="romanOf(article.chapterNo)"> · CHAPTER {{ romanOf(article.chapterNo) }}</template>
        </div>
        <h2 class="chapter-title">
          <span>{{ article.chapterNo }}</span>
          <span v-if="article.chapterTitle" class="chapter-name">{{ article.chapterTitle }}</span>
        </h2>
      </div>
      <div v-if="isNewSection(idx)" class="section-head">
        <span>{{ article.sectionNo }}</span>
        <span v-if="article.sectionTitle" class="section-name">{{ article.sectionTitle }}</span>
      </div>
      <article
        :id="`article-${article.id}`"
        class="article"
        :class="{ focused: activeArticleId === article.id }"
        @click="emit('select', article.id)"
      >
        <div class="article-head">
          <div class="article-no">
            <span>{{ article.articleNo || `第 ${article.articleOrder || article.id} 条` }}</span>
            <XChip v-if="article.obligationFlag" tone="accent">核心义务</XChip>
            <XChip v-if="article.penaltyFlag" tone="rose">责任条款</XChip>
          </div>
          <XButton
            v-if="activeArticleId === article.id"
            size="small"
            variant="ghost"
            class="article-copy"
            @click.stop="copyArticle(article)"
          >
            复制
          </XButton>
        </div>
        <h3 v-if="article.articleTitle">{{ article.articleTitle }}</h3>
        <p>{{ article.contentText || '该条款暂无正文。' }}</p>
      </article>
    </template>
  </div>
</template>

<script setup lang="ts">
import XButton from '@/components/common/XButton.vue';
import XChip from '@/components/common/XChip.vue';
import { useToast } from '@/composables/useToast';
import type { LawArticle } from '@/types/law';
import { copyText } from '@/utils/clipboard';

const props = defineProps<{
  articles: LawArticle[];
  /** 目录点击或滚动视口时高亮当前条款。 */
  activeArticleId?: number | null;
}>();

const emit = defineEmits<{
  select: [articleId: number];
}>();

const toast = useToast();

/** 本条是否为其所属章的首条（据 chapterNo 相对上一条变化判定）。无 chapterNo 的法规不出章头。 */
function isNewChapter(idx: number): boolean {
  const cur = props.articles[idx]?.chapterNo;
  if (!cur) return false;
  return idx === 0 || props.articles[idx - 1]?.chapterNo !== cur;
}

/** 本条是否为其所属节的首条（节变化或所在章变化时都视为新节标题）。 */
function isNewSection(idx: number): boolean {
  const cur = props.articles[idx];
  if (!cur?.sectionNo) return false;
  const prev = props.articles[idx - 1];
  if (idx === 0) return true;
  return prev?.sectionNo !== cur.sectionNo || prev?.chapterNo !== cur.chapterNo;
}

const CN_DIGIT: Record<string, number> = {
  零: 0, 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9
};

/** 把「第X章」里的中文/阿拉伯数字解析为整数；无法解析返回 0。支持到千位，足够覆盖章号。 */
function chapterValue(chapterNo?: string): number {
  if (!chapterNo) return 0;
  const s = chapterNo.replace(/第|[章节编篇]|\s/g, '');
  if (!s) return 0;
  if (/^[0-9]+$/.test(s)) return Number(s);
  let total = 0;
  let section = 0;
  let num = 0;
  for (const ch of s) {
    if (ch in CN_DIGIT) {
      num = CN_DIGIT[ch];
      continue;
    }
    const unit = ch === '十' ? 10 : ch === '百' ? 100 : ch === '千' ? 1000 : 0;
    if (unit === 0) return 0;
    if (num === 0) num = 1; // 「十三」中的「十」= 10
    section += num * unit;
    num = 0;
  }
  return total + section + num;
}

/** 章号转罗马数字（用于 CHAPTER N 副标题）；无法解析或超范围时返回空串。 */
function romanOf(chapterNo?: string): string {
  let n = chapterValue(chapterNo);
  if (n <= 0 || n >= 4000) return '';
  const table: [number, string][] = [
    [1000, 'M'], [900, 'CM'], [500, 'D'], [400, 'CD'], [100, 'C'], [90, 'XC'],
    [50, 'L'], [40, 'XL'], [10, 'X'], [9, 'IX'], [5, 'V'], [4, 'IV'], [1, 'I']
  ];
  let out = '';
  for (const [v, sym] of table) {
    while (n >= v) {
      out += sym;
      n -= v;
    }
  }
  return out;
}

function formatArticleText(article: LawArticle) {
  const no = article.articleNo || `第 ${article.articleOrder || article.id} 条`;
  const parts = [no];
  if (article.articleTitle) parts.push(article.articleTitle);
  if (article.contentText) parts.push(article.contentText);
  return parts.join('\n\n');
}

async function copyArticle(article: LawArticle) {
  const ok = await copyText(formatArticleText(article));
  if (ok) {
    toast.success('已复制条款内容。');
  } else {
    toast.error('复制失败，请手动选择文本。');
  }
}
</script>

<style scoped>
.reader {
  display: grid;
  gap: 6px;
}

.chapter-head {
  margin: 30px 0 6px;
  padding: 0 22px;
}

.reader > .chapter-head:first-child {
  margin-top: 4px;
}

.chapter-title {
  margin: 6px 0 0;
  font-family: var(--serif-display);
  font-size: 28px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.chapter-title .chapter-name {
  margin-left: 12px;
}

.section-head {
  margin: 16px 0 2px;
  padding: 0 22px;
  color: var(--ink-2);
  font-family: var(--serif-body);
  font-size: 17px;
  font-weight: 500;
}

.section-head .section-name {
  margin-left: 8px;
  color: var(--ink-3);
}

.article {
  padding: 18px 22px 20px;
  border-left: 2px solid transparent;
  cursor: pointer;
  transition: background 0.15s var(--ease), border-color 0.15s var(--ease);
}

.article.focused {
  border-left-color: var(--accent);
  background: var(--accent-soft);
}

.article-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}

.article-copy {
  flex-shrink: 0;
  margin-top: 2px;
}

.article-no {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  color: var(--accent);
  font-family: var(--serif-display);
  font-size: 23px;
  font-style: italic;
}

h3 {
  margin: 12px 0 0;
  font-family: var(--serif-body);
  font-size: 18px;
  font-weight: 500;
}

p {
  margin: 12px 0 0;
  color: var(--ink-2);
  font-family: var(--serif-body);
  font-size: 16px;
  line-height: 1.78;
  text-align: justify;
  text-justify: inter-ideograph;
}
</style>
