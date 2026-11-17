import { createRouter, createWebHistory } from 'vue-router'
import { pinia } from '../stores'
import { useAuthStore } from '../stores/auth'

import AdminLayout from '../layouts/AdminLayout.vue'
import LoginView from '../views/LoginView.vue'
import ChangePasswordView from '../views/ChangePasswordView.vue'
import HostView from '../views/HostView.vue'
import HostDetailView from '../views/HostDetailView.vue'
import AlertView from '../views/AlertView.vue'
import IncidentView from '../views/IncidentView.vue'
import RegistryView from '../views/RegistryView.vue'
import DiagnosticsView from '../views/DiagnosticsView.vue'
import PlatformLlmConfigView from '../views/PlatformLlmConfigView.vue'
import RagFileDetailView from '../views/RagFileDetailView.vue'
import RagKnowledgeView from '../views/RagKnowledgeView.vue'
import MemoryView from '../views/MemoryView.vue'
import SystemSettingsView from '../views/SystemSettingsView.vue'
import RagSettingsView from '../views/RagSettingsView.vue'
import UserManagementView from '../views/UserManagementView.vue'
import OrganizationManagementView from '../views/OrganizationManagementView.vue'
import RoleManagementView from '../views/RoleManagementView.vue'
import PermissionManagementView from '../views/PermissionManagementView.vue'
import AgentRunView from '../views/AgentRunView.vue'
import AgentWorkspaceView from '../views/AgentWorkspaceView.vue'
import AgentRunTimelineView from '../views/AgentRunTimelineView.vue'
import AgentApprovalView from '../views/AgentApprovalView.vue'
import AgentArtifactView from '../views/AgentArtifactView.vue'

// Knowledge graph view pulls in echarts; lazy-load to keep it out of the main bundle.
const KnowledgeGraphView = () => import('../views/KnowledgeGraphView.vue')
const TopologyView = () => import('../views/TopologyView.vue')

