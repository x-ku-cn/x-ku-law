<template>
  <div class="block-editor">
    <article v-for="(block, idx) in modelValue" :key="idx" class="block-card">
      <div class="block-head">
        <div>
          <div class="section-kicker">§ Block {{ idx + 1 }}</div>
          <strong>{{ blockTypeLabel(block.blockType) }}</strong>
        </div>
        <div class="block-actions">
          <XButton size="small" :disabled="idx === 0" @click="move(idx, -1)">上移</XButton>
          <XButton size="small" :disabled="idx === modelValue.length - 1" @click="move(idx, 1)">下移</XButton>
          <XButton size="small" variant="ghost" @click="remove(idx)">删除</XButton>
        </div>
      </div>

      <div class="block-grid">
        <label>
          <span class="mono">类型</span>
          <XSelect :model-value="block.blockType || defaultBlockType" :options="blockTypeOptions" @update:model-value="patch(idx, { blockType: $event })" />
        </label>
        <label>
          <span class="mono">编号</span>
          <XInput :model-value="block.blockNo || ''" placeholder="如 第一条" @update:model-value="patch(idx, { blockNo: $event })" />
        </label>
        <label>
          <span class="mono">标题</span>
          <XInput :model-value="block.blockTitle || ''" placeholder="可选标题" @update:model-value="patch(idx, { blockTitle: $event })" />
        </label>
        <label>
          <span class="mono">排序</span>
          <XInput :model-value="String(block.blockOrder || idx + 1)" type="number" @update:model-value="patch(idx, { blockOrder: Number($event) || idx + 1 })" />
        </label>
      </div>

      <div v-if="parserType === 'law_article'" class="block-grid block-grid--structure">
        <label>
          <span class="mono">章号</span>
          <XInput :model-value="block.chapterNo || ''" @update:model-value="patch(idx, { chapterNo: $event })" />
        </label>
        <label>
          <span class="mono">章标题</span>
          <XInput :model-value="block.chapterTitle || ''" @update:model-value="patch(idx, { chapterTitle: $event })" />
        </label>
        <label>
          <span class="mono">节号</span>
          <XInput :model-value="block.sectionNo || ''" @update:model-value="patch(idx, { sectionNo: $event })" />
        </label>
        <label>
          <span class="mono">节标题</span>
          <XInput :model-value="block.sectionTitle || ''" @update:model-value="patch(idx, { sectionTitle: $event })" />
        </label>
      </div>

      <label class="content-field">
        <span class="mono">正文</span>
        <XTextarea :model-value="block.contentText" rows="5" @update:model-value="patch(idx, { contentText: $event })" />
      </label>
    </article>

    <button type="button" class="add-card" @click="addBlock">新增结构块</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import XButton from '@/components/common/XButton.vue';
import XInput from '@/components/common/XInput.vue';
import XSelect from '@/components/common/XSelect.vue';
import XTextarea from '@/components/common/XTextarea.vue';
import type { ParsedBlockDraft } from '@/api/ops';

const props = defineProps<{
  modelValue: ParsedBlockDraft[];
  parserType: string;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: ParsedBlockDraft[]];
}>();

const articleTypes = [
  { label: '条款', value: 'article' }
];
const decisionTypes = [
  { label: '说明段', value: 'preamble' },
  { label: '修改项', value: 'amendment_item' },
  { label: '施行条款', value: 'effective_clause' },
  { label: '正文段落', value: 'body_paragraph' }
];
const genericTypes = [
  { label: '章节', value: 'section' },
  { label: '段落', value: 'paragraph' }
];

const defaultBlockType = computed(() => (props.parserType === 'decision_text' ? 'body_paragraph' : 'article'));

const blockTypeOptions = computed(() =>
  props.parserType === 'decision_text'
    ? decisionTypes
    : props.parserType === 'generic_section'
      ? genericTypes
      : articleTypes
);

function patch(idx: number, patchValue: Partial<ParsedBlockDraft>) {
  const next = props.modelValue.map((item, i) => (i === idx ? { ...item, ...patchValue } : item));
  emit('update:modelValue', normalizeOrder(next));
}

function move(idx: number, delta: number) {
  const next = [...props.modelValue];
  const target = idx + delta;
  const [item] = next.splice(idx, 1);
  next.splice(target, 0, item);
  emit('update:modelValue', normalizeOrder(next));
}

function remove(idx: number) {
  emit('update:modelValue', normalizeOrder(props.modelValue.filter((_, i) => i !== idx)));
}

function addBlock() {
  emit('update:modelValue', [
    ...props.modelValue,
    {
      blockType: defaultBlockType.value,
      blockOrder: props.modelValue.length + 1,
      blockLevel: 1,
      contentText: ''
    }
  ]);
}

function normalizeOrder(list: ParsedBlockDraft[]) {
  return list.map((item, idx) => ({ ...item, blockOrder: idx + 1, blockLevel: item.blockLevel || 1 }));
}

function blockTypeLabel(type?: string) {
  return [...articleTypes, ...decisionTypes, ...genericTypes].find((item) => item.value === type)?.label || '结构块';
}
</script>

<style scoped>
.block-editor {
  display: grid;
  gap: 12px;
}

.block-card,
.add-card {
  border: 1px solid var(--rule);
  background: var(--paper);
}

.block-card {
  display: grid;
  gap: 12px;
  padding: 14px;
}

.block-head {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.block-actions {
  display: flex;
  flex-shrink: 0;
  gap: 6px;
  align-items: center;
}

.block-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  align-items: end;
}

.block-grid label,
.content-field {
  display: grid;
  gap: 6px;
}

.content-field {
  min-width: 0;
}

.add-card {
  padding: 14px;
  color: var(--ink-2);
  cursor: pointer;
}

.add-card:hover {
  background: var(--accent-soft);
}

@media (max-width: 1100px) {
  .block-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
