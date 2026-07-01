<template>
  <section class="page resource-page">
    <header class="resource-head">
      <div>
        <div class="section-kicker">{{ config.kicker }}</div>
        <h1 class="h1">{{ config.title }}</h1>
        <p>{{ config.description }}</p>
      </div>
      <XButton v-if="config.fields?.length" variant="primary" @click="openCreateModal">新建记录</XButton>
    </header>

    <section v-if="showKeyword || statusFilterOptions.length" class="toolbar" :class="{ 'toolbar--compact': !statusFilterOptions.length }">
      <XInput v-if="showKeyword" v-model="keyword" placeholder="关键词筛选" @keyup.enter="reload(1)" />
      <XSelect v-if="statusFilterOptions.length" v-model="status" :options="statusFilterOptions" placeholder="全部状态" />
      <XButton :loading="loading" @click="reload(1)">筛选</XButton>
    </section>

    <div class="resource-meta">
      <span class="mono">当前资源</span>
      <strong>{{ config.title }}</strong>
      <span v-if="loading" class="resource-count-sk sk-shimmer" aria-hidden="true" />
      <span v-else class="mono">共 {{ total }} 条</span>
      <button v-if="keyword || status" type="button" class="clear-filter" @click="clearFilters">清空筛选</button>
    </div>

    <PageState v-if="error" :error="error" />
    <template v-if="isFeedLayout">
      <SkeletonList v-if="loading" :count="6" />
      <EditorialFeed
        v-else
        :items="feedItems"
        :empty-title="`暂无${config.title}`"
        :empty-description="config.description"
        @select="onFeedSelect"
      />
    </template>
    <template v-else>
      <SkeletonTable
        v-if="loading"
        :columns="config.columns.length + (hasRowActions ? 1 : 0)"
        :show-actions="hasRowActions"
      />
      <XTable
        v-else
        dense
        :columns="config.columns"
        :rows="rows"
        :row-clickable="resourceKey === 'favorites'"
        :empty-title="`暂无${config.title}`"
        :empty-description="keyword || status ? '当前筛选条件下没有记录。' : config.description"
        @row-click="onTableRowClick"
      >
        <template v-for="col in specialColumns" :key="col.key" #[`cell-${col.key}`]="{ row, value }">
          <StatusBadge v-if="col.type === 'status'" :value="String(value ?? '')" />
          <span v-else-if="col.type === 'bool'">{{ labelOf(value) }}</span>
          <span v-else-if="col.type === 'datetime'" class="cell-time">{{ formatDateTime(value) }}</span>
          <span v-else-if="col.type === 'relation'">{{ relationCellLabel(row, col, value) }}</span>
        </template>
        <template #actions="{ row }">
          <XButton v-if="config.update && config.fields?.length" size="small" variant="ghost" @click="startEdit(row)">编辑</XButton>
          <XButton
            v-if="config.extraAction && (config.extraAction.show ? config.extraAction.show(row) : true)"
            size="small"
            variant="ghost"
            :loading="actionPendingId === row.id"
            @click="runExtraAction(row)"
          >
            {{ config.extraAction.label }}
          </XButton>
          <XButton v-if="canStartParseRepair(row)" size="small" variant="ghost" :loading="actionPendingId === row.id" @click="startParseRepair(row)">
            解析修复
          </XButton>
          <XButton v-if="config.assign" size="small" variant="ghost" @click="openAssign(row)">{{ config.assign.label }}</XButton>
          <XButton v-if="config.markRead" size="small" variant="ghost" @click="markRead(row.id as number)">标记已读</XButton>
          <XButton v-if="config.remove" size="small" variant="ghost" @click="remove(row.id as number)">删除</XButton>
        </template>
      </XTable>
    </template>
    <XPagination
      class="resource-pager"
      :class="{ 'resource-pager--loading': loading }"
      :total="total"
      :page-no="pageNo"
      :page-size="pageSize"
      @change="reload"
    />

    <XModal
      v-if="config.fields?.length"
      :open="creating"
      :title="editingId ? '编辑记录' : '新建记录'"
      :description="config.description"
      :kicker="config.kicker"
      max-width="960px"
      @update:open="cancelForm"
    >
      <form id="resource-form" class="create-form" @submit.prevent="submitForm">
        <fieldset v-for="grp in fieldGroups" :key="grp.name || 'default'" class="field-group">
          <legend v-if="grp.name" class="field-group__legend">{{ grp.name }}</legend>
          <div class="field-grid">
            <XFormField
              v-for="field in grp.fields"
              :key="field.key"
              :label="field.label"
              :required="field.required"
              :hint="field.hint"
              :error="fieldErrors[field.key]"
              :class="{ 'field--wide': isWideField(field) }"
            >
              <XTextarea
                v-if="field.type === 'textarea' || field.type === 'json'"
                :model-value="getTextValue(field.key)"
                :placeholder="field.placeholder"
                :class="{ 'field-json': field.type === 'json' }"
                :invalid="Boolean(fieldErrors[field.key])"
                @update:model-value="setFieldValue(field.key, $event)"
              />
              <XRelationSelect
                v-else-if="field.type === 'relation'"
                :model-value="getInputValue(field.key)"
                :resource="field.relation!.resource"
                :placeholder="field.placeholder || '搜索并选择…'"
                :invalid="Boolean(fieldErrors[field.key])"
                :disabled="isReadonly(field)"
                :initial-label="relationInitialLabel(field)"
                :query-params="relationQueryParams(field)"
                @update:model-value="setFieldValue(field.key, $event)"
              />
              <XSelect
                v-else-if="field.type === 'select'"
                :model-value="getSelectValue(field.key)"
                :options="selectOptions(field)"
                :placeholder="field.placeholder || '请选择'"
                :invalid="Boolean(fieldErrors[field.key])"
                :disabled="isReadonly(field)"
                @update:model-value="setFieldValue(field.key, $event)"
              />
              <XCheckbox
                v-else-if="field.type === 'checkbox'"
                :model-value="Boolean(form[field.key])"
                :label="field.placeholder || '已启用'"
                @update:model-value="form[field.key] = $event"
              />
              <XInput
                v-else
                :model-value="getInputValue(field.key)"
                :type="inputType(field)"
                :placeholder="field.placeholder"
                :disabled="isReadonly(field)"
                :invalid="Boolean(fieldErrors[field.key])"
                @update:model-value="setFieldValue(field.key, $event)"
              />
            </XFormField>
          </div>
        </fieldset>
      </form>
      <template #footer>
        <span v-if="formMessage" class="form-message">{{ formMessage }}</span>
        <XButton type="button" variant="ghost" @click="cancelForm">取消</XButton>
        <XButton variant="primary" type="submit" form="resource-form" :loading="formSubmitting">保存</XButton>
      </template>
    </XModal>

    <XModal
      v-if="config.assign"
      :open="assignOpen"
      :title="config.assign.title"
      :kicker="config.kicker"
      max-width="640px"
      @update:open="closeAssign"
    >
      <div class="assign">
        <div class="assign-bar">
          <XInput v-model="assignKeyword" placeholder="搜索名称 / 编码" />
          <div class="assign-bulk">
            <button type="button" @click="selectAllAssign">全选</button>
            <button type="button" @click="clearAssign">清空</button>
          </div>
        </div>
        <PageState v-if="assignError" :error="assignError" />
        <SkeletonList v-else-if="assignLoading" :count="6" />
        <EmptyState
          v-else-if="!assignOptions.length"
          title="暂无可分配项"
          :description="config.assign.emptyText || '没有可分配的项目。'"
        />
        <div v-else class="assign-list">
          <XCheckbox
            v-for="opt in filteredAssignOptions"
            :key="opt.id"
            class="assign-item"
            :model-value="assignSelected.has(opt.id)"
            @update:model-value="toggleAssign(opt.id)"
          >
            <span class="assign-item__label">{{ opt.label }}</span>
            <span v-if="opt.sub" class="assign-item__sub mono">{{ opt.sub }}</span>
          </XCheckbox>
          <p v-if="!filteredAssignOptions.length" class="assign-empty">没有匹配的项。</p>
        </div>
      </div>
      <template #footer>
        <span class="assign-count mono">已选 {{ assignSelected.size }} 项</span>
        <XButton type="button" variant="ghost" @click="closeAssign">取消</XButton>
        <XButton variant="primary" :loading="assignSaving" :disabled="assignLoading" @click="saveAssign">保存</XButton>
      </template>
    </XModal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import EditorialFeed from '@/components/editorial/EditorialFeed.vue';
