import { getData, postData } from './http'

export interface HostSummary {
  hostname: string
  ip: string
  status: string
  osVersion: string
  cpuUsage: string
  memoryUsage: string
  diskUsage: string
  loadAverage: string
  uptimeText: string
  nodeId: string
  clusterName: string
  agentVersion: string
  supportedTools: string[]
  lastHeartbeatAt: string
  grpcConnected: boolean
  grpcConnectedAt: string
  certSubject: string
  certExpiresAt: string
  certStatus: string
  nativeGuardStatus: string
  nativeGuardCheckedAt: string
  nativeGuardCriticalCount: number
  nativeGuardWarningCount: number
  nativeGuardJson: string
  nodeExporterStatus: string
  nodeExporterCheckedAt: string
  nodeExporterCpuUsagePercent: number | null
  nodeExporterMemoryUsagePercent: number | null
  nodeExporterFilesystemUsagePercent: number | null
  nodeExporterLoad1: number | null
  nodeExporterJson: string
}

export interface HostSessionItem {
  taskId: string
  requestedBy: string
  executionMode: string
  status: string
  updatedAt: string
}

export interface HostToolDefinition {
  toolName: string
  category: string
  readOnly: boolean
  requiresApproval: boolean
  description: string
}

export interface HostDetail extends HostSummary {
  supportedToolDetails: HostToolDefinition[]
  sessions: HostSessionItem[]
}

export interface HostCertificateBundle {
  hostname: string
  nodeId: string
  certSubject: string
  certExpiresAt: string
  certStatus: string
  caCertPem: string
  crlPem: string
}

export function fetchHosts() {
  return getData<HostSummary[]>('/hosts')
}

export function fetchHostDetail(hostname: string) {
  return getData<HostDetail>(`/hosts/${hostname}`)
}

export function fetchHostCertificate(hostname: string) {
  return getData<HostCertificateBundle>(`/hosts/${hostname}/certificate`)
}

export function revokeHostCertificate(hostname: string, reason: string) {
  return postData<void>(`/hosts/${hostname}/certificate/revoke?reason=${encodeURIComponent(reason)}`)
}
