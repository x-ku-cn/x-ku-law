<template>
  <section class="page page--reader detail">
    <PageState v-if="error && !loading" :error="error" />
    <template v-if="loading">
      <SkeletonLawMetaHead />
      <SkeletonVersionTimeline />
      <SkeletonLawReadingGrid />
    </template>
    <template v-else-if="document">
      <header class="meta-head">
        <div class="meta-main">
          <div class="section-kicker">§ Reading</div>
          <h1 class="h1">{{ document.title }}</h1>
          <p class="meta-sub">{{ document.summary || document.issuingOrg || '法规正文与版本沿革。' }}</p>
          <div v-if="metaChips.length" class="meta-tags">
            <XChip v-for="tag in metaChips" :key="tag" tone="outline">{{ tag }}</XChip>
          </div>
          <div v-if="documentTags.length" class="doc-tags">
            <span class="doc-tags-label mono">标签 · Tags</span>
            <div class="doc-tags-list">
              <XChip v-for="tag in documentTags" :key="tag" tone="accent">{{ tag }}</XChip>
            </div>
          </div>
        </div>

        <div class="meta-side">
          <div class="meta-actions">
            <XButton size="small" variant="primary" :disabled="!sourcePreviewAvailable" @click="sourcePreviewOpen = true">源文件预览</XButton>
            <XButton v-if="versions.length > 1" size="small" @click="router.push(lawCompareTo(document.id))">对比版本</XButton>
            <XButton v-if="auth.isAdmin && currentVersion" size="small" @click="startParseRepair">发起解析修复</XButton>
            <XButton
              size="small"
              :class="{ 'is-favorited': isFavorited }"
              :loading="favoriteLoading"
              @click="toggleFavorite"
            >
              {{ isFavorited ? '已收藏' : '收藏' }}
            </XButton>
            <XButton size="small" variant="accent" @click="goAskAi">问 AI</XButton>
          </div>
          <dl class="meta-stats">
            <div>
              <dt class="mono">{{ isDecisionLayout ? '段落' : '条文' }}</dt>
              <dd class="stat-num">{{ articles.length }}</dd>
            </div>
            <div>
              <dt class="mono">版本</dt>
              <dd class="stat-num">{{ versions.length }}</dd>
            </div>
            <div>
              <dt class="mono">引用</dt>
              <dd class="stat-num">{{ citationCount }}</dd>
            </div>
            <div>
              <dt class="mono">关联</dt>
              <dd class="stat-num">{{ relationCount }}</dd>
            </div>
          </dl>
          <div class="meta-box">
            <span class="mono">{{ document.documentNo || '暂无文号' }}</span>
            <StatusBadge :value="document.status" />
            <span class="mono">公布 {{ document.publishDate || '未标注' }}</span>
            <span class="mono">施行 {{ document.effectiveDate || '未标注' }}</span>
            <label v-if="versionOptions.length > 1" class="version-pick">
              <span class="mono">阅读版本</span>
              <XSelect :model-value="currentVersionValue" :options="versionOptions" placeholder="选择版本" @update:model-value="changeVersion" />
            </label>
            <XButton size="small" variant="ghost" @click="feedbackOpen = true">反馈纠错</XButton>
          </div>
        </div>
      </header>

      <VersionTimeline
        v-if="versions.length"
        :versions="versions"
        :active-version-id="currentVersionValue"
        @select="(id) => changeVersion(String(id))"
      />
      <EmptyState v-else title="暂无版本记录" description="该法规当前没有可展示的历史版本。" />

      <div class="reading-grid" :class="{ 'reading-grid--loading': articlesLoading, 'reading-grid--decision': isDecisionLayout }">
        <aside v-if="!isDecisionLayout" class="toc">
          <div class="section-kicker">§ 目录 · TOC</div>
          <template v-if="articlesLoading">
            <Skeleton v-for="n in 8" :key="n" width="90%" />
          </template>
          <template v-else>
            <template v-for="group in tocGroups" :key="group.key">
              <div v-if="group.chapterNo" class="toc-group-title">
                <span>{{ group.chapterHeading.no }}</span>
                <span v-if="group.chapterHeading.title" class="toc-chapter-name">{{ group.chapterHeading.title }}</span>
              </div>
              <a
                v-for="item in group.items"
                :key="item.article.id"
                :href="`#article-${item.article.id}`"
                class="toc-link"
                :class="{ active: item.article.id === activeArticleId }"
                @click.prevent="scrollToArticle(item.article.id)"
              >
                <Diamond :size="6" />
                <span class="toc-index mono">{{ item.index }}</span>
                <span class="toc-label">{{ item.article.articleNo || item.article.articleTitle || `条款 ${item.article.id}` }}</span>
                <span v-if="item.article.obligationFlag" class="toc-tag">义务</span>
                <span v-else-if="item.article.penaltyFlag" class="toc-tag tag-rose">责任</span>
              </a>
            </template>
          </template>
        </aside>

        <main class="reading">
          <template v-if="articlesLoading">
            <Skeleton v-for="n in 4" :key="n" width="100%" :lines="3" />
          </template>
          <template v-else>
            <EmptyState v-if="!articles.length" title="暂无条款正文" description="该法规当前没有可展示的条款正文。" />
            <DecisionReader
              v-if="isDecisionLayout"
              :file-id="currentVersion?.fileId"
              :source-url="currentVersion?.sourceUrl"
              :official-url="document?.officialUrl"
              :title="document?.title"
              :articles="articles"
              @select="selectArticle"
            />
            <ArticleReader
              v-else
              :articles="articles"
              :active-article-id="activeArticleId"
              @select="selectArticle"
            />
            <div v-if="articles.length" class="end-note">
              <Diamond />
              <span class="mono">END OF DOCUMENT</span>
            </div>
          </template>
        </main>

        <aside class="marginal">
          <div class="section-kicker">§ 边注 · Marginalia</div>

          <div class="marginal-ai-pin">
            <LawAiMarginalia
              :interpretation-text="interpretation?.interpretationText"
              @expand="interpretationOpen = true"
            />
          </div>

          <blockquote v-if="activeArticle" class="marginalia-quote">
            {{ editorialNote }}
          </blockquote>
        </aside>
      </div>
    </template>

    <XModal
      :open="feedbackOpen"
      kicker="§ Feedback"
      title="提交纠错"
      description="补充错误条文、失效状态或来源链接问题，系统会将反馈归档到工作台。"
      max-width="640px"
      @update:open="closeFeedbackModal"
    >
      <form id="feedback-form" class="feedback-form" @submit.prevent="submitFeedback">
        <XFormField label="反馈内容" required :error="feedbackError">
          <XTextarea v-model="feedbackContent" placeholder="例如：第二条的生效日期疑似错误，官方来源链接也需要更新。" />
        </XFormField>
      </form>
      <template #footer>
        <span class="mono feedback-target">当前对象：{{ document?.title || '' }}</span>
        <XButton type="button" variant="ghost" @click="closeFeedbackModal">取消</XButton>
        <XButton type="submit" variant="primary" form="feedback-form">{{ feedbackSubmitting ? '提交中…' : '提交反馈' }}</XButton>
      </template>
    </XModal>

    <XModal
      :open="sourcePreviewOpen"
      kicker="§ Source"
      title="源文件预览"
      :description="document?.title"
      max-width="min(96vw, 1400px)"
      @update:open="sourcePreviewOpen = $event"
    >
      <SourceFilePreview
        :open="sourcePreviewOpen"
        :file-id="currentVersion?.fileId"
        :source-url="currentVersion?.sourceUrl"
        :official-url="document?.officialUrl"
        :title="document?.title"
      />
    </XModal>

    <XModal
      :open="interpretationOpen"
      max-width="760px"
      @update:open="interpretationOpen = $event"
    >
      <template #header>
        <div class="interpretation-head-main">
          <div class="section-kicker">§ AI 解读</div>
          <h2 class="interpretation-title">整篇法规解读</h2>
          <div v-if="document?.title || interpretation?.interpretationText" class="interpretation-sub">
            <p v-if="document?.title" class="interpretation-desc">{{ document.title }}</p>
            <div v-if="interpretation?.interpretationText" class="interpretation-actions">
              <XButton size="small" variant="primary" :loading="pdfDownloading" @click="downloadInterpretation">下载 PDF</XButton>
              <XButton size="small" @click="copyInterpretation">复制</XButton>
              <XButton size="small" variant="accent">追问</XButton>
            </div>
          </div>
        </div>
      </template>
      <MarkdownContent v-if="interpretation?.interpretationText" :source="interpretation.interpretationText" />
      <EmptyState v-else title="暂无解读正文" description="该版本尚未生成 AI 解读。" />
    </XModal>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ArticleReader from '@/components/business/ArticleReader.vue';
