<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  Bell,
  BookText,
  Brain,
  Building2,
  Check,
  ChevronDown,
  Copy,
  KeyRound,
  LogOut,
  Boxes,
  Network,
  BadgeCheck,
  Package,
  Server,
  ShieldAlert,
  ShieldCheck,
  Share2,
  PanelLeftClose,
  PanelLeftOpen,
  ShieldHalf,
  SlidersHorizontal,
  Users,
  Workflow,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { Avatar, Dropdown, DropdownItem, toast } from '../ui'
import ThemeToggleButton from '../components/ThemeToggleButton.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const switchingOrg = ref('')
const sidebarCollapsed = ref(false)

const title = computed(() => String(route.meta.title ?? '麒麟安全运维平台'))
const currentRoleNames = computed(
  () => authStore.user?.roles.map((role) => role.name).join(' / ') ?? '',
)
const currentOrganizationName = computed(() => authStore.currentOrganization?.name ?? '未选择组织')
const userInitial = computed(() => {
  const label = authStore.user?.displayName?.trim() || authStore.user?.username?.trim() || '用'
  return label.slice(0, 1).toUpperCase()
})
const organizations = computed(() => authStore.user?.organizations ?? [])
const hasMultipleOrgs = computed(() => organizations.value.length > 1)

const menuGroups = computed(() =>
  [
    {
      label: '工作台',
      items: [
        { index: '/hosts', label: '主机', icon: Server, permission: 'host:read' },
        { index: '/topology', label: '逻辑拓扑', icon: Network, permission: 'host:read' },
        { index: '/alerts', label: '告警', icon: Bell, permission: 'alert:read' },
        { index: '/incidents', label: '安全事件', icon: ShieldAlert, permission: 'incident:read' },
        { index: '/registry', label: '专家注册表', icon: Boxes, permission: 'registry:read' },
        { index: '/diagnostics', label: '安全试判', icon: ShieldCheck, permission: 'registry:read' },
      ],
    },
    {
      label: 'Agent 运行时',
      items: [
        { index: '/agent-runs', label: 'Agent 任务', icon: Workflow, permission: 'agent:read' },
        { index: '/agent-approvals', label: '审批中心', icon: BadgeCheck, permission: 'agent:read' },
        { index: '/agent-artifacts', label: '产物管理', icon: Package, permission: 'agent:read' },
      ],
    },
    {
      label: '知识与模型',
      items: [
        { index: '/rag-knowledge', label: 'RAG 知识库', icon: BookText, permission: 'assistant:use' },
        { index: '/rag-graph', label: '知识图谱', icon: Share2, permission: 'assistant:use' },
        { index: '/memory', label: '智能记忆', icon: Brain, permission: 'assistant:use' },
      ],
    },
    {
      label: '系统管理',
      items: [
        { index: '/users', label: '用户', icon: Users, permission: 'user:read' },
        { index: '/roles', label: '角色', icon: ShieldHalf, permission: 'role:read' },
        { index: '/permissions', label: '权限节点', icon: KeyRound, permission: 'permission:read' },
        { index: '/organizations', label: '组织', icon: Building2, permission: 'organization:read' },
        { index: '/settings', label: '系统设置', icon: SlidersHorizontal, permission: 'platform:configure' },
      ],
    },
  ]
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => authStore.hasPermission(item.permission)),
    }))
    .filter((group) => group.items.length > 0),
)

function navigate(index: string) {
  if (index !== route.path) {
    void router.push(index)
  }
}

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

async function handleOrganizationChange(organizationId: string) {
  if (!organizationId || organizationId === authStore.currentOrganizationId) return
  switchingOrg.value = organizationId
  try {
    await authStore.changeOrganization(organizationId)
    const org = authStore.user?.organizations.find((o) => o.id === organizationId)
    toast.success(`已切换到 ${org?.name ?? '该组织'}`)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '组织切换失败')
  } finally {
    switchingOrg.value = ''
  }
}

