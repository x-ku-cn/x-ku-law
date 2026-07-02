<template>
  <section class="page ops-page parse-repair-page">
    <header class="ops-head">
      <div class="section-kicker">§ Ops · Parse Repair</div>
      <h1>内容解析修复</h1>
      <p>统一处理系统解析异常和管理员主动发现的结构化问题。当前首期接入法规版本，后续可扩展案例、政策、合同等内容。</p>
    </header>

    <section class="toolbar">
      <label>
        <span class="mono">状态</span>
        <XSelect v-model="query.status" :options="statusOptions" />
      </label>
      <label>
        <span class="mono">业务类型</span>
        <XSelect v-model="query.bizType" :options="bizTypeOptions" />
      </label>
      <label>
        <span class="mono">解析类型</span>
        <XSelect v-model="query.parserType" :options="parserTypeOptions" />
      </label>
      <XButton size="small" variant="primary" :loading="loading" @click="loadIssues">刷新</XButton>
    </section>

    <PageState v-if="error" :error="error" />

    <section v-else class="queue-card">
      <div class="table-head">
        <span class="mono">共 {{ total }} 条</span>
        <span class="mono">Parse Repair Queue</span>
      </div>
      <div v-if="loading" class="loading">正在加载修复队列…</div>
      <EmptyState v-else-if="!issues.length" title="暂无解析修复任务" description="系统异常或管理员主动发起的修复会出现在这里。" />
      <div v-else class="issue-list">
        <button
          v-for="issue in issues"
          :key="issueKey(issue)"
          type="button"
          class="issue-row"
          @click="openIssue(issue)"
        >
          <div>
            <div class="row-title">{{ issue.bizTitle || `${issue.bizType} #${issue.bizId}` }}</div>
            <p>{{ issue.reason || issue.issueDesc || '待人工复核解析结构。' }}</p>
          </div>
          <div class="row-meta">
            <StatusBadge :value="issue.status" />
            <span class="mono">{{ parserLabel(issue.parserType) }}</span>
            <span class="mono">{{ issue.createTime || '未记录时间' }}</span>
          </div>
        </button>
      </div>
    </section>

    <XPagination
      class="parse-repair-pager"
      :total="total"
      :page-no="query.pageNo"
      :page-size="query.pageSize"
      :page-size-options="[10, 20, 50]"
      @change="changePage"
      @size-change="changePageSize"
    />
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import EmptyState from '@/components/common/EmptyState.vue';
import PageState from '@/components/common/PageState.vue';
import StatusBadge from '@/components/common/StatusBadge.vue';
import XButton from '@/components/common/XButton.vue';
import XPagination from '@/components/common/XPagination.vue';
import XSelect from '@/components/common/XSelect.vue';
import { getParseRepairIssues, type ParseRepairIssue } from '@/api/ops';
import { resolveApiError } from '@/utils/apiError';

const router = useRouter();
const loading = ref(false);
const error = ref('');
const issues = ref<ParseRepairIssue[]>([]);
const total = ref(0);
const query = reactive({
  status: 'open',
  bizType: 'law_version',
  parserType: '',
  pageNo: 1,
  pageSize: 20
});

const statusOptions = [
  { label: '待处理', value: 'open' },
  { label: '已解决', value: 'resolved' }
];
const bizTypeOptions = [
  { label: '法规版本', value: 'law_version' }
];
const parserTypeOptions = [
  { label: '全部类型', value: '' },
  { label: '法规条款', value: 'law_article' },
  { label: '决定文本', value: 'decision_text' },
  { label: '通用章节', value: 'generic_section' }
];

watch(
  () => [query.status, query.bizType, query.parserType],
  () => {
    query.pageNo = 1;
    void loadIssues();
  }
);

onMounted(loadIssues);

async function loadIssues() {
  loading.value = true;
  error.value = '';
  try {
    const page = await getParseRepairIssues(query);
    issues.value = page.list || [];
    total.value = page.total || 0;
  } catch (err) {
    error.value = resolveApiError(err, '解析修复队列加载失败');
  } finally {
    loading.value = false;
  }
}

function changePage(pageNo: number) {
  query.pageNo = pageNo;
  void loadIssues();
}

function changePageSize(pageSize: number) {
  query.pageSize = pageSize;
  query.pageNo = 1;
  void loadIssues();
}

function openIssue(issue: ParseRepairIssue) {
  router.push({
    name: 'admin.ops.parseRepairEditor',
    params: { bizType: issue.bizType, bizId: issue.bizId },
    query: {
      repairIssueId: issue.id,
      qualityIssueId: issue.qualityIssueId,
      parserType: issue.parserType,
      layoutType: issue.layoutType,
      issueDesc: issue.reason || issue.issueDesc || ''
    }
  });
}

function issueKey(issue: ParseRepairIssue) {
  return `${issue.id || issue.qualityIssueId || 'virtual'}-${issue.bizType}-${issue.bizId}`;
}

function parserLabel(type?: string) {
  if (type === 'decision_text') return '决定文本';
  if (type === 'generic_section') return '通用章节';
  return '法规条款';
}
</script>

<style scoped>
.parse-repair-page {
  display: grid;
  gap: 18px;
}

.ops-head {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--rule);
}

.ops-head h1 {
  margin: 6px 0 4px;
  font-family: var(--serif-display);
  font-size: 26px;
  font-weight: 400;
}

.ops-head p {
  margin: 0;
  max-width: 76ch;
  color: var(--ink-3);
  font-size: 13px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
}

.toolbar label {
  display: grid;
  gap: 6px;
  min-width: 150px;
}

.queue-card {
  border: 1px solid var(--rule);
  background: var(--paper);
}

.table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--rule);
}

.loading {
  padding: 28px 16px;
  color: var(--ink-3);
  font-size: 13px;
}

.issue-list {
  display: grid;
}

.issue-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 18px;
  width: 100%;
  padding: 16px;
  border: 0;
  border-bottom: 1px solid var(--rule);
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.issue-row:hover {
  background: var(--accent-soft);
}

.row-title {
  font-family: var(--serif-display);
  font-size: 18px;
}

.issue-row p {
  margin: 6px 0 0;
  color: var(--ink-3);
  font-size: 13px;
}

.row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  color: var(--ink-3);
}

.parse-repair-pager {
  margin-top: -1px;
}
</style>
