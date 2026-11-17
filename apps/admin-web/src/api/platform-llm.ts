import { deleteData, getData, postData, putData } from './http'

export type LlmProviderType = 'DEEPSEEK' | 'OPENAI_COMPATIBLE' | 'CUSTOM'

export interface LlmModelConfigView {
  id: string
  providerType: LlmProviderType
  groupName: string
  baseUrl: string
  model: string
  enabled: boolean
  timeoutMs: number
  retryCount: number
  hasApiKey: boolean
  apiKeyMasked: string
  /** 该模型支持的思考档位(Faster→Smarter,始终含 OFF) */
  supportedThinkingLevels: string[]
  /** 默认思考档位 */
  defaultThinkingLevel: string
  updatedBy: string
  updatedAt: string
}

export interface SaveLlmModelConfigPayload {
  providerType: LlmProviderType
  groupName: string
  baseUrl: string
  apiKey: string
  model: string
  enabled: boolean
  timeoutMs: number
  retryCount: number
  /** 支持的思考档位 CSV(如 "OFF,LOW,MEDIUM,HIGH") */
  thinkingLevels?: string
  /** 默认思考档位 */
  thinkingDefault?: string
}

export interface LlmConfigTestResponse {
  ok: boolean
  message: string
  model: string
}

export function fetchPlatformLlmConfigs() {
  return getData<LlmModelConfigView[]>('/platform/llm-config')
}

export function createPlatformLlmConfig(payload: SaveLlmModelConfigPayload) {
  return postData<LlmModelConfigView>('/platform/llm-config', payload)
}

export function updatePlatformLlmConfig(configId: string, payload: SaveLlmModelConfigPayload) {
  return putData<LlmModelConfigView>(`/platform/llm-config/${configId}`, payload)
}

export function deletePlatformLlmConfig(configId: string) {
  return deleteData<boolean>(`/platform/llm-config/${configId}`)
}

export function testPlatformLlmConfig(configId: string) {
  return postData<LlmConfigTestResponse>(`/platform/llm-config/${configId}/test`)
}