async function copyText(text: string, label: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    toast.success(`${label}已复制`)
  } catch {
    toast.error(`${label}复制失败`)
  }
}

async function handleLogout() {
  await authStore.signOut()
  await router.replace('/login')
}
</script>

<template>
  <div class="shell" :class="{ 'shell--side-collapsed': sidebarCollapsed }">
    <aside class="side">
      <div class="brand">
        <div class="brand-main">
          <div class="brand-mark"><ShieldHalf /></div>
          <div class="brand-copy">
            <div class="brand-name">麒麟安全运维</div>
            <div class="brand-sub">智能运维平台</div>
          </div>
        </div>
        <button
          type="button"
          class="side-toggle"
          :aria-label="sidebarCollapsed ? '展开导航栏' : '收起导航栏'"
          :title="sidebarCollapsed ? '展开导航栏' : '收起导航栏'"
          @click="toggleSidebar"
        >
          <PanelLeftOpen v-if="sidebarCollapsed" />
          <PanelLeftClose v-else />
        </button>
      </div>

      <nav class="nav">
        <div v-for="group in menuGroups" :key="group.label" class="nav-group">
          <div class="nav-group__label">{{ group.label }}</div>
          <button
            v-for="item in group.items"
            :key="item.index"
            type="button"
            class="nav-item"
            :class="{ on: route.path === item.index }"
            :aria-current="route.path === item.index ? 'page' : undefined"
            :title="sidebarCollapsed ? item.label : undefined"
            @click="navigate(item.index)"
          >
            <component :is="item.icon" class="nav-item__ic" />
            <span class="nav-item__label">{{ item.label }}</span>
          </button>
        </div>
      </nav>
    </aside>

    <div class="main">
      <header class="top">
        <div class="top-titles">
          <div class="top-title">{{ title }}</div>
          <div class="top-org"><Building2 class="top-org__ic" />{{ currentOrganizationName }}</div>
        </div>

        <div v-if="authStore.user" class="top-actions">
          <ThemeToggleButton />

          <Dropdown align="end">
            <template #trigger>
              <button type="button" class="user">
                <Avatar :src="authStore.user.avatarUrl || undefined" :size="32">
                  {{ userInitial }}
                </Avatar>
                <span class="user-name">{{ authStore.user.displayName }}</span>
                <ChevronDown class="user-chev" />
              </button>
            </template>

            <div class="profile">
              <!-- 用户信息头部 -->
              <div class="profile-head">
                <Avatar :src="authStore.user.avatarUrl || undefined" :size="44">
                  {{ userInitial }}
                </Avatar>
                <div class="profile-head__body">
                  <div class="profile-head__name">{{ authStore.user.displayName }}</div>
                  <div class="profile-head__sub">
                    {{ authStore.user.email || authStore.user.username }}
                  </div>
                  <div v-if="currentRoleNames" class="profile-head__role">{{ currentRoleNames }}</div>
                </div>
              </div>

              <!-- 组织切换 -->
              <div class="profile-section">
                <div class="profile-section__title">
                  {{ hasMultipleOrgs ? '切换组织' : '当前组织' }}
                </div>
                <div class="org-list">
                  <button
                    v-for="org in organizations"
                    :key="org.id"
                    type="button"
                    class="org-item"
                    :class="{
                      'org-item--current': org.id === authStore.currentOrganizationId,
                      'org-item--switching': switchingOrg === org.id,
                    }"
                    :disabled="!hasMultipleOrgs || !!switchingOrg"
                    @click="handleOrganizationChange(org.id)"
                  >
                    <Building2 class="org-item__ic" />
                    <span class="org-item__body">
                      <span class="org-item__name">{{ org.name }}</span>
                      <span class="org-item__meta num">{{ org.hostCount }} 主机 · {{ org.memberCount }} 成员</span>
                    </span>
                    <Check v-if="org.id === authStore.currentOrganizationId" class="org-item__check" />
                  </button>
                  <div v-if="organizations.length === 0" class="org-empty">未分配任何组织</div>
                </div>
              </div>

              <!-- 账号操作 -->
              <div class="profile-section">
                <div class="profile-section__title">账号</div>
                <button
                  type="button"
                  class="profile-act"
                  @click="copyText(authStore.user.username, '用户名')"
                >
                  <Copy class="profile-act__ic" />
                  <span class="profile-act__body">
                    <span>复制用户名</span>
                    <span class="profile-act__val num">{{ authStore.user.username }}</span>
                  </span>
                </button>
                <button
                  v-if="authStore.user.email"
                  type="button"
                  class="profile-act"
                  @click="copyText(authStore.user.email, '邮箱')"
                >
                  <Copy class="profile-act__ic" />
                  <span class="profile-act__body">
                    <span>复制邮箱</span>
                    <span class="profile-act__val">{{ authStore.user.email }}</span>
                  </span>
                </button>
              </div>
            </div>

            <DropdownItem divided danger @select="handleLogout">
              <LogOut />
              <span>退出登录</span>
            </DropdownItem>
          </Dropdown>
        </div>
      </header>

      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: grid;
  grid-template-columns: 244px 1fr;
  height: 100vh;
  transition: grid-template-columns 0.18s ease;
}
.shell--side-collapsed {
  grid-template-columns: 76px 1fr;
}

