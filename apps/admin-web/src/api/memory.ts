import { postData } from './http'

export type MemoryRecallMode = 'semantic' | 'generative-agents' | 'full'

export interface MemoryHit {
  id: string
  memoryType: string
  memorySource: string
  title: string
  content: string
  importance: number
  score: number
  semantic: number
  graph: number
  recency: number
  /** 第五路：任务相似度（执行相似工作时找到既往任务记录） */
  taskAffinity: number
  /** 出处驱动的可信度乘子（验证/任务结局/是否被取代/是否过期） */
  trust: number
  verified: boolean
  /** 已被更新版本取代（只追加历史，召回时降权） */
  superseded: boolean
  /** 超过有效期 */
  expired: boolean
  /** 同一 scope 存在互不取代的分歧记忆，并列呈现待裁断 */
  conflicting: boolean
  scope?: string
}

export interface RememberPayload {
  memoryType: string
  title: string
  content: string
  importance: number
}

export function recallMemory(query: string, mode: MemoryRecallMode = 'full', k = 8) {
  return postData<MemoryHit[]>('/memory/recall', { query, mode, k })
}

export function rememberMemory(payload: RememberPayload) {
  return postData<string>('/memory/remember', payload)
}
