<template>
  <div class="shell">
    <aside class="rail">
      <RouterLink class="brand" to="/app/home" title="X-KU">
        <img class="brand-logo" :src="brandLogoSrc" alt="X-KU" />
        <span class="brand-divider" aria-hidden="true"></span>
        <span class="brand-text">{{ isAdminMode ? '运维后台' : '法规智询' }}</span>
      </RouterLink>

      <nav class="rail-nav scroll-gutter">
        <div v-for="group in navGroups" :key="group.kicker" class="rail-group">
          <p class="rail-kicker">{{ group.kicker }}</p>
          <component
            :is="item.disabled || item.locked ? 'button' : RouterLink"
            v-for="item in group.items"
            :key="item.label"
            class="rail-link"
            :class="{ active: item.match(route.path), disabled: item.disabled, locked: item.locked }"
            :to="item.disabled || item.locked ? undefined : item.to"
            :type="item.disabled || item.locked ? 'button' : undefined"
            :title="item.disabled ? (item.disabledHint || item.label) : item.locked ? `登录后可用 · ${item.label}` : item.label"
            @click="item.disabled ? handleDisabledNav(item) : item.locked ? goToLogin(item.to) : undefined"
          >
            <component :is="item.icon" :size="17" />
            <span class="rail-label">{{ item.label }}</span>
            <span v-if="item.badge" class="rail-badge">{{ item.badge > 99 ? '99+' : item.badge }}</span>
          </component>
        </div>
      </nav>

      <div class="rail-foot">
        <button v-if="!auth.isAuthed" class="rail-login" type="button" @click="goToLogin()">
          <LogIn :size="16" />
          <span>登录</span>
        </button>
        <div v-else class="rail-user">
          <button class="rail-user-main" type="button" title="账号设置" @click="router.push('/app/settings')">
            <img class="avatar" :src="railAvatarSrc" alt="" />
            <span class="rail-user-meta">
              <strong>{{ auth.session?.username || '未登录' }}</strong>
              <!-- 多租户时恢复：当前对外隐藏租户码 -->
              <!-- <small>{{ auth.session?.tenantCode || 'PUBLIC' }}</small> -->
            </span>
          </button>
          <button class="rail-logout" title="退出登录" @click="logout">
            <LogOut :size="16" />
          </button>
        </div>
      </div>
    </aside>

    <header class="topbar">
      <button class="menu-btn" type="button" aria-label="打开导航" @click="navOpen = true">
        <Menu :size="18" />
      </button>
      <div class="crumb">
        <span class="section-kicker">§</span>
        <strong>{{ activeLabel }}</strong>
      </div>
      <form class="global-search" @submit.prevent="submitGlobalSearch">
        <Search :size="15" />
        <input
          ref="searchInputRef"
          v-model="searchKeyword"
          class="global-search-input"
          type="search"
          placeholder="检索法规"
          aria-label="检索法规"
        />
        <kbd>{{ commandHint }}</kbd>
      </form>
      <div class="topbar-right">
        <button v-if="auth.isAdmin" class="mode-switch" type="button" @click="toggleAdminMode">
          <ShieldCheck :size="14" />
          <span>{{ isAdminMode ? '返回前台' : '进入后台' }}</span>
        </button>
      </div>
    </header>

    <main class="main">
      <RouterView v-slot="{ Component }">
        <Suspense v-if="Component">
          <component :is="Component" />
          <template #fallback>
            <PageSkeleton variant="spinner" :status-text="routeLoadingText" />
          </template>
        </Suspense>
      </RouterView>
    </main>

    <XModal :open="navOpen" kicker="§ Nav" title="导航" description="选择要进入的页面。" max-width="480px" @update:open="navOpen = $event">
      <nav class="mobile-nav">
        <component
          :is="item.disabled ? 'button' : RouterLink"
          v-for="item in navItems"
          :key="item.label"
          class="mobile-nav-link"
          :class="{ disabled: item.disabled }"
          :to="item.disabled ? undefined : item.to"
          :type="item.disabled ? 'button' : undefined"
          @click="onMobileNav(item)"
        >
          <component :is="item.icon" :size="16" />
          <span>{{ item.label }}</span>
        </component>
      </nav>
    </XModal>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter, type RouteLocationRaw } from 'vue-router';