/* sidebar */
.side {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--sidebar);
  color: oklch(0.94 0.01 240);
  border-right: 1px solid color-mix(in oklch, var(--sidebar), white 8%);
}
.brand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 72px;
  padding: 17px 18px;
  border-bottom: 1px solid color-mix(in oklch, var(--sidebar), white 8%);
}
.brand-main {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}
.brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
  background: oklch(0.9 0.035 230);
  color: var(--sidebar-2);
  flex: none;
}
.brand-mark :deep(svg) {
  width: 18px;
  height: 18px;
}
.brand-copy {
  min-width: 0;
}
.brand-name {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}
.brand-sub {
  font-size: 11px;
  color: oklch(0.68 0.02 240);
  margin-top: 2px;
  white-space: nowrap;
}
.side-toggle {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: none;
  border: 1px solid color-mix(in oklch, var(--sidebar), white 12%);
  border-radius: var(--radius-sm);
  background: transparent;
  color: oklch(0.68 0.02 240);
  cursor: pointer;
  transition: background 0.12s, border-color 0.12s, color 0.12s;
}
.side-toggle:hover {
  background: color-mix(in oklch, var(--sidebar), white 7%);
  border-color: color-mix(in oklch, var(--sidebar), white 20%);
  color: white;
}
.side-toggle :deep(svg) {
  width: 16px;
  height: 16px;
}
.nav {
  flex: 1;
  overflow-y: auto;
  padding: 12px 11px 18px;
}
.nav-group__label {
  font-size: 11px;
  letter-spacing: 0.05em;
  color: oklch(0.59 0.02 240);
  padding: 18px 11px 8px;
}
.nav-group:first-child .nav-group__label {
  padding-top: 6px;
}
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 9px 11px;
  margin-bottom: 2px;
  border: none;
  border-radius: var(--radius);
  background: none;
  color: oklch(0.72 0.02 240);
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: background 0.12s, color 0.12s;
}
.nav-item:hover {
  background: color-mix(in oklch, var(--sidebar), white 6%);
  color: white;
}
.nav-item.on {
  background: oklch(0.9 0.035 230);
  color: var(--sidebar-2);
  font-weight: 650;
}
.nav-item__ic {
  width: 17px;
  height: 17px;
  flex: none;
}
.nav-item__label {
  white-space: nowrap;
}
.shell--side-collapsed .brand {
  flex-direction: column;
  justify-content: center;
  padding: 14px 10px;
}
.shell--side-collapsed .brand-copy {
  display: none;
}
.shell--side-collapsed .nav {
  padding: 4px 10px;
}
.shell--side-collapsed .nav-group {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid color-mix(in oklch, var(--sidebar), white 9%);
}
.shell--side-collapsed .nav-group:first-child {
  margin-top: 0;
  padding-top: 0;
  border-top: none;
}
.shell--side-collapsed .nav-group__label {
  height: 0;
  padding: 0;
  overflow: hidden;
}
.shell--side-collapsed .nav-item {
  justify-content: center;
  gap: 0;
  padding: 10px 0;
}
.shell--side-collapsed .nav-item__label {
  width: 0;
  overflow: hidden;
}