import DecisionReader from '@/components/business/DecisionReader.vue';
import LawAiMarginalia from '@/components/business/LawAiMarginalia.vue';
import SourceFilePreview from '@/components/business/SourceFilePreview.vue';
import VersionTimeline from '@/components/business/VersionTimeline.vue';
import Diamond from '@/components/brand/Diamond.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import MarkdownContent from '@/components/common/MarkdownContent.vue';
import XChip from '@/components/common/XChip.vue';
import XFormField from '@/components/common/XFormField.vue';
import XModal from '@/components/common/XModal.vue';
import PageState from '@/components/common/PageState.vue';
import Skeleton from '@/components/common/Skeleton.vue';
import SkeletonLawMetaHead from '@/components/common/SkeletonLawMetaHead.vue';
import SkeletonLawReadingGrid from '@/components/common/SkeletonLawReadingGrid.vue';
import SkeletonVersionTimeline from '@/components/common/SkeletonVersionTimeline.vue';
import StatusBadge from '@/components/common/StatusBadge.vue';
import XButton from '@/components/common/XButton.vue';
import XSelect from '@/components/common/XSelect.vue';
import XTextarea from '@/components/common/XTextarea.vue';
import { createParseRepairIssue } from '@/api/ops';
import { createFavorite, createFeedback, deleteFavorite, getFavorites } from '@/api/workspace';
import { lawCompareTo } from '@/router/navigation';
import { useLawDetail } from '@/composables/useLawDetail';
import { useToast } from '@/composables/useToast';
import { useAuthStore } from '@/stores/auth';
import type { LawVersion } from '@/types/law';
import type { OptionItem } from '@/types/api';
import { resolveApiError } from '@/utils/apiError';
import { copyText } from '@/utils/clipboard';
import { downloadInterpretationPdf } from '@/utils/interpretationExport';
import { labelOf } from '@/utils/labels';
import { splitChapterHeading } from '@/utils/lawStructure';

