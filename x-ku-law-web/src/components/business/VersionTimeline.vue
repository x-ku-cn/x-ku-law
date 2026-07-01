<template>
  <div class="timeline">
    <button
      v-for="version in versions"
      :key="version.id"
      type="button"
      class="version"
      :class="{ active: String(version.id) === String(activeVersionId) }"
      @click="emit('select', version.id)"
    >
      <Diamond :size="9" />
      <div>
        <strong>{{ versionLabel(version) }}</strong>
        <span>{{ versionDateLabel(version) }}</span>
      </div>
    </button>
  </div>
</template>

<script setup lang="ts">
import Diamond from '@/components/brand/Diamond.vue';
import { labelOf } from '@/utils/labels';
import type { LawVersion } from '@/types/law';

function versionLabel(version: LawVersion) {
  if (version.versionName) return version.versionName;
  if (version.versionNo) return version.versionNo;
  if (version.versionStatus) return labelOf(version.versionStatus);
  return `版本 ${version.id}`;
}

// 明确标注是施行日还是公布日，避免无标签回退时两种日期混淆。
function versionDateLabel(version: LawVersion) {
  if (version.effectiveDate) return `施行 ${version.effectiveDate}`;
  if (version.publishDate) return `公布 ${version.publishDate}`;
  return '日期未标注';
}

defineProps<{
  versions: LawVersion[];
  activeVersionId?: string | number;
}>();

const emit = defineEmits<{
  select: [versionId: number];
}>();
</script>

<style scoped>
.timeline {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  align-items: stretch;
  padding: 18px 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
}

.version {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  min-width: 180px;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  color: var(--ink-3);
  cursor: pointer;
}

.version.active {
  color: var(--accent-deep);
}

.version.active strong {
  color: var(--accent-deep);
}

.version.active :deep(.diamond) {
  background: var(--accent);
}

strong {
  display: block;
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}

span {
  display: block;
  margin-top: 3px;
  color: var(--muted);
  font-family: var(--mono);
  font-size: var(--font-xs);
}

.version.active span {
  color: var(--accent-deep);
}
</style>
