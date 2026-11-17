import type { ScreenDashboardResponse } from '../api/screen'

export function parsePct(value: string): number {
  const n = Number.parseFloat((value || '').replace('%', ''))
  if (Number.isNaN(n)) return 0
  return Math.max(0, Math.min(100, n))
}

export function onlineRate(dashboard: ScreenDashboardResponse | null): number {
  const summary = dashboard?.summary
  if (!summary || !summary.hostCount) return 0
  return Math.round((summary.onlineCount / summary.hostCount) * 1000) / 10
}

export function offlineCount(dashboard: ScreenDashboardResponse | null): number {
  const summary = dashboard?.summary
  if (!summary) return 0
  return Math.max(0, summary.hostCount - summary.onlineCount)
}

export function highRiskCount(dashboard: ScreenDashboardResponse | null): number {
  const items = dashboard?.riskDistribution ?? []
  return items
    .filter((item) => {
      const u = item.level.toUpperCase()
      return u === 'HIGH' || u === 'CRITICAL'
    })
    .reduce((sum, item) => sum + item.count, 0)
}

export function avgCpu(dashboard: ScreenDashboardResponse | null): number {
  const hosts = dashboard?.hostHealth ?? []
  if (!hosts.length) return 0
  const total = hosts.reduce((sum, host) => sum + parsePct(host.cpuUsage), 0)
  return Math.round(total / hosts.length)
}

export function avgMem(dashboard: ScreenDashboardResponse | null): number {
  const hosts = dashboard?.hostHealth ?? []
  if (!hosts.length) return 0
  const total = hosts.reduce((sum, host) => sum + parsePct(host.memoryUsage), 0)
  return Math.round(total / hosts.length)
}

export function avgDisk(dashboard: ScreenDashboardResponse | null): number {
  const hosts = dashboard?.hostHealth ?? []
  if (!hosts.length) return 0
  const total = hosts.reduce((sum, host) => sum + parsePct(host.diskUsage), 0)
  return Math.round(total / hosts.length)
}

export function peakCpu(dashboard: ScreenDashboardResponse | null): number {
  const hosts = dashboard?.hostHealth ?? []
  if (!hosts.length) return 0
  return Math.round(Math.max(...hosts.map((host) => parsePct(host.cpuUsage))))
}

export function activeAgentCount(dashboard: ScreenDashboardResponse | null): number {
  const agents = dashboard?.agents ?? []
  return agents.filter((agent) => {
    const u = (agent.status || '').toUpperCase()
    return !u.includes('OFFLINE') && !u.includes('ERROR') && !u.includes('FAIL')
  }).length
}

export function offlineAgentCount(dashboard: ScreenDashboardResponse | null): number {
  const agents = dashboard?.agents ?? []
  return agents.length - activeAgentCount(dashboard)
}

export function eventTone(type: string): string {
  const u = (type || '').toUpperCase()
  if (u.includes('CRIT') || u.includes('ERROR') || u.includes('FAIL') || u.includes('DANGER') || u.includes('HIGH') || u.includes('BLOCK') || u.includes('REJECT')) return 'danger'
  if (u.includes('WARN') || u.includes('ALERT') || u.includes('MED') || u.includes('PAUSE') || u.includes('WAITING')) return 'warning'
  if (u.includes('OK') || u.includes('SUCCESS') || u.includes('RESOLVE') || u.includes('CLOSE') || u.includes('COMPLET') || u.includes('FINISH')) return 'success'
  return 'info'
}

export function agentStatusKey(status: string): string {
  const u = (status || '').toUpperCase()
  if (u.includes('OFFLINE') || u.includes('DOWN') || u.includes('ERROR') || u.includes('FAIL')) return 'OFFLINE'
  if (u.includes('WARN') || u.includes('DEGRAD') || u.includes('ALERT')) return 'WARNING'
  return 'OK'
}

export function hostStatusBreakdown(dashboard: ScreenDashboardResponse | null) {
  const hosts = dashboard?.hostHealth ?? []
  let online = 0
  let alerting = 0
  let degraded = 0
  let offline = 0
  for (const host of hosts) {
    const u = (host.status || '').toUpperCase()
    if (u === 'OFFLINE') offline += 1
    else if (u === 'ALERTING') alerting += 1
    else if (u === 'DEGRADED') degraded += 1
    else online += 1
  }
  return { online, alerting, degraded, offline }
}

export function riskLevelCount(dashboard: ScreenDashboardResponse | null, level: string): number {
  return (dashboard?.riskDistribution ?? [])
    .filter((item) => item.level.toUpperCase() === level.toUpperCase())
    .reduce((sum, item) => sum + item.count, 0)
}

export function topHostsByCpu(dashboard: ScreenDashboardResponse | null, n = 5) {
  const hosts = dashboard?.hostHealth ?? []
  return [...hosts]
    .sort((a, b) => parsePct(b.cpuUsage) - parsePct(a.cpuUsage))
    .slice(0, n)
}