import {
  Bell,
  BookMarked,
  Bot,
  Database,
  FileCode2,
  FileSearch,
  FolderTree,
  Home,
  KeyRound,
  Layers3,
  LayoutDashboard,
  Clock,
  LogIn,
  LogOut,
  Menu,
  MessageSquare,
  RefreshCcw,
  Rss,
  Search,
  ServerCog,
  Settings2,
  ShieldCheck,
  UploadCloud,
  User,
  Users,
  Workflow
} from '@lucide/vue';
import PageSkeleton from '@/components/common/PageSkeleton.vue';
import XModal from '@/components/common/XModal.vue';
import brandLogoSrc from '../../x-ku-clear.png';
import defaultAvatarSrc from '../../law_avatar.png';
import { useConfirm } from '@/composables/useConfirm';
import { useToast } from '@/composables/useToast';
import { lawSearchTo } from '@/router/navigation';
import { getNotificationUnreadCount, getSubscriptionMatchUnreadCount } from '@/api/workspace';
import { useAuthStore } from '@/stores/auth';
import { resolveAvatarUrl } from '@/api/account';
import { routeLoadingLabel } from '@/utils/routeLoadingLabel';

interface NavItem {
  label: string;
  to: RouteLocationRaw | '';
  icon: object;
  match: (path: string) => boolean;
  disabled?: boolean;
  disabledHint?: string;
  badge?: number;
  /** 该入口需登录才能访问；未登录时归入「登录后可用」分组并置灰。 */
  requiresAuth?: boolean;
  /** 运行时标记：未登录态下渲染为锁态（点击引导登录）。 */
  locked?: boolean;
}

interface NavGroup {
  kicker: string;
  items: NavItem[];
}

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const toast = useToast();
const { confirm } = useConfirm();
const navOpen = ref(false);
const unreadAlerts = ref(0);
const unreadMessages = ref(0);
const searchKeyword = ref('');
const searchInputRef = ref<HTMLInputElement | null>(null);
const isAdminMode = computed(() => route.path.startsWith('/admin'));
/** 侧栏头像：用户已上传则取其头像流，否则回退默认头像。 */
const railAvatarSrc = computed(() => resolveAvatarUrl(auth.profile?.avatarUrl) || defaultAvatarSrc);
const commandHint = computed(() => (window.navigator.platform.toLowerCase().includes('mac') ? '⌘K' : 'Ctrl+K'));

watch(
  () => route.query.keyword,
  (keyword) => {
    searchKeyword.value = String(keyword || '');
  },
  { immediate: true }
);

const userNav = computed<NavGroup[]>(() => [
  {
    kicker: '概览',
    items: [
      { label: '首页', to: { name: 'app.home' }, icon: Home, match: (path) => path === '/app/home' },
      { label: '工作台', to: { name: 'app.workbench' }, icon: LayoutDashboard, match: (path) => path === '/app/workbench', requiresAuth: true }
    ]
  },
  {
    kicker: '法规',
    items: [
      { label: '法规检索', to: { name: 'law.search' }, icon: FileSearch, match: (path) => path === '/app/laws/search', requiresAuth: true }
    ]
  },
  {
    kicker: '智能',
    items: [{ label: '法规智询', to: { name: 'ai.chat' }, icon: Bot, match: (path) => path.startsWith('/app/ai/chat'), requiresAuth: true }]
  },
  {
    kicker: '我的',
    items: [
      { label: '我的空间', to: { name: 'app.mySpace', params: { resource: 'favorites' } }, icon: User, match: (path) => path === '/app/me' || path.startsWith('/app/me/'), requiresAuth: true },
      { label: '订阅规则', to: { name: 'app.subscriptions' }, icon: Rss, match: (path) => path.startsWith('/app/subscriptions'), requiresAuth: true },
      { label: '预警中心', to: { name: 'app.alerts' }, icon: Bell, match: (path) => path.startsWith('/app/alerts'), badge: unreadAlerts.value, requiresAuth: true },
      { label: '消息中心', to: { name: 'app.messages' }, icon: MessageSquare, match: (path) => path.startsWith('/app/messages'), badge: unreadMessages.value, requiresAuth: true }
    ]
  }
]);

