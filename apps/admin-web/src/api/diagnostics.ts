import { getData, postData } from './http'

/** 内部诊断/试判接口（/api/v1/diagnostics）。全部只读、无副作用，供「诊断工具」隐藏页调用。 */

/* ---------------------------- ABAC 资源访问试判 ---------------------------- */

export interface AbacProbeResult {
  allowed: boolean
  reason: string
  matchedPolicyId: string | null
}

export interface AbacProbePayload {
  roles: string[]
  resourceType: string
  resourceId: string
  action: string
  risk?: string
}

export function probeAbac(payload: AbacProbePayload) {
  return postData<AbacProbeResult>('/diagnostics/abac', payload)
}

/* ---------------------------- PolicyEngine 试判 ---------------------------- */

export interface PolicyHitView {
  policyId: string
  message: string
  riskCode: string | null
}

export interface PolicyProbeResult {
  riskCode: string
  requiresApproval: boolean
  denied: boolean
  policyVersion: string
  hits: PolicyHitView[]
}

export interface PolicyProbePayload {
  objective: string
  toolId: string
  argumentsJson?: string
}

export function probePolicy(payload: PolicyProbePayload) {
  return postData<PolicyProbeResult>('/diagnostics/policy', payload)
}

/* -------------------------- 审批令牌绑定检视 -------------------------- */

export interface ApprovalBindingView {
  id: string
  decision: string
  tenantId: string
  runId: string
  agentInstanceId: string
  nodeId: string
  toolId: string
  toolVersion: string
  argumentsDigest: string
  riskCeiling: string
  executionMode: string
  toolInvocationId: string
  agentInvocationId: string | null
  expiresAt: string | null
  decidedAt: string | null
  createdAt: string | null
  consumed: boolean
  expired: boolean
  active: boolean
}

export function inspectApproval(id: string) {
  return getData<ApprovalBindingView>(`/diagnostics/approval/${encodeURIComponent(id)}`)
}
