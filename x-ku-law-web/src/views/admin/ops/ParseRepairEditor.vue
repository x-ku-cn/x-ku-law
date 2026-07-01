<template>
  <section class="page ops-page repair-editor">
    <header class="editor-head">
      <div>
        <div class="section-kicker">§ Parse Repair</div>
        <h1>{{ detail?.bizTitle || '内容解析修复' }}</h1>
        <p>左侧保留原文，右侧维护结构块。普通法规按条款编辑；决定类文档使用独立段落/修改项版式。</p>
      </div>
      <div class="head-actions">
        <XButton @click="router.back()">返回</XButton>
        <XButton variant="primary" :loading="saving" @click="save">保存修复</XButton>
      </div>
    </header>

    <PageState v-if="error" :error="error" />
    <template v-else-if="detail">
      <section class="meta-strip">
        <XChip tone="outline">{{ bizLabel(detail.bizType) }}</XChip>
        <XChip tone="accent">{{ parserLabel(parserType) }}</XChip>
        <XChip tone="outline">{{ layoutLabel(detail.layoutType) }}</XChip>
        <span class="mono">对象 ID {{ detail.bizId }}</span>
      </section>

      <section class="editor-toolbar">
        <div class="toolbar-field">
          <span class="mono toolbar-label">解析类型</span>
          <XSelect v-model="parserType" :options="parserTypeOptions" @update:model-value="previewAll" />
        </div>
        <div class="toolbar-actions">
          <XButton size="small" :loading="previewing" @click="previewAll">重新预览</XButton>
          <XButton size="small" :disabled="!selectedText" @click="appendSelection">选择文本生成块</XButton>
        </div>
        <span class="mono selection-hint">{{ selectedText ? `已选 ${selectedText.length} 字` : '可在原文中选择一段文本' }}</span>
      </section>

      <section class="editor-grid">
        <aside class="source-pane">
          <div class="pane-head">
            <span class="section-kicker">§ Source Text</span>
            <span class="mono">{{ sourceText.length }} 字</span>
          </div>
          <textarea
            ref="sourceRef"
            :value="sourceText"
            class="source-text"
            spellcheck="false"
            readonly
            tabindex="0"
            @select="captureSelection"
            @keyup="captureSelection"
            @mouseup="captureSelection"
          />
        </aside>

        <main class="blocks-pane">
          <div class="pane-head">
            <span class="section-kicker">§ Structured Blocks</span>
            <span class="mono">{{ blocks.length }} 块</span>
          </div>
          <ParsedBlockEditor v-model="blocks" :parser-type="parserType" />
        </main>
      </section>
    </template>
    <div v-else class="loading">正在加载修复对象…</div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import ParsedBlockEditor from '@/components/business/ParsedBlockEditor.vue';
import PageState from '@/components/common/PageState.vue';
import XButton from '@/components/common/XButton.vue';
import XChip from '@/components/common/XChip.vue';
import XSelect from '@/components/common/XSelect.vue';
import {
  getParseRepairTarget,
  previewParseRepair,
  saveParseRepairBlocks,
  type ParsedBlockDraft,
  type ParseRepairDetail
} from '@/api/ops';
import { useToast } from '@/composables/useToast';
import { resolveApiError } from '@/utils/apiError';

const route = useRoute();
const router = useRouter();
const toast = useToast();
const detail = ref<ParseRepairDetail | null>(null);
const blocks = ref<ParsedBlockDraft[]>([]);
const sourceText = ref('');
const parserType = ref('law_article');
const error = ref('');
const saving = ref(false);
const previewing = ref(false);
const selectedText = ref('');
const sourceRef = ref<HTMLTextAreaElement | null>(null);

const parserTypeOptions = [
  { label: '法规条款', value: 'law_article' },
  { label: '决定文本', value: 'decision_text' },
  { label: '通用章节', value: 'generic_section' }
];

onMounted(loadDetail);

async function loadDetail() {
  error.value = '';
  try {
    const bizType = String(route.params.bizType || 'law_version');
    const bizId = String(route.params.bizId || '');
    const data = await getParseRepairTarget(bizType, bizId);
    detail.value = data;
    sourceText.value = data.contentText || '';
    parserType.value = String(route.query.parserType || data.parserType || 'law_article');
    blocks.value = data.blocks?.length ? data.blocks : await previewParseRepair({ bizType, parserType: parserType.value, text: sourceText.value });
  } catch (err) {
    error.value = resolveApiError(err, '解析修复对象加载失败');
  }
}

function captureSelection() {
  const el = sourceRef.value;
  if (!el) return;
  selectedText.value = el.value.slice(el.selectionStart, el.selectionEnd).trim();
}