const adminNav: NavGroup[] = [
  {
    kicker: '概览',
    items: [
      { label: '运维总览', to: { name: 'admin.overview' }, icon: LayoutDashboard, match: (path) => path.startsWith('/admin/overview') }
    ]
  },
  {
    kicker: '法规数据',
    items: [
      { label: '法规主数据', to: { name: 'admin.laws' }, icon: Database, match: (path) => path.startsWith('/admin/laws') },
      { label: '法规版本', to: { name: 'admin.lawVersions' }, icon: Layers3, match: (path) => path.startsWith('/admin/law-versions') },
      { label: '法规条款', to: { name: 'admin.lawArticles' }, icon: FileCode2, match: (path) => path.startsWith('/admin/law-articles') },
      { label: '法规分类', to: { name: 'admin.lawCategories' }, icon: FolderTree, match: (path) => path.startsWith('/admin/law-categories') },
      { label: '法规关系', to: { name: 'admin.lawRelations' }, icon: BookMarked, match: (path) => path.startsWith('/admin/law-relations') }
    ]
  },
  {
    kicker: '数据运维',
    items: [
      { label: '上传接入', to: { name: 'admin.ingest' }, icon: UploadCloud, match: (path) => path.startsWith('/admin/ingest') },
      { label: '采集运维', to: { name: 'admin.ops.collect' }, icon: RefreshCcw, match: (path) => path.startsWith('/admin/ops/collect') },
      { label: '处理管线', to: { name: 'admin.ops.process' }, icon: Workflow, match: (path) => path.startsWith('/admin/ops/process') },
      { label: '索引任务', to: { name: 'admin.ops.index' }, icon: ServerCog, match: (path) => path.startsWith('/admin/ops/index') },
      { label: '数据治理', to: { name: 'admin.ops.governance' }, icon: ShieldCheck, match: (path) => path.startsWith('/admin/ops/governance') },
      { label: '解析修复', to: { name: 'admin.ops.parseRepair' }, icon: FileCode2, match: (path) => path.startsWith('/admin/ops/parse-repair') },
      { label: '定时任务', to: { name: 'admin.ops.scheduler' }, icon: Clock, match: (path) => path.startsWith('/admin/ops/scheduler') }
    ]
  },
  {
    kicker: '系统管理',
    items: [
      { label: '用户管理', to: { name: 'admin.users' }, icon: Users, match: (path) => path.startsWith('/admin/users') },
      { label: '角色管理', to: { name: 'admin.roles' }, icon: ShieldCheck, match: (path) => path.startsWith('/admin/roles') },
      { label: '权限资源', to: { name: 'admin.permissions' }, icon: KeyRound, match: (path) => path.startsWith('/admin/permissions') },
      { label: '字典管理', to: { name: 'admin.dicts' }, icon: Settings2, match: (path) => path.startsWith('/admin/dicts') || path.startsWith('/admin/dict-types') || path.startsWith('/admin/dict-data') },
      { label: '通知管理', to: { name: 'admin.notifications' }, icon: Bell, match: (path) => path.startsWith('/admin/notifications') }
    ]
  }
];

const navGroups = computed<NavGroup[]>(() => {
  if (isAdminMode.value) return adminNav;
  const groups = userNav.value;
  if (auth.isAuthed) return groups;
  // 访客态：各分组只保留公开项；需登录项统一收进尾部「登录后可用」分组并置灰。
  const publicGroups = groups
    .map((group) => ({ ...group, items: group.items.filter((item) => !item.requiresAuth) }))
    .filter((group) => group.items.length > 0);
  const lockedItems = groups
    .flatMap((group) => group.items)
    .filter((item) => item.requiresAuth)
    .map((item) => ({ ...item, locked: true, badge: undefined }));
  if (lockedItems.length) publicGroups.push({ kicker: '登录后可用', items: lockedItems });
  return publicGroups;
});
const navItems = computed(() => navGroups.value.flatMap((group) => group.items));

function toggleAdminMode() {
  router.push(isAdminMode.value ? { name: 'app.home' } : { name: 'admin.overview' });
}
const active = computed(() => navItems.value.find((item) => item.match(route.path)) || navItems.value[0]);
const activeLabel = computed(() => active.value.label);
const routeLoadingText = computed(() => routeLoadingLabel(route.path));
function submitGlobalSearch() {
  router.push(lawSearchTo(searchKeyword.value.trim() || undefined));
}

function handleKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    searchInputRef.value?.focus();
    searchInputRef.value?.select();
  }
}

function handleDisabledNav(item: NavItem) {
  toast.info(item.disabledHint || '该入口暂不可用。');
}

function goToLogin(redirectTo?: RouteLocationRaw) {
  const redirect = redirectTo ? router.resolve(redirectTo).fullPath : route.fullPath;
  router.push({ name: 'login', query: { redirect } });
}

function onMobileNav(item: NavItem) {
  if (item.disabled) {
    handleDisabledNav(item);
    return;
  }
  if (item.locked) {
    navOpen.value = false;
    goToLogin(item.to);
    return;
  }
  navOpen.value = false;
}

async function refreshBadges() {
  if (!auth.session) return;
  try {
    const [alerts, messages] = await Promise.all([
      getSubscriptionMatchUnreadCount(),
      getNotificationUnreadCount()
    ]);
    unreadAlerts.value = alerts;
    unreadMessages.value = messages;
  } catch {
    /* 角标失败不阻断导航 */
  }
}

// 进入预警/消息中心标记已读后会改变未读数，离开这些页面时刷新角标。
watch(
  () => route.path,
  (path, prev) => {
    if (prev && (prev.startsWith('/app/alerts') || prev.startsWith('/app/messages'))) {
      refreshBadges();
    }
  }
);

onMounted(() => {
  window.addEventListener('keydown', handleKeydown);
  refreshBadges();
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown);
});

async function logout() {
  const confirmed = await confirm({
    title: '确认退出登录？',
    message: '',
    confirmText: '退出登录'
  });
  if (!confirmed) return;

  await auth.logout();
  router.push('/login');
}
</script>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: var(--rail-w) 1fr;
  grid-template-rows: var(--topbar-h) 1fr;
  grid-template-areas:
    "rail topbar"
    "rail main";
  height: 100%;
  overflow: hidden;
  background: var(--paper);
}

.rail {
  grid-area: rail;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--rule);
  background: var(--paper-2);
}

.brand {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: center;
  height: var(--topbar-h);
  padding: 0 12px;
  border-bottom: 1px solid var(--rule);
  color: var(--ink);
  text-decoration: none;
}

.brand-logo {
  display: block;
  width: auto;
  height: 24px;
  object-fit: contain;
  flex-shrink: 0;
}

.brand-divider {
  width: 1px;
  height: 20px;
  background: var(--rule-strong);
  flex-shrink: 0;
}

.brand-text {
  color: var(--ink-2);
  font-family: var(--sans);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.04em;
  line-height: 1;
  white-space: nowrap;
  flex-shrink: 0;
}

.rail-nav {
  flex: 1;
  min-height: 0;
  padding: 10px 12px 20px;
  overflow-y: auto;
}

.rail-group + .rail-group {
  margin-top: 14px;
}

.rail-kicker {
  margin: 0 0 4px;
  padding: 0 10px;
  color: var(--muted-2);
  font-size: var(--font-xxs);
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.rail-link {
  position: relative;
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  height: var(--control-h);
  padding: 0 10px;
  border-radius: 4px;
  background: transparent;
  color: var(--ink-3);
  font-size: 13px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: background 0.14s var(--ease), color 0.14s var(--ease);
}

.rail-label {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.rail-badge {
  display: inline-grid;
  place-items: center;
  flex-shrink: 0;
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--accent);
  color: var(--paper);
  font-family: var(--mono);
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
}

.rail-link :deep(svg) {
  flex-shrink: 0;
  color: var(--muted);
  transition: color 0.14s var(--ease);
}

.rail-link:hover {
  background: var(--paper-sunk);
  color: var(--ink);
}

.rail-link:hover :deep(svg) {
  color: var(--ink-2);
}

.rail-link.active {
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-weight: 600;
}

.rail-link.active :deep(svg) {
  color: var(--accent);
}

.rail-link.active::before {
  content: '';
  position: absolute;
  top: 7px;
  bottom: 7px;
  left: -12px;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--accent);
}

.rail-link.disabled {
  color: var(--muted-2);
  cursor: not-allowed;
}

.rail-link.disabled :deep(svg) {
  color: var(--muted-2);
}

