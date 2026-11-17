import { getData } from './http'

export interface AlertSummary {
  id: string
  level: string
  type: string
  host: string
  status: string
  suggestion: string
}

export function fetchAlerts() {
  return getData<AlertSummary[]>('/alerts')
}