const route = useRoute();
const router = useRouter();
const toast = useToast();
const auth = useAuthStore();

const documentId = computed(() => String(route.params.documentId || ''));
const selectedVersionId = computed(() => String(route.query.v || ''));

const { loading, articlesLoading, error, document, articles, versions, relations, interpretation } = useLawDetail(
  documentId,
  selectedVersionId
);

const activeArticleId = ref<number | null>(null);
let articleObserver: IntersectionObserver | undefined;

const citationCount = computed(
  () => relations.value.filter((r) => r.relationType === 'cite').length
);
const relationCount = computed(() => relations.value.length);

const activeArticle = computed(() => articles.value.find((a) => a.id === activeArticleId.value) ?? null);

const isDecisionLayout = computed(() => {
  const d = document.value;
  if (!d) return false;
  const lawType = d.lawType || '';
  const title = d.title || '';
  return lawType.includes('决定')
    || lawType.includes('修正案')
    || title.endsWith('的决定')
    || title.includes('关于修改')
    || title.includes('关于废止');
});

/** 目录按章分组：同 chapterNo 归一组，条款带跨全文连续的两位流水号。无章节则退化为单个「无标题」组。 */
const tocGroups = computed(() => {
  const groups: {
    key: string;
    chapterNo?: string;
    chapterHeading: { no: string; title?: string };
    items: { article: (typeof articles.value)[number]; index: string }[];
  }[] = [];
  let running = 0;
  for (const article of articles.value) {
    running += 1;
    const index = String(running).padStart(2, '0');
    const key = article.chapterNo || '__flat__';
    let group = groups[groups.length - 1];
    if (!group || group.key !== key) {
      group = {
        key,
        chapterNo: article.chapterNo,
        chapterHeading: splitChapterHeading(article.chapterNo, article.chapterTitle),
        items: []
      };
      groups.push(group);
    }
    group.items.push({ article, index });
  }
  return groups;
});

