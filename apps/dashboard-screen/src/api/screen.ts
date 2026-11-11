export interface DashboardSummary {
  hostCount: number
  onlineCount: number
  alertCount: number
  agentExecutions: number
}

export interface RiskSlice {
  level: string
  count: number
}

export interface TrendPoint {
  label: string
  count: number
}

export interface EventItem {
  time: string
  type: string
  message: string
}

export interface HostHealthItem {
  hostname: string
  status: string
  cpuUsage: string
  memoryUsage: string
  diskUsage: string
}

export interface AgentStatusItem {
  name: string
  status: string
  detail: string
}

export interface McpToolStat {
  toolName: string
  count: number
}

export interface HostRuntimeStatus {
  runningTasks: number
  queuedTasks: number
  localActiveLocks: number
  maxParallel: number
  heartbeatHosts: number
}

export interface NativeGuardStatus {
  healthyHosts: number
  warningHosts: number
  criticalHosts: number
  unsupportedHosts: number
  staleHosts: number
  unknownHosts: number
  criticalProbes: number
  warningProbes: number
}

export interface NodeExporterStatus {
  upHosts: number
  downHosts: number
  disabledHosts: number
  staleHosts: number
  unknownHosts: number
  avgCpuUsagePercent: number | null
  avgMemoryUsagePercent: number | null
  avgFilesystemUsagePercent: number | null
}

export interface NodeExporterHostStat {
  hostname: string
  status: string
  checkedAt: string
  cpuUsagePercent: number | null
  memoryUsagePercent: number | null
  filesystemUsagePercent: number | null
  load1: number | null
}

export interface RedisAlertRule {
  name: string
  severity: string
  threshold: number
  currentValue: number
  triggered: boolean
}

export interface RedisStreamStatus {
  enabled: boolean
  status: string
  assistantStreamLength: number
  hostTaskStreamLength: number
  approvalStreamLength: number
  assistantPending: number
  hostTaskPending: number
  approvalPending: number
  deadLetterLength: number
  // outboxPending / outboxFailed 已随后端 outbox_event 表下线（迁移 V9）从契约移除：
  // 那张表没有消费者，任何数字都不代表投递健康度。
  consumerFailures: number
  totalStreamLength: number
  maxPending: number
  capacityLevel: string
  alertRules: RedisAlertRule[]
}

export interface SafeOpsSummary {
  grpcConnectedAgents: number
  pendingApprovals: number
  // 来源：tool_invocation（R5 硬拒计数 / cleanup.rollback 成功次数）与 resource_lock
  // （未过期锁）。此前后端不返回这三个字段，大屏落地页恒显示 0。
  r5Blocked: number
  rollbackRecords: number
  activeResourceLocks: number
  nativeGuardStatus: NativeGuardStatus
  nodeExporterStatus: NodeExporterStatus
  hostRuntimeStatus: HostRuntimeStatus
  activeCertificates: number
  revokedCertificates: number
  expiringCertificates: number
  redisStreamStatus: RedisStreamStatus
  mcpToolTop: McpToolStat[]
  hostEvents: EventItem[]
  nodeExporterHosts: NodeExporterHostStat[]
}

export interface IncidentTile {
  id: string
  title: string
  severity: string
  status: string
  dataMode: string
  sourceIp: string | null
}

export interface IncidentBoard {
  total: number
  open: number
  resolved: number
  avgResolveSeconds: number | null
  avgContainSeconds: number | null
  latest: IncidentTile[]
}

export interface ScreenDashboardResponse {
  summary: DashboardSummary
  riskDistribution: RiskSlice[]
  trend: TrendPoint[]
  events: EventItem[]
  hostHealth: HostHealthItem[]
  agents: AgentStatusItem[]
  safeOps?: SafeOpsSummary
  incidents?: IncidentBoard
}

interface Envelope<T> {
  success: boolean
  data: T
  message: string
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080/api/v1'
const DEFAULT_ORGANIZATION_ID = import.meta.env.VITE_SCREEN_ORGANIZATION_ID ?? 'org-alpha'
const SCREEN_TOKEN = import.meta.env.VITE_SCREEN_TOKEN ?? ''

export async function fetchScreenDashboard() {
  const url = `${API_BASE_URL}/system/screen?organizationId=${encodeURIComponent(DEFAULT_ORGANIZATION_ID)}`
  const response = await fetch(url, {
    headers: {
      Accept: 'application/json',
      'X-Screen-Token': SCREEN_TOKEN,
    },
  })
  if (!response.ok) {
    throw new Error(`大屏数据请求失败: ${response.status}`)
  }
  const payload = (await response.json()) as Envelope<ScreenDashboardResponse>
  if (!payload.success) {
    throw new Error(payload.message || '大屏数据请求失败')
  }
  return payload.data
}
