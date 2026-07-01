<template>
  <section class="page compare">
    <header>
      <div class="section-kicker">§ 版本对比</div>
      <h1 class="display h1--italic compare-title">{{ document?.title || '版本对比' }}</h1>
      <p>选择两个版本查看修订摘要；条款差异以编辑型对照呈现。</p>
    </header>

    <PageState v-if="error" :error="error" />
    <template v-if="loading">
      <SkeletonVersionTimeline />
      <SkeletonCompareSpread />
    </template>
    <template v-else>
      <VersionTimeline
        v-if="versions.length"
        :versions="versions"
        :active-version-id="targetId"
        @select="onTimelineSelect"
      />
      <EmptyState v-else title="暂无可对比版本" description="该法规当前没有两个以上可展示版本。" />

      <section class="spread">
        <div class="pane" :class="{ 'pane--active': baseId }">
          <div class="section-kicker">基准版本</div>
          <XSelect v-model="baseIdText" :options="versionOptions" />
          <p class="pane-note">{{ baseName || '选择旧版本或参照版本' }}</p>
        </div>
        <div class="gutter">
          <Diamond />
          <XChip tone="outline">逐条对比</XChip>
          <span v-if="baseId && targetId && baseId === targetId" class="mono compare-warning">请选择两个不同版本</span>
        </div>
        <div class="pane" :class="{ 'pane--active': targetId }">
          <div class="section-kicker">目标版本</div>
          <XSelect v-model="targetIdText" :options="versionOptions" />
          <p class="pane-note">{{ targetName || '选择新版本或当前版本' }}</p>
        </div>
      </section>

      <CompareSummaryStrip v-if="diff && !diffLoading" :stats="summaryStats" />

      <blockquote v-if="diff?.summary" class="compare-quote marginalia">
        {{ diff.summary }}
      </blockquote>

      <PageState v-if="diffError" :error="diffError" />
      <SkeletonCompareDiff v-if="diffLoading" />

      <template v-else-if="diff && visibleChanges.length">
        <CompareArticleTabs v-model="focusIdx" :tabs="visibleChanges" />
        <DiffSpread
          v-if="focusedChange"
          :base-text="focusedChange.baseText"
          :target-text="focusedChange.targetText"
          :change-type="focusedChange.changeType"
          :article-no="focusedChange.articleNo"
          :left-label="baseName"
          :right-label="targetName"
          :left-date="baseDate"
          :right-date="targetDate"
        />
        <section v-if="previewChanges.length" class="preview-grid">
          <button
            v-for="(change, idx) in previewChanges"
            :key="idx"
            type="button"
            class="preview-card"
            @click="focusIdx = visibleChanges.indexOf(change)"
          >
            <span class="mono">{{ change.articleNo || '条款' }}</span>
            <XChip :tone="chipTone(change.changeType)">{{ changeLabel(change.changeType) }}</XChip>
          </button>
        </section>
      </template>

      <EmptyState
        v-else-if="diff && !diffError"
        title="差异分析将于二期完善"
        description="当前版本对比已提供时间线与版本选择，逐条差异摘要会在后端分析能力就绪后补齐。"
      />

      <EmptyState
        v-else-if="!diffLoading && !diffError && versions.length"
        title="选择两个版本开始对比"
        description="在上方分别选择基准版本与目标版本，系统将逐条比对差异。"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import CompareArticleTabs from '@/components/compare/CompareArticleTabs.vue';