const editorialNote = computed(() => {
  const a = activeArticle.value;
  if (!a) return '';
  if (isDecisionLayout.value) return '该段属于决定文本，请结合被修改法规的现行条文一并理解。';
  if (a.obligationFlag) return '本条为核心义务条款。';
  if (a.penaltyFlag) return '本条涉及法律责任或处罚，请结合上下位法一并理解。';
  return '本条为定义或适用范围类条款，通常不直接产生具体义务。';
});

const metaChips = computed(() => {
  const d = document.value;
  if (!d) return [] as string[];
  return [
    labelOf(d.lawType),
    labelOf(d.legalLevel),
    labelOf(d.status),
    d.issuingOrg
  ].filter((t) => t && t !== '—');
});

const documentTags = computed(() => {
  const names = document.value?.tags ?? [];
  return [...new Set(names.map((t) => t.trim()).filter(Boolean))];
});

watch(
  () => articles.value,
  (list) => {
    if (!list.length || !list.some((a) => a.id === activeArticleId.value)) {
      activeArticleId.value = null;
    }
    setupArticleObserver();
  }
);

function setupArticleObserver() {
  articleObserver?.disconnect();
  if (!articles.value.length) return;
  articleObserver = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((e) => e.isIntersecting)
        .sort((a, b) => (b.intersectionRatio || 0) - (a.intersectionRatio || 0))[0];
      if (!visible?.target?.id) return;
      const id = Number(visible.target.id.replace('article-', ''));
      if (Number.isFinite(id)) activeArticleId.value = id;
    },
    { root: null, rootMargin: '-20% 0px -55% 0px', threshold: [0, 0.25, 0.5, 1] }
  );
  articles.value.forEach((article) => {
    const el = globalThis.document.getElementById(`article-${article.id}`);
    if (el) articleObserver?.observe(el);
  });
}

onMounted(() => setupArticleObserver());
onBeforeUnmount(() => articleObserver?.disconnect());

const versionOptions = computed<OptionItem[]>(() =>
  versions.value.map((v: LawVersion) => ({
    value: v.id,
    label: v.versionName || v.versionNo || v.effectiveDate || `版本 #${v.id}`
  }))
);

const currentVersionValue = computed(() =>
  selectedVersionId.value || String(document.value?.currentVersionId || versions.value[0]?.id || '')
);

const currentVersion = computed(() =>
  versions.value.find((v) => String(v.id) === String(currentVersionValue.value)) ?? null
);

const sourcePreviewAvailable = computed(() =>
  Boolean(currentVersion.value?.fileId || currentVersion.value?.sourceUrl || document.value?.officialUrl)
);

function changeVersion(value: string) {
  router.replace({ query: { ...route.query, v: value || undefined } });
}

function goAskAi() {
  const doc = document.value;
  if (!doc) return;
  router.push({
    name: 'ai.chat',
    query: { documentId: String(doc.id), documentTitle: doc.title }
  });
}

async function startParseRepair() {
  const version = currentVersion.value;
  if (!version) return;
  try {
    const issue = await createParseRepairIssue({
      bizType: 'law_version',
      bizId: version.id,
      parserType: isDecisionLayout.value ? 'decision_text' : 'law_article',
      layoutType: isDecisionLayout.value ? 'decision_reader' : 'article_reader',
      source: 'manual',
      reason: '管理员在阅读时发现解析结构需要人工复核'
    });
    router.push({
      name: 'admin.ops.parseRepairEditor',
      params: { bizType: issue.bizType, bizId: issue.bizId },
      query: { repairIssueId: issue.id, parserType: issue.parserType, layoutType: issue.layoutType }
    });
  } catch (err) {
    toast.error(resolveApiError(err, '发起解析修复失败'));
  }
}

function selectArticle(id: number) {
  activeArticleId.value = id;
}

