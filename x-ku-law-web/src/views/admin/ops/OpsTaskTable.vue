<template>
  <div class="ops-task">
    <div class="filters">
      <div v-if="statusOptions.length" class="filters-field">
        <XSelect v-model="status" :options="statusFilterOptions" placeholder="全部状态" @update:model-value="onStatusChange" />
      </div>
      <XButton size="small" :loading="loading" @click="reloadFirst">刷新</XButton>
      <XButton
        v-if="retryAll"
        size="small"
        :loading="retryingAll"
        @click="runRetryAll"
      >
        重试全部失败
      </XButton>
      <slot name="actions" :reload="reload" />
    </div>

    <PageState v-if="error" :error="error" />
    <SkeletonTable v-if="loading" :columns="columns.length + (hasRowActions ? 1 : 0)" :show-actions="hasRowActions" />
    <template v-else>
      <XTable
        dense
        row-clickable
        :columns="columns"
        :rows="rows"
        :empty-title="emptyTitle"
        :empty-description="emptyDescription"
        @row-click="openDetail"
      >
        <template v-for="col in specialColumns" :key="col.key" #[`cell-${col.key}`]="{ row, value }">
          <StatusBadge v-if="col.type === 'status'" :value="String(value ?? '')" />
          <span v-else-if="col.type === 'datetime'" class="cell-time">{{ formatDateTime(value) }}</span>
          <span v-else-if="col.type === 'relation'">{{ relationCellLabel(row, col, value) }}</span>
        </template>
        <template v-if="hasRowActions" #actions="{ row }">
          <div class="row-actions">
            <XButton
              v-if="retry && row[statusKey] === 'failed'"
              size="small"
              :loading="retryingId === row.id"
              @click.stop="runRetry(row)"
            >
              重试
            </XButton>
            <template v-if="rowActions">
              <XButton
                v-for="rowAction in rowActionsFor(row)"
                :key="rowAction.key"
                size="small"
                :variant="rowAction.variant || 'ghost'"
                :disabled="rowAction.disabled || Boolean(actingActionKey)"
                @click.stop="runRowAction(row, rowAction)"
              >
                {{ rowAction.label }}
              </XButton>
            </template>
            <XButton
              v-else-if="action && row[statusKey] === actionStatus"
              size="small"
              :loading="actingId === row.id"
              @click.stop="runAction(row)"
            >
              {{ actionLabelFor(row) }}
            </XButton>
          </div>
        </template>
      </XTable>
      <XPagination :total="total" :page-no="pageNo" :page-size="pageSize" @change="onPageChange" />
    </template>

    <XModal
      :open="Boolean(detailRow)"
      title="任务详情"
      kicker="§ Task detail"
      max-width="640px"
      @update:open="detailRow = null"
    >
      <div v-if="detailRow" class="detail">
        <dl class="detail-grid">
          <template v-for="f in detailFieldList" :key="f.key">
            <dt>{{ f.label }}</dt>
            <dd>
              <StatusBadge v-if="f.type === 'status'" :value="String(detailRow[f.key] ?? '')" />
              <span v-else-if="f.type === 'datetime'">{{ formatDateTime(detailRow[f.key]) }}</span>
              <span v-else>{{ displayValue(detailRow[f.key]) }}</span>
            </dd>
          </template>
        </dl>
        <div v-if="detailRow.errorMessage" class="detail-error">
          <div class="section-kicker">§ Error</div>
          <pre>{{ detailRow.errorMessage }}</pre>
        </div>
      </div>
      <template #footer>
        <RouterLink v-if="lawLink" class="detail-link" :to="lawLink">查看关联法规 →</RouterLink>
        <XButton
          v-if="retry && detailRow && detailRow[statusKey] === 'failed'"
          variant="primary"
          :loading="retryingId === detailRow.id"
          @click="runRetry(detailRow)"
        >
          重试
        </XButton>
        <XButton
          v-else-if="action && detailRow && detailRow[statusKey] === actionStatus"
          variant="primary"
          :loading="actingId === detailRow.id"
          @click="runAction(detailRow)"
        >
          {{ actionLabelFor(detailRow) }}
        </XButton>
        <template v-if="rowActions && detailRow">
          <XButton
            v-for="rowAction in rowActionsFor(detailRow)"
            :key="rowAction.key"
            :variant="rowAction.variant || 'ghost'"
            :disabled="rowAction.disabled || Boolean(actingActionKey)"
            @click="runRowAction(detailRow, rowAction)"
          >
            {{ rowAction.label }}
          </XButton>
        </template>
        <XButton variant="ghost" @click="detailRow = null">关闭</XButton>
      </template>
    </XModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import XButton from '@/components/common/XButton.vue';
