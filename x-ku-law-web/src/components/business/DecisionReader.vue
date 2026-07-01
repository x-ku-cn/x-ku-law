<template>
  <article class="decision-reader" @click="emit('select', 0)">
    <SourceFilePreview
      v-if="hasSourceFile"
      :open="true"
      :file-id="fileId"
      :source-url="sourceUrl"
      :official-url="officialUrl"
      :title="title"
      embedded
      :show-toolbar="false"
    />
    <div v-else class="decision-prose">
      <div class="section-kicker">§ Decision Text</div>
      <p v-for="(paragraph, idx) in paragraphs" :key="idx">{{ paragraph }}</p>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import SourceFilePreview from '@/components/business/SourceFilePreview.vue';
import type { LawArticle } from '@/types/law';
import type { ParsedBlockDraft } from '@/api/ops';

type DecisionBlock = ParsedBlockDraft & { id?: number };

const props = defineProps<{
  sourceText?: string;
  fileId?: number | string | null;
  sourceUrl?: string;
  officialUrl?: string;
  title?: string;
  blocks?: DecisionBlock[];
  articles?: LawArticle[];
}>();

const emit = defineEmits<{
  select: [id: number];
}>();

const hasSourceFile = computed(() => Boolean(props.fileId || props.sourceUrl || props.officialUrl));

const fullText = computed(() => {
  if (props.sourceText?.trim()) return props.sourceText;
  if (props.blocks?.length) return props.blocks.map((block) => block.contentText).filter(Boolean).join('\n\n');
  return (props.articles || []).map((article) => article.contentText).filter(Boolean).join('\n\n');
});

const paragraphs = computed(() => {
  const lines = fullText.value
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  return lines.length ? lines : ['暂无决定原文。'];
});
</script>

<style scoped>
.decision-reader {
  padding: 8px 4px 32px;
  cursor: pointer;
}

.decision-prose {
  margin-top: 18px;
  color: var(--ink-2);
  font-family: var(--serif-body);
  font-size: 18px;
  line-height: 2.15;
  letter-spacing: 0.01em;
  text-align: justify;
  text-justify: inter-ideograph;
}

.decision-prose p {
  margin: 0 0 1.1em;
  text-indent: 2em;
}

.decision-prose p:first-child {
  text-indent: 0;
}
</style>