import type { EditorialFeedItem } from '@/components/editorial/EditorialFeed.vue';
import SkeletonList from '@/components/common/SkeletonList.vue';
import XButton from '@/components/common/XButton.vue';
import XCheckbox from '@/components/common/XCheckbox.vue';
import XFormField from '@/components/common/XFormField.vue';
import XInput from '@/components/common/XInput.vue';
import XModal from '@/components/common/XModal.vue';
import XPagination from '@/components/common/XPagination.vue';
import XRelationSelect from '@/components/common/XRelationSelect.vue';
import XSelect from '@/components/common/XSelect.vue';
import XTable from '@/components/common/XTable.vue';
import XTextarea from '@/components/common/XTextarea.vue';
import PageState from '@/components/common/PageState.vue';
import SkeletonTable from '@/components/common/SkeletonTable.vue';
import StatusBadge from '@/components/common/StatusBadge.vue';
import EmptyState from '@/components/common/EmptyState.vue';
import { useConfirm } from '@/composables/useConfirm';
import { useToast } from '@/composables/useToast';
import { createParseRepairIssue } from '@/api/ops';
import type { OptionItem, RecordValue } from '@/types/api';
import { resolveApiError } from '@/utils/apiError';
import { formatDateTime } from '@/utils/datetime';
import { labelOf } from '@/utils/labels';
import { lawDetailTo } from '@/router/navigation';
import type { NotificationInbox, SubscriptionMatch } from '@/types/workspace';
import { inboxToFeedItem, matchToFeedItem } from '@/utils/editorialFeed';
import { resourceConfigs, type AssignOption, type ColumnConfig, type FieldConfig } from './resourceConfigs';
import { useDictStore } from '@/stores/dict';