.rail-link.disabled:hover {
  background: transparent;
  color: var(--muted-2);
}

.rail-link.locked {
  color: var(--muted-2);
  cursor: pointer;
}

.rail-link.locked :deep(svg) {
  color: var(--muted-2);
}

.rail-link.locked:hover {
  background: var(--paper-sunk);
  color: var(--ink-3);
}

.rail-link.locked:hover :deep(svg) {
  color: var(--muted);
}

.rail-foot {
  padding: 10px 12px;
  border-top: 1px solid var(--rule);
}

.rail-user {
  display: flex;
  gap: 9px;
  align-items: center;
  padding: 4px;
}

.rail-user-main {
  display: flex;
  gap: 9px;
  align-items: center;
  flex: 1;
  min-width: 0;
  padding: 3px 4px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s var(--ease);
}

.rail-user-main:hover {
  background: var(--accent-soft);
}

.rail-user-meta {
  display: grid;
  gap: 1px;
  min-width: 0;
  flex: 1;
  line-height: 1.2;
}

.rail-user-meta strong {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.rail-user-meta small {
  color: var(--muted);
  font-family: var(--mono);
  font-size: var(--font-xxs);
}

.rail-logout {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.rail-logout:hover {
  background: var(--rose-soft);
  color: var(--rose);
}

.rail-login {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: var(--control-h);
  border: 1px solid var(--ink);
  border-radius: 4px;
  background: var(--ink);
  color: var(--paper);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.14s var(--ease), color 0.14s var(--ease);
}

.rail-login :deep(svg) {
  flex-shrink: 0;
}

.rail-login:hover {
  background: var(--ink-2);
  border-color: var(--ink-2);
}

.avatar {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  background: var(--paper);
}

.menu-btn {
  display: none;
  place-items: center;
  width: var(--control-h);
  height: var(--control-h);
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper);
  color: var(--ink-2);
  cursor: pointer;
}

.topbar {
  grid-area: topbar;
  display: flex;
  gap: 22px;
  align-items: center;
  min-width: 0;
  padding: 0 22px;
  border-bottom: 1px solid var(--rule);
  background: var(--paper);
}

.crumb {
  display: flex;
  gap: 12px;
  align-items: center;
  white-space: nowrap;
}

.crumb strong {
  font-family: var(--serif-body);
  font-size: 18px;
  font-weight: 500;
}

.global-search {
  display: flex;
  flex: 1;
  gap: 9px;
  align-items: center;
  max-width: 520px;
  height: var(--control-h);
  padding: 0 12px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper-2);
  color: var(--muted);
  transition: border-color 0.15s var(--ease), background 0.15s var(--ease);
}

.global-search:hover {
  border-color: var(--rule-strong);
}

.global-search:focus-within {
  border-color: var(--ink);
  background: var(--paper-card);
}

.global-search-input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-size: 13px;
  outline: none;
}

.global-search-input::placeholder {
  color: var(--muted);
}

.global-search :deep(svg) {
  flex-shrink: 0;
  color: var(--muted);
}

.global-search kbd {
  margin-left: auto;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--paper-sunk);
  color: var(--muted);
  font-family: var(--mono);
  font-size: var(--font-xxs);
}

.topbar-right {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-left: auto;
}

.mode-switch {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: var(--control-h);
  padding: 0 12px;
  border: 1px solid var(--rule-strong);
  border-radius: 4px;
  background: var(--paper);
  color: var(--ink-2);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.mode-switch:hover {
  background: var(--ink);
  color: var(--paper);
}

.main {
  grid-area: main;
  min-width: 0;
  overflow: auto;
  background: var(--paper);
}

.mobile-nav {
  display: grid;
  gap: 8px;
}

.mobile-nav-link {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid var(--rule);
  border-radius: 4px;
  background: var(--paper);
  color: var(--ink-2);
  font-size: 14px;
  text-decoration: none;
  cursor: pointer;
}

.mobile-nav-link.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 860px) {
  .shell {
    grid-template-columns: 1fr;
    grid-template-rows: var(--topbar-h) 1fr;
    grid-template-areas:
      "topbar"
      "main";
  }

  .rail {
    display: none;
  }

  .menu-btn {
    display: grid;
  }

  .topbar {
    padding: 0 14px;
  }
}
</style>
