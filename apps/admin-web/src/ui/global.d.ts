import type { vLoading } from './loading'

declare module 'vue' {
  interface GlobalDirectives {
    vLoading: typeof vLoading
  }
}

export {}
