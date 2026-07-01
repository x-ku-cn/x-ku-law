import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { getSession } from '@/api/token';
import { useAuthStore } from '@/stores/auth';

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/app/home' },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue'), meta: { public: true } },
  {
    path: '/app',
    component: () => import('@/layouts/AppShell.vue'),
    children: [
      { path: '', redirect: '/app/home' },
      { path: 'home', name: 'app.home', component: () => import('@/views/app/PortalView.vue'), meta: { public: true, skeletonLayout: 'generic' } },
      { path: 'workbench', name: 'app.workbench', component: () => import('@/views/app/HomeView.vue'), meta: { skeletonLayout: 'home' } },
      { path: 'laws/search', name: 'law.search', component: () => import('@/views/app/SearchView.vue'), meta: { skeletonLayout: 'search' } },
      { path: 'laws/:documentId', name: 'law.detail', component: () => import('@/views/app/LawDetailView.vue'), meta: { skeletonLayout: 'law' } },
      { path: 'laws/:documentId/compare', name: 'law.compare', component: () => import('@/views/app/CompareView.vue'), meta: { skeletonLayout: 'compare' } },
      { path: 'ai/chat', name: 'ai.chat', component: () => import('@/views/app/AiChatView.vue'), meta: { skeletonLayout: 'ai' } },
      { path: 'subscriptions', name: 'app.subscriptions', component: () => import('@/views/app/subscriptions/SubscriptionRulesView.vue'), meta: { skeletonLayout: 'table' } },
      { path: 'alerts', name: 'app.alerts', component: () => import('@/views/app/alerts/AlertCenterView.vue'), meta: { skeletonLayout: 'feed' } },
      { path: 'messages', name: 'app.messages', component: () => import('@/views/app/messages/MessageCenterView.vue'), meta: { skeletonLayout: 'feed' } },
      { path: 'settings', name: 'app.settings', component: () => import('@/views/app/AccountSettingsView.vue'), meta: { skeletonLayout: 'generic' } },
      // 「我的空间」：收藏夹 / 保存检索 / 我的反馈 合并为一个带标签页的壳，:resource 直接对应 resourceConfigs 的 key。
      { path: 'me', redirect: '/app/me/favorites' },
      { path: 'me/:resource', name: 'app.mySpace', component: () => import('@/views/app/MySpaceView.vue'), meta: { skeletonLayout: 'table' } },
      // 旧入口保留命名并重定向到新标签，避免既有跳转（收藏按钮、命令面板、工作台链接）失效。
      { path: 'saved-searches', name: 'app.savedSearches', redirect: '/app/me/savedSearches' },
      { path: 'favorites', name: 'app.favorites', redirect: '/app/me/favorites' },
      { path: 'feedback', name: 'app.feedback', redirect: '/app/me/feedbacks' }
    ]
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AppShell.vue'),
    meta: { requiresAdmin: true },
    children: [
      { path: '', redirect: '/admin/overview' },
      { path: 'overview', name: 'admin.overview', component: () => import('@/views/admin/ops/OverviewView.vue') },
      { path: 'laws', name: 'admin.laws', component: () => import('@/views/ResourceListView.vue'), meta: { resource: 'lawDocuments', skeletonLayout: 'table' } },
      { path: 'law-versions', name: 'admin.lawVersions', component: () => import('@/views/ResourceListView.vue'), meta: { resource: 'lawVersions', skeletonLayout: 'table' } },
      { path: 'law-articles', name: 'admin.lawArticles', component: () => import('@/views/ResourceListView.vue'), meta: { resource: 'lawArticles', skeletonLayout: 'table' } },
      { path: 'law-categories', name: 'admin.lawCategories', component: () => import('@/views/ResourceListView.vue'), meta: { resource: 'lawCategories', skeletonLayout: 'table' } },
      { path: 'law-relations', name: 'admin.lawRelations', component: () => import('@/views/ResourceListView.vue'), meta: { resource: 'lawRelations', skeletonLayout: 'table' } },
      { path: 'users', name: 'admin.users', component: () => import('@/views/admin/system/UserManageView.vue'), meta: { skeletonLayout: 'table' } },
      { path: 'roles', name: 'admin.roles', component: () => import('@/views/admin/system/RoleManageView.vue'), meta: { skeletonLayout: 'table' } },
      { path: 'permissions', name: 'admin.permissions', component: () => import('@/views/admin/system/PermissionManageView.vue'), meta: { skeletonLayout: 'table' } },
      { path: 'dicts', name: 'admin.dicts', component: () => import('@/views/admin/DictManageView.vue'), meta: { skeletonLayout: 'table' } },
      { path: 'dict-types', name: 'admin.dictTypes', redirect: '/admin/dicts' },
      { path: 'dict-data', name: 'admin.dictData', redirect: '/admin/dicts' },
      { path: 'notifications', name: 'admin.notifications', component: () => import('@/views/admin/system/NotificationManageView.vue'), meta: { skeletonLayout: 'table' } },
      { path: 'ingest', name: 'admin.ingest', component: () => import('@/views/admin/IngestView.vue') },
      { path: 'ops/collect', name: 'admin.ops.collect', component: () => import('@/views/admin/ops/CollectOpsView.vue') },
      { path: 'ops/process-tasks', name: 'admin.ops.process', component: () => import('@/views/admin/ops/ProcessTaskView.vue') },
      { path: 'ops/index-tasks', name: 'admin.ops.index', component: () => import('@/views/admin/ops/IndexTaskView.vue') },
      { path: 'ops/governance', name: 'admin.ops.governance', component: () => import('@/views/admin/ops/GovernanceView.vue') },
      { path: 'ops/parse-repair', name: 'admin.ops.parseRepair', component: () => import('@/views/admin/ops/ParseRepairView.vue'), meta: { skeletonLayout: 'table' } },
      { path: 'ops/parse-repair/:bizType/:bizId', name: 'admin.ops.parseRepairEditor', component: () => import('@/views/admin/ops/ParseRepairEditor.vue'), meta: { skeletonLayout: 'generic' } },
      { path: 'ops/scheduler', name: 'admin.ops.scheduler', component: () => import('@/views/admin/ops/SchedulerConfigView.vue') }
    ]
  },
  { path: '/403', name: 'error.403', component: () => import('@/views/error/ErrorView.vue'), meta: { public: true, code: 403 } },
  { path: '/500', name: 'error.500', component: () => import('@/views/error/ErrorView.vue'), meta: { public: true, code: 500 } },
  { path: '/session-expired', name: 'session-expired', component: () => import('@/views/error/SessionExpiredView.vue'), meta: { public: true } },
  { path: '/:pathMatch(.*)*', name: 'error.404', component: () => import('@/views/error/ErrorView.vue'), meta: { public: true, code: 404 } }
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
});

router.beforeEach(async (to) => {
  if (to.meta.public) return true;

  if (!getSession()) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }

  const auth = useAuthStore();
  if (!auth.profile) {
    await auth.ensureProfile();
  }

  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'error.403' };
  }

  return true;
});

export default router;