const dictStore = useDictStore();
const route = useRoute();
const router = useRouter();
const { confirm } = useConfirm();
const toast = useToast();
const resourceKey = computed(() => String(route.meta.resource || route.params.resource || 'lawDocuments'));
const config = computed(() => resourceConfigs[resourceKey.value] || resourceConfigs.lawDocuments);
const isFeedLayout = computed(() => config.value.layout === 'feed');
const feedItems = computed<EditorialFeedItem[]>(() => {
  if (resourceKey.value === 'alerts') {
    return (rows.value as unknown as SubscriptionMatch[]).map(matchToFeedItem);
  }
  if (resourceKey.value === 'messages') {
    return (rows.value as unknown as NotificationInbox[]).map(inboxToFeedItem);
  }
  return [];
});
const hasRowActions = computed(
  () =>
    Boolean(
      config.value.update ||
        config.value.remove ||
        config.value.markRead ||
        config.value.extraAction ||
        config.value.assign ||
        resourceKey.value === 'lawVersions' ||
        resourceKey.value === 'lawArticles'
    )
);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = 10;
const keyword = ref('');
const status = ref('');
const loading = ref(false);
const error = ref('');
const creating = ref(false);
const editingId = ref<number | null>(null);
const editingRow = ref<Record<string, unknown> | null>(null);
const form = reactive<Record<string, RecordValue>>({});
const fieldErrors = reactive<Record<string, string>>({});
const formMessage = ref('');
const actionPendingId = ref<number | null>(null);
const formSubmitting = ref(false);

// ===== 多对多分配（用户↔角色 / 角色↔权限） =====
const assignOpen = ref(false);
const assignRowId = ref<number | null>(null);
const assignOptions = ref<AssignOption[]>([]);
const assignSelected = ref<Set<number>>(new Set());
const assignKeyword = ref('');
const assignLoading = ref(false);
const assignSaving = ref(false);
const assignError = ref('');

const filteredAssignOptions = computed(() => {
  const kw = assignKeyword.value.trim().toLowerCase();
  if (!kw) return assignOptions.value;
  return assignOptions.value.filter(
    (o) => o.label.toLowerCase().includes(kw) || (o.sub || '').toLowerCase().includes(kw)
  );
});