import XSelect from '@/components/common/XSelect.vue';
import XTable from '@/components/common/XTable.vue';
import XPagination from '@/components/common/XPagination.vue';
import XModal from '@/components/common/XModal.vue';
import PageState from '@/components/common/PageState.vue';
import SkeletonTable from '@/components/common/SkeletonTable.vue';
import StatusBadge from '@/components/common/StatusBadge.vue';
import { useToast } from '@/composables/useToast';
import { useConfirm } from '@/composables/useConfirm';
import type { OptionItem, PageResult } from '@/types/api';
import { resolveApiError } from '@/utils/apiError';
import { formatDateTime } from '@/utils/datetime';
import { labelOf } from '@/utils/labels';
import { lawDetailTo } from '@/router/navigation';

export interface OpsColumn {
  key: string;
  label: string;
  width?: string;
  type?: 'status' | 'datetime' | 'relation';
  labelField?: string;
}

type Row = Record<string, unknown>;

export interface OpsRowAction {
  key: string;
  label: string;
  variant?: 'primary' | 'ghost' | 'danger';
  disabled?: boolean;
  run: (row: Row) => Promise<boolean | void> | boolean | void;
  successMessage?: string | ((row: Row) => string);
}

const props = withDefaults(
  defineProps<{
    loader: (params: { status?: string; pageNo: number; pageSize: number }) => Promise<PageResult<any>>;
    columns: OpsColumn[];
    statusKey: string;
    statusOptions?: OptionItem[];
    retry?: (id: number) => Promise<boolean>;
    retryAll?: () => Promise<number>;
    action?: (id: number, row?: Row) => Promise<boolean>;
    actionLabel?: string | ((row: Row) => string);
    actionSuccessMessage?: string | ((row: Row) => string);
    actionStatus?: string;
    rowActions?: (row: Row) => OpsRowAction[];
    detailFields?: OpsColumn[];
    lawLinkKey?: string;
    emptyTitle?: string;
    emptyDescription?: string;
  }>(),
  {
    statusOptions: () => [],
    actionLabel: '处理',
    actionStatus: 'open',
    emptyTitle: '暂无任务',
    emptyDescription: '触发任务后会出现在这里。'
  }
);

const emit = defineEmits<{ retried: [] }>();
const toast = useToast();
const { confirm } = useConfirm();