function appendSelection() {
  if (!selectedText.value) return;
  blocks.value = [
    ...blocks.value,
    {
      blockType: parserType.value === 'decision_text' ? 'body_paragraph' : 'article',
      blockOrder: blocks.value.length + 1,
      blockLevel: 1,
      contentText: selectedText.value
    }
  ];
  selectedText.value = '';
}

async function previewAll() {
  if (!detail.value) return;
  previewing.value = true;
  try {
    blocks.value = await previewParseRepair({
      bizType: detail.value.bizType,
      parserType: parserType.value,
      text: sourceText.value
    });
    toast.success('已根据当前原文生成预览。');
  } catch (err) {
    toast.error(resolveApiError(err, '解析预览失败'));
  } finally {
    previewing.value = false;
  }
}

async function save() {
  if (!detail.value) return;
  const emptyIdx = blocks.value.findIndex((block) => !block.contentText?.trim());
  if (emptyIdx >= 0) {
    toast.error(`第 ${emptyIdx + 1} 个结构块正文为空。`);
    return;
  }
  saving.value = true;
  try {
    await saveParseRepairBlocks(detail.value.bizType, detail.value.bizId, {
      repairIssueId: Number(route.query.repairIssueId || detail.value.repairIssueId || undefined) || undefined,
      qualityIssueId: Number(route.query.qualityIssueId || detail.value.qualityIssueId || undefined) || undefined,
      parserType: parserType.value,
      layoutType: parserType.value === 'decision_text' ? 'decision_reader' : 'article_reader',
      contentText: sourceText.value,
      triggerSync: true,
      blocks: blocks.value
    });
    toast.success('解析修复已保存。');
    router.push({ name: 'admin.ops.parseRepair' });
  } catch (err) {
    toast.error(resolveApiError(err, '保存解析修复失败'));
  } finally {
    saving.value = false;
  }
}

function bizLabel(type?: string) {
  if (type === 'law_version') return '法规版本';
  return type || '内容';
}

function parserLabel(type?: string) {
  if (type === 'decision_text') return '决定文本';
  if (type === 'generic_section') return '通用章节';
  return '法规条款';
}

function layoutLabel(type?: string) {
  if (type === 'decision_reader') return '决定版式';
  if (type === 'generic_reader') return '通用版式';
  return '条文版式';
}
</script>

<style scoped>
.repair-editor {
  display: grid;
  gap: 16px;
  --repair-toolbar-sticky-h: 44px;
}

.editor-head {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--rule);
}

.editor-head h1 {
  margin: 6px 0 4px;
  font-family: var(--serif-display);
  font-size: 28px;
  font-weight: 400;
}

.editor-head p {
  margin: 0;
  max-width: 78ch;
  color: var(--ink-3);
  font-size: 13px;
}

.head-actions,
.meta-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.editor-toolbar {
  position: sticky;
  top: 0;
  z-index: 4;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  padding: 8px 0 10px;
  background: var(--paper);
  border-bottom: 1px solid var(--rule);
}

.toolbar-field,
.toolbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.toolbar-field :deep(.x-select) {
  min-width: 160px;
}

.toolbar-field :deep(.x-select__trigger) {
  height: var(--control-h-sm);
  font-size: var(--font-xs);
}

.toolbar-label {
  color: var(--ink-3);
  white-space: nowrap;
}

.selection-hint {
  margin-left: auto;
  color: var(--ink-3);
  white-space: nowrap;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(320px, 0.9fr) minmax(420px, 1.1fr);
  gap: 16px;
  align-items: start;
}

.source-pane {
  position: sticky;
  top: calc(var(--repair-toolbar-sticky-h) + 12px);
  align-self: start;
  display: grid;
  grid-template-rows: auto 1fr;
  gap: 10px;
  min-width: 0;
  height: calc(100dvh - var(--topbar-h) - var(--repair-toolbar-sticky-h) - 44px);
  max-height: calc(100dvh - var(--topbar-h) - var(--repair-toolbar-sticky-h) - 44px);
}

.blocks-pane {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.pane-head {
  display: flex;
  justify-content: space-between;
  color: var(--ink-3);
}

.source-text {
  min-height: 0;
  padding: 18px;
  border: 1px solid var(--rule);
  background: var(--paper-2);
  color: var(--ink);
  font-family: var(--serif-body);
  font-size: 15px;
  line-height: 1.9;
  resize: none;
  overflow-y: auto;
  outline: 0;
  cursor: text;
}

.source-text:focus {
  border-color: var(--rule);
}

.loading {
  color: var(--ink-3);
}

@media (max-width: 1180px) {
  .editor-grid {
    grid-template-columns: 1fr;
  }

  .selection-hint {
    margin-left: 0;
    width: 100%;
  }

  .source-pane {
    position: static;
    height: auto;
    max-height: none;
  }

  .source-text {
    height: min(52dvh, 480px);
  }
}
</style>