/* main */
.main {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg);
}
.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 72px;
  flex: none;
  padding: 0 34px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in oklch, var(--bg), var(--surface) 48%);
}
.top-titles {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 14px;
}
.top-title {
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -.015em;
}
.top-org {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-3);
  margin-top: 0;
  padding-left: 14px;
  border-left: 1px solid var(--border);
}
.top-org__ic {
  width: 13px;
  height: 13px;
}
.top-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}
.top-actions > * {
  flex: none;
}
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 8px 0 5px;
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: transparent;
  cursor: pointer;
  transition: background 0.12s, border-color 0.12s;
  flex: none;
}
.user:hover {
  background: var(--surface-2);
  border-color: var(--border);
}
.user-name {
  max-width: 120px;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.user-chev {
  width: 15px;
  height: 15px;
  color: var(--text-3);
  flex: none;
}

/* Profile dropdown */
.profile {
  width: 280px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 4px 2px;
}
.profile-head {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 8px 8px 12px;
  border-bottom: 1px solid var(--border);
}
.profile-head__body {
  min-width: 0;
  flex: 1;
}
.profile-head__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.profile-head__sub {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 1px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.profile-head__role {
  font-size: 11.5px;
  color: var(--accent);
  margin-top: 3px;
}

.profile-section {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 2px;
}
.profile-section__title {
  font-size: 10.5px;
  letter-spacing: 0.06em;
  color: var(--text-3);
  text-transform: uppercase;
  padding: 6px 8px 4px;
}

.org-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.org-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 9px;
  border: none;
  background: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  text-align: left;
  color: var(--text-2);
  transition: background 0.12s, color 0.12s;
}
.org-item:not(:disabled):hover {
  background: var(--surface-2);
  color: var(--text);
}
.org-item:disabled {
  cursor: default;
}
.org-item--current {
  color: var(--text);
}
.org-item--switching {
  opacity: 0.5;
}
.org-item__ic {
  width: 16px;
  height: 16px;
  color: var(--text-3);
  flex: none;
}
.org-item--current .org-item__ic {
  color: var(--accent);
}
.org-item__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.org-item__name {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.org-item__meta {
  font-size: 11px;
  color: var(--text-3);
}
.org-item__check {
  width: 15px;
  height: 15px;
  color: var(--accent);
  flex: none;
}
.org-empty {
  font-size: 12.5px;
  color: var(--text-3);
  padding: 8px;
}

.profile-act {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 7px 9px;
  border: none;
  background: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  text-align: left;
  color: var(--text-2);
  transition: background 0.12s, color 0.12s;
}
.profile-act:hover {
  background: var(--surface-2);
  color: var(--text);
}
.profile-act__ic {
  width: 15px;
  height: 15px;
  color: var(--text-3);
  flex: none;
}
.profile-act:hover .profile-act__ic {
  color: var(--accent);
}
.profile-act__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.profile-act__body > span:first-child {
  font-size: 13px;
}
.profile-act__val {
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.content {
  flex: 1;
  overflow-y: auto;
}

@media (max-width: 820px) {
  .shell { grid-template-columns: 76px 1fr; }
  .brand { flex-direction: column; justify-content: center; padding: 14px 10px; }
  .brand-copy { display: none; }
  .nav { padding-inline: 10px; }
  .nav-group__label { height: 0; padding: 0; overflow: hidden; }
  .nav-item { justify-content: center; gap: 0; padding: 10px 0; }
  .nav-item__label { width: 0; overflow: hidden; }
  .top { padding-inline: 20px; }
  .top-org { display: none; }
}
</style>