const status = ref('');
const rows = ref<Row[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = 20;
const loading = ref(false);
const error = ref('');
const retryingId = ref<number | null>(null);
const retryingAll = ref(false);
const actingId = ref<number | null>(null);
const actingActionKey = ref('');
const detailRow = ref<Row | null>(null);

const hasRowActions = computed(() => Boolean(props.retry || props.action || props.rowActions));

const statusFilterOptions = computed<OptionItem[]>(() =>
  props.statusOptions.length ? [{ label: '全部', value: '' }, ...props.statusOptions] : []
);
const specialColumns = computed(() => props.columns.filter((c) => c.type || c.labelField));
const detailFieldList = computed<OpsColumn[]>(() => props.detailFields ?? props.columns);

const lawLink = computed(() => {
  if (!props.lawLinkKey || !detailRow.value) return null;
  const id = detailRow.value[props.lawLinkKey];
  return id ? lawDetailTo(id as number) : null;
});

function relationCellLabel(row: Row, col: OpsColumn, value: unknown) {
  if (col.labelField && row[col.labelField]) return String(row[col.labelField]);
  return value === null || value === undefined || value === '' ? '—' : `#${value}`;
}

function displayValue(value: unknown) {
  return labelOf(value);
}

function actionLabelFor(row: Row) {
  return typeof props.actionLabel === 'function' ? props.actionLabel(row) : props.actionLabel;
}

function actionSuccessMessageFor(row: Row) {
  if (typeof props.actionSuccessMessage === 'function') return props.actionSuccessMessage(row);
  return props.actionSuccessMessage || `#${row.id} 已处理。`;
}

function rowActionsFor(row: Row) {
  return props.rowActions ? props.rowActions(row) : [];
}

function actionKey(row: Row, action: OpsRowAction) {
  return `${row.id || 'row'}:${action.key}`;
}

function rowActionSuccessMessageFor(row: Row, action: OpsRowAction) {
  if (typeof action.successMessage === 'function') return action.successMessage(row);
  return action.successMessage || '';
}

async function load(options?: { silent?: boolean }) {
  const silent = options?.silent ?? false;
  if (!silent) {
    loading.value = true;
    error.value = '';
  }
  try {
    const page = await props.loader({ status: status.value || undefined, pageNo: pageNo.value, pageSize });
    rows.value = page.list as Row[];
    total.value = page.total;
  } catch (err) {
    if (silent) {
      toast.error(resolveApiError(err, '刷新失败。'));
    } else {
      error.value = resolveApiError(err, '加载任务失败。');
    }
  } finally {
    if (!silent) loading.value = false;
  }
}

function reload(silent = false) {
  void load({ silent });
}

function patchRow(id: number, patch: Partial<Row>) {
  const idx = rows.value.findIndex((r) => Number(r.id) === id);
  if (idx >= 0) rows.value[idx] = { ...rows.value[idx], ...patch };
}

function reloadFirst() {
  pageNo.value = 1;
  void load();
}

function onStatusChange() {
  pageNo.value = 1;
  void load();
}

function onPageChange(next: number) {
  pageNo.value = next;
  void load();
}

function openDetail(row: Row) {
  detailRow.value = row;
}

async function runRetry(row: Row) {
  if (!props.retry) return;
  const id = Number(row.id);
  retryingId.value = id;
  try {
    const ok = await props.retry(id);
    if (ok) {
      toast.success(`任务 #${id} 已重新入队。`);
      detailRow.value = null;
      patchRow(id, {
        [props.statusKey]: 'pending',
        retryCount: 0,
        errorMessage: null,
        startedAt: null,
        finishedAt: null
      });
      reload(true);
    } else {
      toast.info('任务状态已变化，无需重试。');
      reload(true);
    }
    emit('retried');
  } catch (err) {
    toast.error(resolveApiError(err, '重试失败。'));
  } finally {
    retryingId.value = null;
  }
}

async function runRetryAll() {
  if (!props.retryAll) return;
  const ok = await confirm({
    title: '重试全部失败任务？',
    message: '将对全部「失败」状态的任务发起重试（不限当前页/筛选），由调度逐批重新处理。',
    confirmText: '全部重试'
  });
  if (!ok) return;
  retryingAll.value = true;
  try {
    const count = await props.retryAll();
    toast.success(count > 0 ? `已重新入队 ${count} 条失败任务。` : '没有失败任务需要重试。');
    reloadFirst();
    emit('retried');
  } catch (err) {
    toast.error(resolveApiError(err, '批量重试失败。'));
  } finally {
    retryingAll.value = false;
  }
}

async function runAction(row: Row) {
  if (!props.action) return;
  const id = Number(row.id);
  actingId.value = id;
  try {
    const ok = await props.action(id, row);
    if (ok) {
      toast.success(actionSuccessMessageFor(row));
      detailRow.value = null;
      reload(true);
    } else {
      toast.info('状态已变化，无需处理。');
      reload(true);
    }
    emit('retried');
  } catch (err) {
    toast.error(resolveApiError(err, '操作失败。'));
  } finally {
    actingId.value = null;
  }
}

async function runRowAction(row: Row, action: OpsRowAction) {
  const key = actionKey(row, action);
  actingActionKey.value = key;
  try {
    const ok = await action.run(row);
    if (ok !== false) {
      const message = rowActionSuccessMessageFor(row, action);
      if (message) toast.success(message);
      detailRow.value = null;
      reload(true);
    }
  } catch (err) {
    toast.error(resolveApiError(err, '操作失败。'));
  } finally {
    actingActionKey.value = '';
  }
}

defineExpose({ reload, reloadFirst });
onMounted(load);
</script>

<style scoped>
.ops-task {
  display: grid;
  gap: 18px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.filters-field {
  flex: 0 1 240px;
  min-width: 200px;
}

.muted {
  color: var(--muted);
}

.row-actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

.cell-time {
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.detail {
  display: grid;
  gap: 18px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 8px 16px;
  margin: 0;
}

.detail-grid dt {
  color: var(--muted);
  font-size: var(--font-xs);
}

.detail-grid dd {
  margin: 0;
  color: var(--ink-2);
  font-size: 13px;
  word-break: break-all;
}

.detail-error pre {
  margin: 8px 0 0;
  padding: 12px;
  max-height: 240px;
  overflow: auto;
  border: 1px solid var(--rose-soft, var(--rule));
  border-radius: 4px;
  background: var(--paper-2);
  color: var(--rose);
  font-family: var(--mono);
  font-size: var(--font-xs);
  white-space: pre-wrap;
  word-break: break-all;
}

.detail-link {
  margin-right: auto;
  color: var(--accent-deep);
  font-size: 13px;
  text-decoration: none;
}

.detail-link:hover {
  color: var(--ink);
}
</style>
