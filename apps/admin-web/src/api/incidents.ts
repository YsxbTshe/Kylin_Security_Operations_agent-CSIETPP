import { getData, postData } from './http'

/** 安全事件响应闭环。 */
export interface IncidentSummary {
  id: string
  title: string
  severity: string
  category: string
  sourceIp: string | null
  targetNodeId: string | null
  dataMode: string
  status: string
  occurrenceCount: number
  taskId: string | null
  detectedAt: string | null
  updatedAt: string | null
}

export interface IncidentRunbookSummary {
  id: string
  name: string
  version: string
  category: string
  summary: string | null
  stepsJson: string
}

export interface SopReference {
  id: string
  title: string
  sourceType: string
}

export interface IncidentDetail {
  summary: IncidentSummary
  description: string | null
  evidenceJson: string | null
  runbookId: string | null
  runbook: IncidentRunbookSummary | null
  recommendedSop: SopReference[]
  sourceAlertId: number | null
  acknowledgedAt: string | null
  containedAt: string | null
  resolvedAt: string | null
  timeToAcknowledgeSeconds: number | null
  timeToContainSeconds: number | null
  timeToResolveSeconds: number | null
}

export interface IncidentMetrics {
  total: number
  byStatus: Record<string, number>
  resolvedCount: number
  avgAcknowledgeSeconds: number | null
  avgContainSeconds: number | null
  avgResolveSeconds: number | null
}

/** 对应后端 IncidentTriagePlan（incidentId / targetNodeId / prompt）。 */
export interface IncidentTriagePlan {
  incidentId: string
  targetNodeId: string | null
  prompt: string
}

export function fetchIncidents(status?: string) {
  return getData<IncidentSummary[]>('/incidents', { params: status ? { status } : undefined })
}

export function fetchIncident(id: string) {
  return getData<IncidentDetail>(`/incidents/${id}`)
}

export function fetchIncidentMetrics() {
  return getData<IncidentMetrics>('/incidents/metrics')
}

export function fetchRunbooks() {
  return getData<IncidentRunbookSummary[]>('/incidents/runbooks')
}

/**
 * 开始研判：置 TRIAGED、记录 acknowledged_at，返回处置话术与目标主机。
 *
 * 对应 `POST /api/v1/incidents/{incidentId}/triage`（权限 incident:respond）。
 * 失败时前端不伪造状态推进，只如实提示「事件状态未推进」，人工动线不被阻断。
 */
export function beginIncidentTriage(id: string) {
  return postData<IncidentTriagePlan>(`/incidents/${id}/triage`, undefined, {
    silentErrorToast: true,
  })
}

/**
 * 回填处置任务并推进到 CONTAINING（闭环第二段，遏制时效由此产生）。
 *
 * 对应 `POST /api/v1/incidents/{incidentId}/link-task`（权限 incident:respond）。
 * 后续由服务端在关联 run 成功完成时推进到 CONTAINED，大屏「遏制均时」由此有数。
 */
export function linkIncidentTask(id: string, taskId: string) {
  return postData<IncidentDetail>(`/incidents/${id}/link-task`, { taskId }, {
    silentErrorToast: true,
  })
}

export interface IncidentIntakeResult {
  incident: IncidentSummary
  merged: boolean
}

export function intakeIncidentFromAlert(alertId: string | number) {
  return postData<IncidentIntakeResult>(`/incidents/from-alert/${alertId}`)
}

export function resolveIncident(id: string) {
  return postData<IncidentDetail>(`/incidents/${id}/resolve`)
}

export function markIncidentFalsePositive(id: string, note?: string) {
  return postData<IncidentDetail>(`/incidents/${id}/false-positive`, { note })
}

export interface RunbookStep {
  step: string
  tool: string
  risk: string
  approval?: boolean
  desc: string
}

/** steps_json 是 JSON 数组字符串，解析失败返回空数组（不抛）。 */
export function parseRunbookSteps(stepsJson: string | null | undefined): RunbookStep[] {
  if (!stepsJson) return []
  try {
    const parsed = JSON.parse(stepsJson)
    return Array.isArray(parsed) ? (parsed as RunbookStep[]) : []
  } catch {
    return []
  }
}