function scrollToArticle(id: number) {
  selectArticle(id);
  globalThis.document.getElementById(`article-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

const sourcePreviewOpen = ref(false);
const feedbackOpen = ref(false);
const interpretationOpen = ref(false);
const feedbackSubmitting = ref(false);
const feedbackContent = ref('');
const feedbackError = ref('');
const favoriteId = ref<number | null>(null);
const favoriteLoading = ref(false);
const pdfDownloading = ref(false);

const isFavorited = computed(() => favoriteId.value != null);

async function loadFavoriteStatus() {
  const doc = document.value;
  if (!doc) return;
  try {
    const result = await getFavorites({ refType: 'law_document', refId: doc.id, pageNo: 1, pageSize: 1 });
    favoriteId.value = result.list[0]?.id ?? null;
  } catch {
    favoriteId.value = null;
  }
}

watch(
  () => document.value?.id,
  (id) => {
    favoriteId.value = null;
    if (id) void loadFavoriteStatus();
  }
);

async function toggleFavorite() {
  if (!document.value || favoriteLoading.value) return;
  favoriteLoading.value = true;
  try {
    if (favoriteId.value) {
      await deleteFavorite(favoriteId.value);
      favoriteId.value = null;
      toast.success('已取消收藏。');
    } else {
      const id = await createFavorite({
        refType: 'law_document',
        refId: document.value.id,
        titleSnapshot: document.value.title,
        folderName: '默认'
      });
      favoriteId.value = id;
      toast.success('已加入收藏。');
    }
  } catch (err) {
    toast.error(resolveApiError(err, favoriteId.value ? '取消收藏失败。' : '收藏失败。'));
  } finally {
    favoriteLoading.value = false;
  }
}

async function downloadInterpretation() {
  const text = interpretation.value?.interpretationText?.trim();
  const title = document.value?.title?.trim();
  if (!text || !title || pdfDownloading.value) return;
  pdfDownloading.value = true;
  try {
    const ok = await downloadInterpretationPdf(title, text);
    if (ok) {
      toast.success('PDF 已开始下载。');
    } else {
      toast.error('PDF 生成失败，请稍后重试。');
    }
  } finally {
    pdfDownloading.value = false;
  }
}

async function copyInterpretation() {
  const text = interpretation.value?.interpretationText?.trim();
  if (!text) return;
  const ok = await copyText(text);
  if (ok) {
    toast.success('已复制解读内容。');
  } else {
    toast.error('复制失败，请手动选择文本。');
  }
}

function closeFeedbackModal() {
  feedbackOpen.value = false;
  feedbackContent.value = '';
  feedbackError.value = '';
}

async function submitFeedback() {
  if (!document.value) return;
  if (!feedbackContent.value.trim()) {
    feedbackError.value = '请先填写反馈内容。';
    return;
  }
  feedbackSubmitting.value = true;
  feedbackError.value = '';
  try {
    await createFeedback({
      feedbackType: 'correction',
      refType: 'law_document',
      refId: document.value.id,
      content: feedbackContent.value.trim()
    });
    closeFeedbackModal();
    toast.success('反馈已提交。');
  } catch (err) {
    feedbackError.value = resolveApiError(err, '反馈提交失败。');
  } finally {
    feedbackSubmitting.value = false;
  }
}

</script>

<style scoped>
.detail {
  display: grid;
  gap: 0;
}

.meta-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--gutter);
  align-items: start;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--ink);
}

.meta-sub {
  max-width: 76ch;
  margin: 12px 0 0;
  color: var(--ink-3);
  font-family: var(--serif-body);
  font-size: 16px;
  line-height: 1.7;
}

.meta-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.doc-tags {
  display: grid;
  gap: 8px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--rule);
}

.doc-tags-label {
  color: var(--muted);
  font-size: var(--font-xxs);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.doc-tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.meta-side {
  display: grid;
  gap: 14px;
  justify-items: end;
}

.meta-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.meta-actions :deep(.x-button.is-favorited) {
  border-color: var(--gold);
  background: var(--gold-soft);
  color: var(--gold);
}

.meta-actions :deep(.x-button.is-favorited:hover) {
  border-color: var(--gold);
  background: rgba(168, 132, 59, 0.16);
}

.meta-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(52px, 1fr));
  gap: 10px;
  margin: 0;
}

.meta-stats div {
  text-align: center;
}

.meta-stats dt {
  margin: 0;
  color: var(--muted);
  font-size: var(--font-xxs);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.stat-num {
  margin: 4px 0 0;
  font-family: var(--serif-display);
  font-size: 28px;
  font-style: italic;
  font-weight: 400;
  line-height: 1;
  color: var(--ink);
}

.meta-box {
  display: grid;
  gap: 10px;
  justify-items: start;
  min-width: 220px;
  padding: 16px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper-2);
}

.version-pick {
  display: grid;
  gap: 6px;
  width: 100%;
  color: var(--muted);
  font-size: var(--font-xs);
}

.reading-grid {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr) 280px;
  gap: var(--gutter);
  align-items: start;
  padding-top: 28px;
}

.reading-grid--decision {
  grid-template-columns: minmax(0, 1fr) 280px;
}

.toc,
.marginal {
  position: sticky;
  top: 18px;
  display: grid;
  gap: 14px;
  align-content: start;
  max-height: calc(100vh - var(--topbar-h) - 48px);
  overflow: auto;
}

.toc {
  /* Rotated diamonds extend past their box; padding keeps them inside the scroll clip. */
  padding-inline: 8px 4px;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.toc:hover {
  scrollbar-color: var(--rule) transparent;
}

.toc::-webkit-scrollbar {
  width: 4px;
}

.toc::-webkit-scrollbar-track {
  background: transparent;
}

.toc::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 4px;
}

.toc:hover::-webkit-scrollbar-thumb {
  background: var(--rule);
}

.toc:hover::-webkit-scrollbar-thumb:hover {
  background: var(--muted-2);
}

.toc-link {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--rule);
  color: var(--ink-3);
  font-size: 13px;
  transition: color 0.14s var(--ease);
}

.toc-link.active {
  color: var(--accent-deep);
  font-weight: 600;
}

.toc-link.active :deep(.diamond) {
  background: var(--accent);
}

.toc-group-title {
  margin-top: 16px;
  padding: 6px 0 4px;
  color: var(--ink-1);
  font-family: var(--serif-body);
  font-size: 13px;
  font-weight: 600;
}

.toc-group-title:first-child {
  margin-top: 0;
}

.toc-chapter-name {
  margin-left: 0.45em;
}

.toc-index {
  flex-shrink: 0;
  color: var(--muted-2);
  font-size: 11px;
}

.toc-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toc-tag {
  flex-shrink: 0;
  padding: 0 5px;
  border: 1px solid var(--rule);
  border-radius: 3px;
  color: var(--accent-deep);
  font-size: 10px;
  line-height: 1.5;
}

.toc-tag.tag-rose {
  color: var(--rose, #b4436c);
}

.reading {
  min-width: 0;
  padding: 0 8px;
}

.marginal-ai-pin {
  position: sticky;
  top: 0;
  z-index: 1;
  padding-bottom: 4px;
  background: var(--paper);
}

.marginalia-quote {
  margin: 0;
  padding-left: 12px;
  border-left: 2px solid var(--accent);
  color: var(--ink-3);
  font-family: var(--serif-body);
  font-size: 13px;
  font-style: italic;
  line-height: 1.55;
}

.feedback-form {
  display: grid;
  gap: 18px;
}

.feedback-target {
  margin-right: auto;
  color: var(--muted);
  font-size: var(--font-xs);
}

.interpretation-head-main {
  width: 100%;
  min-width: 0;
}

.interpretation-title {
  margin: 0;
  font-family: var(--serif-display);
  font-size: clamp(26px, 4vw, 36px);
  font-weight: 400;
  font-style: italic;
  line-height: 1.05;
}

.interpretation-desc {
  min-width: 0;
  margin: 0;
  color: var(--ink-3);
  font-family: var(--serif-body);
  font-size: 15px;
  line-height: 1.65;
}

.interpretation-sub {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
  width: calc(100% + var(--control-h) + 16px);
  margin-top: 10px;
}

.interpretation-actions {
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.end-note {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: center;
  padding: 32px 0 0;
  color: var(--muted);
}

@media (max-width: 1120px) {
  .meta-head,
  .reading-grid {
    grid-template-columns: 1fr;
  }

  .meta-side {
    justify-items: start;
  }

  .meta-actions {
    justify-content: flex-start;
  }

  .interpretation-sub {
    grid-template-columns: 1fr;
    width: 100%;
  }

  .interpretation-actions {
    justify-content: flex-end;
  }

  .toc,
  .marginal {
    position: static;
    max-height: none;
  }
}
</style>
