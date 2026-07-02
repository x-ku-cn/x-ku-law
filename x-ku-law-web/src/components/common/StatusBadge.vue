<template>
  <XChip v-if="visible" :tone="tone">{{ text }}</XChip>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { labelOf } from '@/utils/labels';
import XChip from './XChip.vue';

const props = defineProps<{ value?: string }>();

const raw = computed(() => props.value?.trim() || '');
const text = computed(() => (raw.value ? labelOf(raw.value) : '未知'));
const visible = computed(() => {
  if (!raw.value) return false;
  const lower = raw.value.toLowerCase();
  return lower !== 'unknown' && raw.value !== '未知';
});

const tone = computed(() => {
  const value = raw.value.toLowerCase();
  if (['effective', 'current', 'enabled', 'published', 'pass', 'done', 'success', 'read', 'resolved'].includes(value)) return 'moss';
  if (value.includes('有效') || value.includes('启用') || value.includes('通过') || value.includes('完成') || value.includes('已处理') || value.includes('已解决')) return 'moss';
  if (['amended', 'open', 'pending', 'auditing', 'processing', 'running', 'draft'].includes(value)) return 'gold';
  if (value.includes('修订') || value.includes('待') || value.includes('进行') || value.includes('审核')) return 'gold';
  if (['expired', 'repealed', 'failed', 'reject', 'rejected', 'offline', 'disabled'].includes(value)) return 'rose';
  if (value.includes('失效') || value.includes('废止') || value.includes('失败') || value.includes('驳回')) return 'rose';
  return 'outline';
});
</script>