const showKeyword = computed(() => config.value.filters?.keyword !== false);
const statusFilterOptions = computed<OptionItem[]>(() => {
  const f = config.value.filters;
  const opts = f?.statusDict ? dictStore.options(f.statusDict, f?.status || []) : f?.status;
  return opts?.length ? [{ label: '全部', value: '' }, ...opts] : [];
});
const specialColumns = computed<ColumnConfig[]>(() => config.value.columns.filter((c) => c.type || c.labelField));

/** 当前显隐的字段：新建跳过 mode==='edit'，编辑跳过 mode==='create'。 */
const visibleFields = computed<FieldConfig[]>(() => {
  const isEdit = editingId.value != null;
  return (config.value.fields || []).filter((f) => (isEdit ? f.mode !== 'create' : f.mode !== 'edit'));
});

/** 按 group 聚合字段；无任何分组信息时返回单个无名组。 */
const fieldGroups = computed<{ name: string; fields: FieldConfig[] }[]>(() => {
  const fields = visibleFields.value;
  const declared = config.value.groups;
  const useGroups = Boolean(declared?.length) || fields.some((f) => f.group);
  if (!useGroups) return [{ name: '', fields }];
  const defaultName = declared?.[0] || '基础信息';
  const order: string[] = declared ? [...declared] : [];
  const map = new Map<string, FieldConfig[]>();
  for (const f of fields) {
    const g = f.group || defaultName;
    if (!map.has(g)) {
      map.set(g, []);
      if (!order.includes(g)) order.push(g);
    }
    map.get(g)!.push(f);
  }
  return order.filter((g) => map.has(g)).map((g) => ({ name: g, fields: map.get(g)! }));
});

function isReadonly(field: FieldConfig) {
  return Boolean(field.readonly && editingId.value != null);
}

function isWideField(field: FieldConfig) {
  return field.type === 'textarea' || field.type === 'json';
}

function inputType(field: FieldConfig) {
  if (field.type === 'date') return 'date';
  if (field.type === 'number') return 'number';
  return 'text';
}

function relationQueryParams(field: FieldConfig): Record<string, unknown> {
  const rel = field.relation;
  if (!rel?.dependsOn) return {};
  const depValue = form[rel.dependsOn];
  if (depValue === '' || depValue === null || depValue === undefined) return {};
  return { [rel.queryKey || rel.dependsOn]: depValue };
}

function relationInitialLabel(field: FieldConfig) {
  const labelField = field.relation?.labelField;
  if (!labelField || !editingRow.value) return '';
  const v = editingRow.value[labelField];
  return v ? String(v) : '';
}

function relationCellLabel(row: Record<string, unknown>, col: ColumnConfig, value: unknown) {
  if (col.labelField && row[col.labelField]) return String(row[col.labelField]);
  return value === null || value === undefined || value === '' ? '—' : `#${value}`;
}