import CompareSummaryStrip from '@/components/compare/CompareSummaryStrip.vue';
import type { CompareStat } from '@/components/compare/CompareSummaryStrip.vue';
import DiffSpread from '@/components/compare/DiffSpread.vue';
import Diamond from '@/components/brand/Diamond.vue';
import VersionTimeline from '@/components/business/VersionTimeline.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import PageState from '@/components/common/PageState.vue';
import SkeletonCompareDiff from '@/components/common/SkeletonCompareDiff.vue';
import SkeletonCompareSpread from '@/components/common/SkeletonCompareSpread.vue';
import SkeletonVersionTimeline from '@/components/common/SkeletonVersionTimeline.vue';
import XChip from '@/components/common/XChip.vue';
import XSelect from '@/components/common/XSelect.vue';
import { compareLawVersions, getLawDocument, getLawVersions } from '@/api/law';
import type { ArticleChangeType, LawDocument, LawVersion, VersionDiffResult } from '@/types/law';
import { resolveApiError } from '@/utils/apiError';

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const error = ref('');
const document = ref<LawDocument | null>(null);
const versions = ref<LawVersion[]>([]);
const baseId = ref<number | undefined>();
const targetId = ref<number | undefined>();
const focusIdx = ref(0);

const versionOptions = computed(() =>
  versions.value.map((version) => ({
    label: versionOptionLabel(version),
    value: String(version.id)
  }))
);

const baseIdText = computed({
  get: () => (baseId.value ? String(baseId.value) : ''),
  set: (value: string) => {
    baseId.value = value ? Number(value) : undefined;
  }
});

const targetIdText = computed({
  get: () => (targetId.value ? String(targetId.value) : ''),
  set: (value: string) => {
    targetId.value = value ? Number(value) : undefined;
  }
});

const diff = ref<VersionDiffResult | null>(null);
const diffLoading = ref(false);
const diffError = ref('');

const visibleChanges = computed(() => (diff.value?.changes ?? []).filter((c) => c.changeType !== 'UNCHANGED'));

const focusedChange = computed(() => visibleChanges.value[focusIdx.value]);

const previewChanges = computed(() =>
  visibleChanges.value.filter((_, idx) => idx !== focusIdx.value).slice(0, 3)
);

watch(visibleChanges, (list) => {
  if (focusIdx.value >= list.length) focusIdx.value = 0;
});

const baseName = computed(() => versionName(baseId.value));
const targetName = computed(() => versionName(targetId.value));
const baseDate = computed(() => versionDateStr(baseId.value));
const targetDate = computed(() => versionDateStr(targetId.value));

const summaryStats = computed<CompareStat[]>(() => {
  const d = diff.value;
  const total = (d?.addedCount ?? 0) + (d?.modifiedCount ?? 0) + (d?.removedCount ?? 0) + (d?.unchangedCount ?? 0);
  return [
    { kicker: '新增', label: 'ADDED', value: d?.addedCount ?? 0, sub: '条款', color: 'var(--moss)' },
    { kicker: '修订', label: 'MODIFIED', value: d?.modifiedCount ?? 0, sub: '条款', color: 'var(--gold)' },
    { kicker: '删除', label: 'REMOVED', value: d?.removedCount ?? 0, sub: '条款', color: 'var(--rose)' },
    { kicker: '合计', label: 'TOTAL', value: total, sub: '对比范围' }
  ];
});

const CHANGE_LABELS: Record<ArticleChangeType, string> = {
  ADDED: '新增',
  REMOVED: '删除',
  MODIFIED: '修改',
  UNCHANGED: '未变'
};

function changeLabel(type: ArticleChangeType) {
  return CHANGE_LABELS[type];
}

function chipTone(type: ArticleChangeType): 'moss' | 'gold' | 'rose' | 'outline' {
  if (type === 'ADDED') return 'moss';
  if (type === 'MODIFIED') return 'gold';
  if (type === 'REMOVED') return 'rose';
  return 'outline';
}

function versionById(id?: number) {
  return versions.value.find((item) => item.id === id);
}

function versionDateStr(id?: number) {
  const v = versionById(id);
  if (!v) return '';
  if (v.effectiveDate) return `施行 ${v.effectiveDate}`;
  if (v.publishDate) return `公布 ${v.publishDate}`;
  return '';
}