const routes = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录' },
  },
  {
    path: '/change-password',
    name: 'change-password',
    component: ChangePasswordView,
    meta: { title: '修改初始密码' },
  },
  {
    // 全屏 Agent 对话工作台：独立顶层路由，不套 AdminLayout（无左侧管理菜单）。
    path: '/workspace',
    name: 'agent-workspace',
    component: AgentWorkspaceView,
    meta: { title: 'Agent 工作台', permission: 'agent:read' },
  },
  {
    path: '/',
    component: AdminLayout,
    redirect: '/agent-runs',
    children: [
      { path: 'hosts', name: 'hosts', component: HostView, meta: { title: '主机', permission: 'host:read' } },
      { path: 'hosts/:hostname', name: 'host-detail', component: HostDetailView, meta: { title: '主机详情', permission: 'host:read' } },
      { path: 'topology', name: 'topology', component: TopologyView, meta: { title: '逻辑拓扑', permission: 'host:read' } },
      { path: 'alerts', name: 'alerts', component: AlertView, meta: { title: '告警', permission: 'alert:read' } },
      { path: 'incidents', name: 'incidents', component: IncidentView, meta: { title: '安全事件', permission: 'incident:read' } },
      { path: 'agent-runs', name: 'agent-runs', component: AgentRunView, meta: { title: 'Agent 任务', permission: 'agent:read' } },
      { path: 'agent-runs/:runId', name: 'agent-run-timeline', component: AgentRunTimelineView, meta: { title: '任务时间线', permission: 'agent:read' } },
      { path: 'agent-approvals', name: 'agent-approvals', component: AgentApprovalView, meta: { title: '审批中心', permission: 'agent:read' } },
      { path: 'agent-artifacts', name: 'agent-artifacts', component: AgentArtifactView, meta: { title: '产物管理', permission: 'agent:read' } },
      { path: 'registry', name: 'registry', component: RegistryView, meta: { title: '专家注册表', permission: 'registry:read' } },
      // 安全试判（原「诊断工具」）：审批令牌绑定、ABAC / Prompt 注入试判、Agent IAM 授权判定的
      // 可视化入口——安全护栏的唯一可读界面，因此进侧边栏与 first-allowed。权限仍为 registry:read，
      // 无该权限的角色在侧边栏看不到、直达也会被路由守卫弹回（未放宽任何权限）。
      { path: 'diagnostics', name: 'diagnostics', component: DiagnosticsView, meta: { title: '安全试判', permission: 'registry:read' } },
      { path: 'users', name: 'users', component: UserManagementView, meta: { title: '用户', permission: 'user:read' } },
      { path: 'roles', name: 'roles', component: RoleManagementView, meta: { title: '角色', permission: 'role:read' } },
      { path: 'permissions', name: 'permissions', component: PermissionManagementView, meta: { title: '权限节点', permission: 'permission:read' } },
      { path: 'organizations', name: 'organizations', component: OrganizationManagementView, meta: { title: '组织', permission: 'organization:read' } },
      { path: 'platform-llm', name: 'platform-llm', component: PlatformLlmConfigView, meta: { title: '模型配置', permission: 'platform:configure' } },
      { path: 'settings', name: 'settings', component: SystemSettingsView, meta: { title: '系统设置', permission: 'platform:configure' } },
      // 原路由保留：老书签与深链不失效，内容与「系统设置」内的同名 Tab 一致
      { path: 'rag-settings', name: 'rag-settings', component: RagSettingsView, meta: { title: 'RAG 设置', permission: 'platform:configure' } },
      { path: 'rag-knowledge', name: 'rag-knowledge', component: RagKnowledgeView, meta: { title: '知识库', permission: 'assistant:use' } },
      { path: 'rag-knowledge/file', name: 'rag-file', component: RagFileDetailView, meta: { title: '资料详情', permission: 'assistant:use' } },
      { path: 'rag-graph', name: 'rag-graph', component: KnowledgeGraphView, meta: { title: '知识图谱', permission: 'assistant:use' } },
      { path: 'memory', name: 'memory', component: MemoryView, meta: { title: '智能记忆', permission: 'assistant:use' } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

function resolveFirstAllowedPath(authStore: ReturnType<typeof useAuthStore>) {
  const candidates = [
    { path: '/agent-runs', permission: 'agent:read' },
    { path: '/hosts', permission: 'host:read' },
    { path: '/topology', permission: 'host:read' },
    { path: '/alerts', permission: 'alert:read' },
    { path: '/incidents', permission: 'incident:read' },
    { path: '/agent-approvals', permission: 'agent:read' },
    { path: '/agent-artifacts', permission: 'agent:read' },
    { path: '/registry', permission: 'registry:read' },
    { path: '/diagnostics', permission: 'registry:read' },
    { path: '/users', permission: 'user:read' },
    { path: '/roles', permission: 'role:read' },
    { path: '/permissions', permission: 'permission:read' },
    { path: '/organizations', permission: 'organization:read' },
    { path: '/platform-llm', permission: 'platform:configure' },
    { path: '/settings', permission: 'platform:configure' },
    { path: '/rag-settings', permission: 'platform:configure' },
    { path: '/rag-knowledge', permission: 'assistant:use' },
    { path: '/rag-knowledge/file', permission: 'assistant:use' },
    { path: '/rag-graph', permission: 'assistant:use' },
    { path: '/memory', permission: 'assistant:use' },
  ]
  return candidates.find((item) => authStore.hasPermission(item.permission))?.path ?? '/login'
}

// Synchronous guard — auth is resolved in main.ts before mount, so no await here.
router.beforeEach((to) => {
  const authStore = useAuthStore(pinia)

  if (to.name === 'login') {
    if (authStore.isAuthenticated) {
      if (authStore.mustChangePassword) {
        return { path: '/change-password' }
      }
      return resolveFirstAllowedPath(authStore)
    }
    return true
  }

  if (!authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (authStore.mustChangePassword && to.name !== 'change-password') {
    return { path: '/change-password' }
  }

  const permission = typeof to.meta.permission === 'string' ? to.meta.permission : undefined
  if (permission && !authStore.hasPermission(permission)) {
    return resolveFirstAllowedPath(authStore)
  }

  return true
})

export default router