async function reload(nextPage = pageNo.value) {
  pageNo.value = nextPage;
  loading.value = true;
  error.value = '';
  try {
    const result = await config.value.loader({
      pageNo: pageNo.value,
      pageSize,
      keyword: keyword.value || undefined,
      status: status.value || undefined
    });
    rows.value = result.list as Record<string, unknown>[];
    total.value = result.total;
  } catch (err) {
    error.value = resolveApiError(err, '数据读取失败。');
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

function defaultFieldValue(field: FieldConfig): RecordValue {
  return field.type === 'checkbox' ? false : '';
}

function normalizeFieldValue(field: FieldConfig, value: unknown): RecordValue {
  if (field.type === 'checkbox') {
    if (typeof value === 'boolean') return value;
    if (typeof value === 'string') return value === 'true' || value === '1';
    return Boolean(value);
  }
  return value === null || value === undefined ? '' : String(value);
}

function clearFieldErrors() {
  Object.keys(fieldErrors).forEach((key) => {
    delete fieldErrors[key];
  });
}

function resetForm() {
  Object.keys(form).forEach((key) => {
    delete form[key];
  });
  clearFieldErrors();
  (config.value.fields || []).forEach((field) => {
    form[field.key] = defaultFieldValue(field);
  });
}

function buildPayload() {
  const payload: Record<string, unknown> = {};
  visibleFields.value.forEach((field) => {
    if (isReadonly(field)) return; // 系统/身份字段不回写
    const value = form[field.key];
    if (field.type === 'checkbox') {
      payload[field.key] = Boolean(value);
      return;
    }
    if (value === '' || value === undefined || value === null) return;
    if (field.type === 'number' || field.type === 'relation') {
      const n = Number(value);
      payload[field.key] = Number.isFinite(n) && String(n) === String(value) ? n : value;
      return;
    }
    payload[field.key] = value;
  });
  return payload;
}

function validateForm() {
  clearFieldErrors();
  formMessage.value = '';

  let valid = true;
  visibleFields.value.forEach((field) => {
    if (isReadonly(field)) return;
    const value = form[field.key];
    const missing = field.type === 'checkbox'
      ? field.required && value !== true
      : field.required && String(value ?? '').trim() === '';
    if (missing) {
      fieldErrors[field.key] = `${field.label}不能为空。`;
      valid = false;
      return;
    }
    if (field.type === 'json' && typeof value === 'string' && value.trim()) {
      try {
        JSON.parse(value);
      } catch {
        fieldErrors[field.key] = 'JSON 格式不正确。';
        valid = false;
      }
    }
  });

  if (!valid) {
    formMessage.value = '请检查标红的字段。';
  }
  return valid;
}

function getTextValue(key: string) {
  const value = form[key];
  return typeof value === 'string' ? value : '';
}

/** select 选项：有 dictCode 则优先取字典，缺失回退配置内置 options。 */
function selectOptions(field: FieldConfig): OptionItem[] {
  const fallback = field.options || [];
  return field.dictCode ? dictStore.options(field.dictCode, fallback) : fallback;
}

function getSelectValue(key: string) {
  const value = form[key];
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return value;
  }
  return '';
}

function getInputValue(key: string) {
  const value = form[key];
  if (typeof value === 'string' || typeof value === 'number') {
    return value;
  }
  return '';
}

function setFieldValue(key: string, value: RecordValue) {
  form[key] = value;
}

function openCreateModal() {
  editingId.value = null;
  editingRow.value = null;
  resetForm();
  formMessage.value = '';
  creating.value = true;
}

function cancelForm() {
  creating.value = false;
  editingId.value = null;
  editingRow.value = null;
  formMessage.value = '';
  resetForm();
}

function startEdit(row: Record<string, unknown>) {
  editingId.value = (row.id as number) ?? null;
  editingRow.value = row;
  formMessage.value = '';
  clearFieldErrors();
  (config.value.fields || []).forEach((field) => {
    form[field.key] = normalizeFieldValue(field, row[field.key]);
  });
  creating.value = true;
}

async function submitForm() {
  if (!validateForm()) {
    return;
  }

  const payload = buildPayload();
  const isEdit = editingId.value != null;
  try {
    formSubmitting.value = true;
    if (isEdit) {
      if (!config.value.update) return;
      await config.value.update(editingId.value as number, payload);
      toast.success('记录已更新。');
    } else {
      if (!config.value.create) return;
      await config.value.create(payload);
      toast.success('记录已创建。');
    }
    cancelForm();
    await reload(isEdit ? pageNo.value : 1);
  } catch (err) {
    formMessage.value = resolveApiError(err, '保存失败。');
  } finally {
    formSubmitting.value = false;
  }
}

function clearFilters() {
  keyword.value = '';
  status.value = '';
  void reload(1);
}

async function remove(id: number) {
  if (!config.value.remove) return;
  const confirmed = await confirm({
    title: '确认删除这条记录？',
    message: '此操作不可撤销，删除后需要重新创建。',
    confirmText: '确认删除',
    danger: true
  });
  if (!confirmed) return;

  try {
    await config.value.remove(id);
    if (editingId.value === id) cancelForm();
    toast.success('记录已删除。');
    await reload(pageNo.value);
  } catch (err) {
    error.value = resolveApiError(err, '删除失败。');
    toast.error(error.value);
  }
}

async function onFeedSelect(_item: EditorialFeedItem, index: number) {
  const row = rows.value[index];
  if (!row) return;
  if (resourceKey.value === 'alerts') {
    const documentId = row.documentId as number | undefined;
    if (documentId) {
      router.push(lawDetailTo(documentId));
      return;
    }
  }
  if (resourceKey.value === 'messages') {
    if (row.bizType === 'law_document' && row.bizId) {
      router.push(lawDetailTo(row.bizId as number));
      return;
    }
  }
  if (config.value.markRead && typeof row.id === 'number') {
    await markRead(row.id);
  }
}

function onTableRowClick(row: Record<string, unknown>) {
  if (resourceKey.value !== 'favorites') return;
  if (row.refType === 'document' || row.refType === 'law_document') {
    const id = row.refId;
    if (id) router.push(lawDetailTo(id as number));
  }
}

async function markRead(id: number) {
  if (!config.value.markRead) return;
  try {
    await config.value.markRead(id);
    toast.success('已标记为已读。');
    await reload(pageNo.value);
  } catch (err) {
    toast.error(resolveApiError(err, '标记已读失败。'));
  }
}

async function runExtraAction(row: Record<string, unknown>) {
  const action = config.value.extraAction;
  if (!action) return;
  const id = row.id as number;
  actionPendingId.value = id;
  try {
    await action.run(id);
    toast.success(action.successText || '操作成功。');
    await reload(pageNo.value);
  } catch (err) {
    toast.error(resolveApiError(err, '操作失败。'));
  } finally {
    actionPendingId.value = null;
  }
}

function canStartParseRepair(row: Record<string, unknown>) {
  if (resourceKey.value === 'lawVersions') return Boolean(row.id);
  if (resourceKey.value === 'lawArticles') return Boolean(row.versionId);
  return false;
}

async function startParseRepair(row: Record<string, unknown>) {
  const versionId = resourceKey.value === 'lawVersions' ? Number(row.id) : Number(row.versionId);
  if (!Number.isFinite(versionId)) return;
  actionPendingId.value = Number(row.id) || versionId;
  try {
    const issue = await createParseRepairIssue({
      bizType: 'law_version',
      bizId: versionId,
      source: 'manual',
      reason: '管理员在管理端发现解析结构需要人工复核'
    });
    router.push({
      name: 'admin.ops.parseRepairEditor',
      params: { bizType: issue.bizType, bizId: issue.bizId },
      query: { repairIssueId: issue.id, parserType: issue.parserType, layoutType: issue.layoutType }
    });
  } catch (err) {
    toast.error(resolveApiError(err, '发起解析修复失败。'));
  } finally {
    actionPendingId.value = null;
  }
}

async function openAssign(row: Record<string, unknown>) {
  const cfg = config.value.assign;
  if (!cfg) return;
  const id = row.id as number;
  assignRowId.value = id;
  assignKeyword.value = '';
  assignError.value = '';
  assignSelected.value = new Set();
  assignOptions.value = [];
  assignOpen.value = true;
  assignLoading.value = true;
  try {
    const [options, assigned] = await Promise.all([cfg.options(), cfg.loadAssigned(id)]);
    assignOptions.value = options;
    assignSelected.value = new Set(assigned);
  } catch (err) {
    assignError.value = resolveApiError(err, '加载分配数据失败。');
  } finally {
    assignLoading.value = false;
  }
}

function closeAssign() {
  assignOpen.value = false;
  assignRowId.value = null;
}

function toggleAssign(id: number) {
  const next = new Set(assignSelected.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  assignSelected.value = next;
}

function selectAllAssign() {
  const next = new Set(assignSelected.value);
  filteredAssignOptions.value.forEach((o) => next.add(o.id));
  assignSelected.value = next;
}

function clearAssign() {
  assignSelected.value = new Set();
}

async function saveAssign() {
  const cfg = config.value.assign;
  if (!cfg || assignRowId.value == null) return;
  assignSaving.value = true;
  try {
    await cfg.save(assignRowId.value, [...assignSelected.value]);
    toast.success(cfg.successText || '分配已保存。');
    closeAssign();
  } catch (err) {
    toast.error(resolveApiError(err, '保存分配失败。'));
  } finally {
    assignSaving.value = false;
  }
}

watch(resourceKey, () => {
  rows.value = [];
  total.value = 0;
  creating.value = false;
  editingId.value = null;
  editingRow.value = null;
  assignOpen.value = false;
  assignRowId.value = null;
  keyword.value = '';
  status.value = '';
  formMessage.value = '';
  resetForm();
  reload(1);
});

onMounted(() => {
  void dictStore.ensureLoaded();
  resetForm();
  reload(1);
});
</script>

<style scoped>
.resource-page {
  display: grid;
  gap: 22px;
}

.resource-head {
  display: flex;
  gap: 24px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--ink);
}

.resource-head p {
  max-width: 72ch;
  margin: 10px 0 0;
  color: var(--ink-3);
  font-family: var(--serif-body);
  font-size: 16px;
  line-height: 1.65;
}

.toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 180px auto;
  gap: 12px;
  align-items: stretch;
}

