import { authToken, getData, http, postData, type RequestConfig } from './http'

/**
 * Agent Runtime V2 接口封装（Phase 6.5 时间线 UI）。
 *
 * 后端 v2 端点挂在 `/api/v2`，与 http 实例默认的 `/api/v1` baseURL 不同，
 * 因此每个请求显式传入 `V2` 配置覆盖 baseURL；拦截器（令牌、401 处理）照常生效。
 *
 * 复用既有端点：
 *   GET  /api/v2/approvals                    待审列表（ApprovalController，裸 List）
 *   POST /api/v2/approvals/{id}/decide        批准/拒绝（裸 ApprovalView）
 *   POST /api/v2/agent-runs/{runId}/cancel    取消 run（ApiResponse 包装）
 * 只读查询新增端点（AgentRuntimeQueryController / AgentArtifactQueryController）：
 *   GET  /api/v2/agent-runtime/runs | /runs/{id}/timeline | /approvals | /artifacts ...
 */
const V2: RequestConfig = { baseURL: '/api/v2' }

/* ------------------------------- 类型 ------------------------------- */

export interface AgentRunItem {
  id: string
  parentRunId: string | null
  objective: string
  executionMode: string
  status: string
  waitingReason: string | null
  outcome: string | null
  budget: Record<string, number>
  usage: Record<string, number>
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  nodeId: string | null
  service: string | null
}

export interface RunEventItem {
  eventId: string
  sequence: number
  eventType: string
  actorType: string
  actorId: string
  payloadJson: string
  eventHash: string
  createdAt: string
}

export interface RuntimeToolInvocationItem {
  id: string
  toolId: string
  toolVersion: string
  targetNodeId: string | null
  riskCode: string
  readOnly: boolean
  status: string
  errorCode: string | null
  queuedAt: string
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
}

export interface RuntimeHandoffItem {
  id: string
  objective: string
  status: string
  childAgentInstanceId: string
  createdAt: string
  completedAt: string | null
}

export interface RuntimeChildRunItem {
  id: string
  objective: string
  status: string
  outcome: string | null
  createdAt: string
  completedAt: string | null
}

export interface RunTimeline {
  run: AgentRunItem
  events: RunEventItem[]
  toolInvocations: RuntimeToolInvocationItem[]
  handoffs: RuntimeHandoffItem[]
  childRuns: RuntimeChildRunItem[]
}

export interface RunCancelReport {
  cancelledRunIds: string[]
  cancelledHandoffIds: string[]
}

export interface RuntimeApprovalItem {
  id: string
  toolInvocationId: string
  runId: string
  nodeId: string
  toolId: string
  toolVersion: string
  argumentsJson: string
  riskCeiling: string
  executionMode: string
  decision: string
  requestedFromUserId: string | null
  decidedByUserId: string | null
  createdAt: string
  decidedAt: string | null
  expiresAt: string | null
  batchId: string | null
}

export interface AgentArtifactItem {
  id: string
  name: string
  contentType: string
  sizeBytes: number
  sm3Hex: string | null
  status: string
  agentInstanceId: string | null
  createdAt: string
  publishedAt: string | null
}

export interface ArtifactDownloadUrl {
  downloadUrl: string
  expiresAtEpochSecond: number
}

/* ------------------------------- Run ------------------------------- */

export interface CreateAgentRunPayload {
  objective: string
  executionMode: 'PLAN' | 'CAUTIOUS' | 'AUTO'
  nodeId?: string
  service?: string
  reasoningLevel?: 'OFF' | 'LOW' | 'MEDIUM' | 'HIGH'
}

export interface CreatedRun {
  runId: string
  rootAgentInstanceId: string | null
  status: string
  budgetJson: string
}

/**
 * 创建 Agent 任务（AgentRunController#create）。PLAN 无目标即只读规划；CAUTIOUS/AUTO 带
 * 目标节点/服务作为 targetScope。返回的 runId 用于跳转到任务时间线。
 */
export async function createAgentRun(payload: CreateAgentRunPayload) {
  return postData<CreatedRun>('/agent-runs', payload, V2)
}

export async function fetchAgentRuns(query: { status?: string; limit?: number } = {}) {
  return getData<AgentRunItem[]>('/agent-runtime/runs', { ...V2, params: query })
}

export async function fetchRunTimeline(runId: string, eventLimit = 500) {
  return getData<RunTimeline>(`/agent-runtime/runs/${encodeURIComponent(runId)}/timeline`, {
    ...V2,
    params: { eventLimit },
  })
}

/* ---------------------------- Plan Canvas ---------------------------- */

/** 与 tool_invocation.status 同源，不做二次映射。 */
export type PlanNodeStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'FAILED'