function versionName(id?: number) {
  const v = versionById(id);
  if (!v) return '';
  return v.versionName || v.versionNo || (id ? `v${id}` : '');
}

function versionOptionLabel(version: LawVersion) {
  const date = version.effectiveDate
    ? `施行 ${version.effectiveDate}`
    : version.publishDate
      ? `公布 ${version.publishDate}`
      : '日期未标注';
  const name = version.versionName || version.versionNo || String(version.id);
  return `${date} · ${name}`;
}

function onTimelineSelect(versionId: number) {
  targetId.value = versionId;
  if (baseId.value === versionId) {
    const other = versions.value.find((v) => v.id !== versionId);
    if (other) baseId.value = other.id;
  }
}

async function loadDiff() {
  if (!baseId.value || !targetId.value || baseId.value === targetId.value) {
    diff.value = null;
    diffError.value = '';
    return;
  }
  diffLoading.value = true;
  diffError.value = '';
  try {
    diff.value = await compareLawVersions(baseId.value, targetId.value);
  } catch (err) {
    diff.value = null;
    diffError.value = resolveApiError(err, '版本对比读取失败。');
  } finally {
    diffLoading.value = false;
  }
}

function syncQuery() {
  router.replace({
    query: {
      ...route.query,
      base: baseId.value ? String(baseId.value) : undefined,
      target: targetId.value ? String(targetId.value) : undefined
    }
  });
}

let diffTimer: number | undefined;
watch([baseId, targetId], () => {
  syncQuery();
  if (diffTimer) window.clearTimeout(diffTimer);
  diffTimer = window.setTimeout(loadDiff, 250);
});

onBeforeUnmount(() => {
  if (diffTimer) window.clearTimeout(diffTimer);
});

function pickInitialVersion(raw: unknown, fallback?: number) {
  const id = Number(raw);
  if (Number.isFinite(id) && versions.value.some((v) => v.id === id)) return id;
  return fallback;
}

onMounted(async () => {
  loading.value = true;
  try {
    const documentId = String(route.params.documentId);
    const [doc, versionPage] = await Promise.all([getLawDocument(documentId), getLawVersions(documentId)]);
    document.value = doc;
    versions.value = versionPage.list;
    baseId.value = pickInitialVersion(route.query.base, versions.value[1]?.id || versions.value[0]?.id);
    targetId.value = pickInitialVersion(route.query.target, versions.value[0]?.id);
  } catch (err) {
    error.value = resolveApiError(err, '版本信息读取失败。');
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.compare {
  display: grid;
  gap: 28px;
}

header {
  display: grid;
  gap: 12px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--ink);
}

.compare-title {
  font-size: clamp(36px, 5vw, 52px);
}

header p {
  max-width: 70ch;
  margin: 0;
  color: var(--ink-3);
  font-family: var(--serif-body);
  font-size: 16px;
  line-height: 1.65;
}

.spread {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 140px minmax(0, 1fr);
  gap: 18px;
  align-items: stretch;
}

.pane {
  display: grid;
  gap: 16px;
  padding: 24px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper);
}

.pane--active {
  border-color: var(--rule-strong);
  background: var(--paper-2);
}

.pane-note {
  margin: 0;
  color: var(--ink-3);
  font-family: var(--serif-body);
  font-size: 13px;
  line-height: 1.5;
}

.gutter {
  display: grid;
  gap: 14px;
  place-items: center;
  align-content: center;
}

.compare-warning {
  color: var(--rose);
  font-size: var(--font-xs);
  text-align: center;
}

.compare-quote {
  margin: 0;
  max-width: 72ch;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.preview-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper);
  cursor: pointer;
  transition: border-color 0.15s var(--ease);
}

.preview-card:hover {
  border-color: var(--ink);
}

.diff-sk {
  display: grid;
  gap: 18px;
}

.diff-sk-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

@media (max-width: 860px) {
  .spread,
  .preview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
