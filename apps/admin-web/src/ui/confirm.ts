import { reactive } from 'vue'

export interface ConfirmOptions {
  title?: string
  message: string
  confirmText?: string
  cancelText?: string
  tone?: 'default' | 'danger'
}

interface ConfirmState {
  open: boolean
  options: Required<ConfirmOptions>
  resolve: ((value: boolean) => void) | null
}

const defaults: Required<ConfirmOptions> = {
  title: '请确认',
  message: '',
  confirmText: '确定',
  cancelText: '取消',
  tone: 'default',
}

export const confirmState = reactive<ConfirmState>({
  open: false,
  options: { ...defaults },
  resolve: null,
})

export function confirm(options: ConfirmOptions): Promise<boolean> {
  return new Promise((resolve) => {
    confirmState.options = { ...defaults, ...options }
    confirmState.resolve = resolve
    confirmState.open = true
  })
}

export function settleConfirm(value: boolean) {
  confirmState.open = false
  confirmState.resolve?.(value)
  confirmState.resolve = null
}
