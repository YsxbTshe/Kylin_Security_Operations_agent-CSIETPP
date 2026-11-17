/** 时间、字节、耗时等纯展示格式化。后端统一返回 ISO-8601 字符串（UTC）。 */

function pad(value: number) {
  return value < 10 ? `0${value}` : String(value)
}

/** ISO 时间 → 本地时区 `YYYY-MM-DD HH:mm:ss`；空值返回 '—'。 */
export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} `
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** ISO 时间 → 本地时区 `MM-DD HH:mm:ss`（时间线条目用，更紧凑）。 */
export function formatInstantShort(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB']

/** 字节数 → 人类可读大小。 */
export function formatBytes(size: number | null | undefined): string {
  if (size == null || Number.isNaN(size)) return '—'
  let value = size
  let unitIndex = 0
  while (value >= 1024 && unitIndex < BYTE_UNITS.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  const text = unitIndex === 0 ? String(value) : value.toFixed(value >= 100 ? 0 : 1)
  return `${text} ${BYTE_UNITS[unitIndex]}`
}

/** 毫秒耗时 → 紧凑展示（如 1.2s / 850ms / 2m 05s）。 */
export function formatDurationMs(ms: number | null | undefined): string {
  if (ms == null || Number.isNaN(ms) || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  const totalSeconds = Math.round(ms / 1000)
  if (totalSeconds < 60) return `${(ms / 1000).toFixed(1)}s`
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}m ${pad(seconds)}s`
}

/** 预算/用量对比展示：`用量 / 预算`，预算缺失时只显示用量。 */
export function formatBudgetUsage(usage: number | null | undefined, budget: number | null | undefined): string {
  const usageText = usage == null ? '0' : String(usage)
  if (budget == null || budget <= 0) return usageText
  return `${usageText} / ${budget}`
}