.toolbar--compact {
  grid-template-columns: minmax(220px, 1fr) auto;
}

.resource-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding: 10px 0;
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
}

.resource-meta .mono {
  color: var(--muted);
  font-size: var(--font-xs);
}

.resource-meta strong {
  color: var(--ink);
  font-family: var(--serif-body);
  font-size: 14px;
  font-weight: 500;
}

.resource-count-sk {
  display: inline-block;
  width: 72px;
  height: 10px;
  border-radius: 2px;
}

.clear-filter {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--accent-deep);
  font-size: 12px;
  cursor: pointer;
}

.clear-filter:hover {
  color: var(--ink);
}

.create-form {
  display: grid;
  gap: 18px;
}

.field-group {
  margin: 0;
  padding: 0;
  border: 0;
}

.field-group__legend {
  margin-bottom: 12px;
  padding: 0;
  color: var(--muted);
  font-family: var(--sans);
  font-size: var(--font-xxs);
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.field-group + .field-group {
  padding-top: 16px;
  border-top: 1px solid var(--rule);
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px 20px;
}

.field--wide {
  grid-column: 1 / -1;
}

.field-json :deep(.x-textarea),
.field-json :deep(textarea) {
  font-family: var(--mono);
  font-size: var(--font-xs);
}

.cell-time {
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}

.form-message {
  margin-right: auto;
  color: var(--rose);
  font-size: 12px;
}

.resource-pager--loading {
  opacity: 0.5;
  pointer-events: none;
}

.assign {
  display: grid;
  gap: 14px;
}

.assign-bar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.assign-bulk {
  display: flex;
  gap: 12px;
  margin-left: auto;
  white-space: nowrap;
}

.assign-bulk button {
  border: 0;
  background: transparent;
  color: var(--accent-deep);
  font-size: 12px;
  cursor: pointer;
}

.assign-bulk button:hover {
  color: var(--ink);
}

.assign-list {
  display: grid;
  gap: 2px;
  max-height: 48vh;
  overflow: auto;
  padding-right: 4px;
  scrollbar-width: thin;
}

.assign-list .assign-item {
  display: flex;
  width: 100%;
  min-height: 34px;
  padding: 2px 8px;
  border-radius: 4px;
}

.assign-list .assign-item:hover {
  background: var(--paper-2);
}

.assign-item :deep(.x-checkbox__label) {
  display: flex;
  gap: 10px;
  align-items: baseline;
  width: 100%;
  min-width: 0;
}

.assign-item__label {
  overflow: hidden;
  color: var(--ink);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assign-item__sub {
  margin-left: auto;
  color: var(--muted);
  font-size: var(--font-xxs);
  white-space: nowrap;
}

.assign-empty {
  margin: 0;
  padding: 12px;
  color: var(--muted);
  font-size: 13px;
}

.assign-count {
  margin-right: auto;
  color: var(--muted);
  font-size: var(--font-xs);
}

@media (max-width: 860px) {
  .resource-head,
  .toolbar,
  .toolbar--compact,
  .field-grid {
    grid-template-columns: 1fr;
  }
}
</style>
