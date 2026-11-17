import { getData } from './http'

/** 运行时能力状态：严格模式总开关 + LLM/embedding 是否已配置真实依赖。 */
export interface RuntimeStatus {
  strictMode: boolean
  llmConfigured: boolean
  llmModel: string | null
  embeddingProvider: string | null
  embeddingReal: boolean
}

export function fetchRuntimeStatus() {
  return getData<RuntimeStatus>('/system/runtime-status')
}
