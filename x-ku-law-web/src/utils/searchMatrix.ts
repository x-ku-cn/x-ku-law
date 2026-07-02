import type { MatrixNode } from '@/components/editorial/ResultLevelMatrix.vue';
import type { LawSearchResult, MatrixBucket } from '@/types/law';
import { matrixExcludedLevels, matrixLevelOf, matrixLevelOrder } from '@/utils/labels';

// 层级顺序与排除项的唯一来源在 labels.ts（与 effectLevel 词表同处），此处仅消费。
const LEVEL_ORDER = matrixLevelOrder;
const MATRIX_EXCLUDED_LEVELS = new Set(matrixExcludedLevels);

export function buildSearchMatrix(items: LawSearchResult[], years: number[]) {
  const buckets = new Map<string, number>();
  const levels = [...LEVEL_ORDER];

  for (const item of items) {
    const level = matrixLevelOf(item.effectLevel) || '其他';
    if (!levels.includes(level)) levels.push(level);
    const year = parseYear(item.effectiveDate);
    if (!year) continue;
    buckets.set(`${level}|${year}`, (buckets.get(`${level}|${year}`) || 0) + 1);
  }

  return assembleMatrix(levels, years, buckets);
}

/**
 * 从服务端聚合桶构建矩阵：桶为跨全结果集的 effectLevel × year 统计，
 * 因此气泡密度反映全部命中而非当前页。
 */
export function buildMatrixFromBuckets(serverBuckets: MatrixBucket[], years: number[]) {
  const buckets = new Map<string, number>();
  const levels = [...LEVEL_ORDER];

  for (const bucket of serverBuckets) {
    const level = matrixLevelOf(bucket.effectLevel) || '其他';
    if (!levels.includes(level)) levels.push(level);
    if (!bucket.year) continue;
    buckets.set(`${level}|${bucket.year}`, (buckets.get(`${level}|${bucket.year}`) || 0) + bucket.count);
  }

  return assembleMatrix(levels, years, buckets);
}

function assembleMatrix(levels: string[], years: number[], buckets: Map<string, number>) {
  const activeLevels = levels.filter(
    (level) =>
      !MATRIX_EXCLUDED_LEVELS.has(level) && years.some((year) => (buckets.get(`${level}|${year}`) || 0) > 0)
  );

  const nodes: MatrixNode[] = [];
  activeLevels.forEach((level, levelIndex) => {
    years.forEach((year) => {
      const count = buckets.get(`${level}|${year}`) || 0;
      nodes.push({
        id: `${level}-${year}`,
        levelIndex,
        year,
        count
      });
    });
  });
  return { levels: activeLevels, years, nodes };
}

function parseYear(value?: string) {
  if (!value) return 0;
  const y = Number(String(value).slice(0, 4));
  return Number.isFinite(y) ? y : 0;
}

export function defaultMatrixYears() {
  const end = new Date().getFullYear();
  return Array.from({ length: 9 }, (_, i) => end - 8 + i);
}