export interface PlanStep {
  id: string
  title: string
  toolId: string | null
  nodeId: string | null
  riskCode: string | null
  requiresApproval: boolean
  status: PlanNodeStatus
  /** 计划步骤 ↔ 实际执行的对账键；null = 这一步还没落地。 */
  toolInvocationId: string | null
  rejectionCode: string | null
}

export interface PlanPhase {
  id: string
  title: string
  order: number
  dependsOn: string[]
  status: PlanNodeStatus
  steps: PlanStep[]
}

export interface PlanCanvas {
  id: string
  runId: string
  version: number
  baseVersion: number | null
  contentDigest: string
  createdAt: string
  createdBy: string | null
  phases: PlanPhase[]
  parsedStepCount: number
  matchedStepCount: number
  /** 正文里未进 Canvas 的说明行数，>0 时如实提示，不假装计划被完整结构化。 */
  unparsedLineCount: number
}

/** 计划画布：最新计划版本解析成阶段/步骤，并与真实工具调用对账。无计划时后端返回 404。 */
export async function fetchPlanCanvas(runId: string) {
  return getData<PlanCanvas>(`/agent-runs/${encodeURIComponent(runId)}/plan/canvas`, V2)
}

export interface RunChainView {
  valid: boolean
  eventCount: number
  brokenEventId: string | null
  reason: string
  message: string
}

export async function fetchRunChain(runId: string) {
  return getData<RunChainView>(`/agent-runtime/runs/${encodeURIComponent(runId)}/chain`, V2)
}

/** 复用 AgentRunController 的取消端点；级联取消子 run 与 OPEN handoff。 */
export async function cancelAgentRun(runId: string) {
  return postData<RunCancelReport>(`/agent-runs/${encodeURIComponent(runId)}/cancel`, {}, V2)
}

/* ------------------------------ 审批 ------------------------------ */

/**
 * 复用 ApprovalController 的待审列表（返回裸数组，不带 ApiResponse 包装）。
 *
 * 租户不再由前端传：服务端一律取会话组织。此前四个审批端点都接受
 * `?tenantId=` 并用它顶掉会话组织，任意登录用户带上他人组织 id 即可读取甚至
 * 审批别人的 R3/R4 写操作，已在后端修复。
 */
export async function fetchPendingApprovals(
  query: { runId?: string; toolId?: string; nodeId?: string } = {},
) {
  const response = await http.get<RuntimeApprovalItem[]>('/approvals', {
    ...V2,
    params: query,
  })
  return response.data
}

/** 已决审批走新增只读端点：decision 留空返回全部（含待审），前端自行过滤。 */
export async function fetchRuntimeApprovals(query: { decision?: string; runId?: string; limit?: number } = {}) {
  return getData<RuntimeApprovalItem[]>('/agent-runtime/approvals', { ...V2, params: query })
}

/** 复用 ApprovalController 的决策端点（租户取自会话；返回裸 ApprovalView）。 */
export async function decideApproval(approvalId: string, approve: boolean, reason: string) {
  const response = await http.post<RuntimeApprovalItem>(
    `/approvals/${encodeURIComponent(approvalId)}/decide`,
    { approve, reason },
    V2,
  )
  return response.data
}

/* ----------------------------- Artifact ----------------------------- */

export async function fetchArtifacts(limit = 100) {
  return getData<AgentArtifactItem[]>('/agent-runtime/artifacts', { ...V2, params: { limit } })
}

export async function fetchArtifactDownloadUrl(artifactId: string) {
  return getData<ArtifactDownloadUrl>(
    `/agent-runtime/artifacts/${encodeURIComponent(artifactId)}/download-url`,
    V2,
  )
}

/**
 * 下载 artifact：先签发限时链接，再带 Authorization 头拉取字节流触发浏览器下载。
 * 不直接 window.open：签名链接在 /api 下仍要求 Bearer 令牌。
 */
export async function downloadArtifact(artifact: AgentArtifactItem) {
  const { downloadUrl } = await fetchArtifactDownloadUrl(artifact.id)
  const response = await fetch(downloadUrl, {
    credentials: 'include',
    headers: authToken() ? { Authorization: `Bearer ${authToken()}` } : {},
  })
  if (!response.ok) {
    let message = `下载失败: ${response.status}`
    try {
      const body = (await response.json()) as { message?: string }
      if (body?.message) message = body.message
    } catch {
      // 非 JSON 错误体时保留默认提示
    }
    throw new Error(message)
  }
  const blob = await response.blob()
  const objectUrl = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = artifact.name || artifact.id
  link.click()
  window.URL.revokeObjectURL(objectUrl)
}
