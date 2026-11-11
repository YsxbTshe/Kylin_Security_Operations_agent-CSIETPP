import { getData, postData } from './http'

/** 专家注册表 / 知识域 / Agent IAM（决赛计划 R）。 */
export interface AgentRegistryEntry {
  id: string
  agentKey: string
  name: string
  role: string
  description: string | null
  knowledgeDomains: string[]
  allowedTools: string[]
  maxRisk: string
  budgetTokens: number
  enabled: boolean
  version: string
  createdAt: string | null
  updatedAt: string | null
}

export interface KnowledgeDomain {
  id: string
  code: string
  name: string
  description: string | null
}

export interface AgentAuthorization {
  allowed: boolean
  reason: string
}

export function fetchAgents() {
  return getData<AgentRegistryEntry[]>('/registry/agents')
}

export function fetchDomains() {
  return getData<KnowledgeDomain[]>('/registry/domains')
}

export function setAgentEnabled(agentKey: string, enabled: boolean) {
  return postData<AgentRegistryEntry>(`/registry/agents/${agentKey}/enabled`, { enabled })
}

export function authorizeAgent(agentKey: string, tool: string, risk: string) {
  return getData<AgentAuthorization>(`/registry/agents/${agentKey}/authorize`, { params: { tool, risk } })
}
