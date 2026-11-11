import { authToken, getData } from './http'

export interface AuditRecord {
  id: string
  operator: string
  action: string
  target: string
  time: string
}

export interface AuditQuery {
  operator?: string
  action?: string
  target?: string
  fromTime?: string
  toTime?: string
  limit?: number
}

export function fetchAudits(query: AuditQuery = {}) {
  return getData<AuditRecord[]>('/audits', { params: query })
}

export async function exportAudits(query: AuditQuery = {}) {
  const url = new URL('/api/v1/audits/export', window.location.origin)
  appendQuery(url, query)
  await downloadCsv(url, 'platform-audits.csv')
}

function appendQuery(url: URL, query: AuditQuery) {
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') {
      url.searchParams.set(key, String(value))
    }
  }
}

async function downloadCsv(url: URL, filename: string) {
  const response = await fetch(url, {
    credentials: 'include',
    headers: authToken() ? { Authorization: `Bearer ${authToken()}` } : {},
  })
  if (!response.ok) {
    throw new Error(`导出失败: ${response.status}`)
  }
  const blob = await response.blob()
  const objectUrl = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  link.click()
  window.URL.revokeObjectURL(objectUrl)
}
