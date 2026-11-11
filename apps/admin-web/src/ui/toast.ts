import { reactive } from 'vue'

export type ToastTone = 'success' | 'error' | 'warning' | 'info'

export interface ToastItem {
  id: number
  tone: ToastTone
  message: string
}

const items = reactive<ToastItem[]>([])
let seq = 0

function dismiss(id: number) {
  const index = items.findIndex((item) => item.id === id)
  if (index !== -1) items.splice(index, 1)
}

function push(tone: ToastTone, message: string, duration = 3200) {
  const id = ++seq
  items.push({ id, tone, message })
  window.setTimeout(() => dismiss(id), duration)
}

/** Reactive list consumed by ToastHost. */
export const toastItems = items

export const toast = {
  success: (message: string) => push('success', message),
  error: (message: string) => push('error', message),
  warning: (message: string) => push('warning', message),
  info: (message: string) => push('info', message),
  dismiss,
}
