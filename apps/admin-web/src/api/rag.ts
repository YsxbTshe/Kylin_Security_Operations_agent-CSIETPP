import { deleteData, getData, postData, putData } from './http'

export interface RagDocumentItem {
  id: string
  title: string
  sourceType: string
  content: string
  /** 原始文件地址（如上传的 PDF）；文本录入的文档为空 */
  sourceUrl?: string
  sourceFileName?: string
}

export interface RagChunkItem {
  id: string
  documentId: string
  chunkIndex: number
  title: string
  sourceType: string
  content: string
  score: number
}

export interface RagMemoryItem {
  id: string
  memoryType: string
  title: string
  content: string
  importance: number
  sourceDocumentId?: string
}

export interface RagGraphEdgeItem {
  id: string
  sourceNode: string
  relation: string
  targetNode: string
  evidence?: string
  sourceDocumentId?: string
}

export interface SaveRagDocumentPayload {
  title: string
  sourceType: string
  content: string
}

export interface SaveRagMemoryPayload {
  memoryType: string
  title: string
  content: string
  importance: number
  sourceDocumentId?: string
}

export interface SaveRagGraphEdgePayload {
  sourceNode: string
  relation: string
  targetNode: string
  evidence?: string
  sourceDocumentId?: string
}

export interface RagQueryResponse {
  answer: string
  references: RagDocumentItem[]
  chunks: RagChunkItem[]
  memories: RagMemoryItem[]
  graphEdges: RagGraphEdgeItem[]
  /** 仅当请求带 withTrace 时返回 */
  trace?: RagRetrievalTrace | null
}

/** 通道内的一条候选，rank 从 1 开始 */
export interface RagTraceCandidate {
  chunkId: string
  title: string
  snippet: string
  rank: number
  /** 通道原始分，各通道量纲不同，不可跨通道直接比较 */
  score: number
}

export interface RagTraceChannel {
  key: 'vector' | 'keyword' | 'graph'
  label: string
  /** 该路的真实实现说明，降级时如实反映 */
  detail: string
  weight: number
  candidates: RagTraceCandidate[]
}

export interface RagTraceContribution {
  channel: string
  rank: number
  points: number
}

export interface RagTraceFused {
  chunkId: string
  title: string
  snippet: string
  rank: number
  score: number
  selected: boolean
  contributions: RagTraceContribution[]
}

export interface RagTraceRerankedItem {
  chunkId: string
  title: string
  snippet: string
  rank: number
  /** 重排前在融合阶段的名次，用于展示"精排把谁提上来了" */
  fusedRank: number
  relevance: number
  selected: boolean
}

export interface RagTraceRerank {
  /** DISABLED 未启用 / APPLIED 已重排 / FALLBACK 调用失败已退回融合顺序 —— 界面必须区分 */
  status: 'DISABLED' | 'APPLIED' | 'FALLBACK'
  model: string
  inputCount: number
  items: RagTraceRerankedItem[]
}

export interface RagRetrievalTrace {
  question: string
  rrfK: number
  candidateN: number
  topK: number
  channels: RagTraceChannel[]
  fused: RagTraceFused[]
  rerank: RagTraceRerank
}

/** 文档解析 + LLM 归纳的结果（解析预览不落库） */
export interface RagDocumentAnalysis {
  fileName: string
  pageCount: number
  extractedChars: number
  truncated: boolean
  extractedText: string
  /** OK 摘要可用 / LLM_DISABLED 未配置模型 / FAILED 调用失败 */
  summaryStatus: 'OK' | 'LLM_DISABLED' | 'FAILED'
  summary: string
  keyPoints: string[]
  /** 解析统计：如实反映哪些页用了视觉、描述了多少插图 */
  parseStats?: {
    visionAvailable: boolean
    visionPages: number
    describedImages: number
    emptyPages: number
    documentCount: number
  }
}

export type RagEmbeddingProvider = 'LOCAL' | 'OPENAI_COMPATIBLE'

export interface RagSettingsView {
  embeddingProvider: RagEmbeddingProvider
  embeddingBaseUrl: string
  embeddingModel: string
  embeddingDimension: number
  chunkSize: number
  chunkOverlap: number
  topK: number
  hasEmbeddingApiKey: boolean
  embeddingApiKeyMasked: string
  milvusEnabled: boolean
  milvusBaseUrl: string
  milvusCollection: string
  elasticsearchEnabled: boolean
  elasticsearchBaseUrl: string
  elasticsearchIndex: string
  redisEnabled: boolean
  redisBaseUrl: string
  redisPrefix: string
  rerankerEnabled: boolean
  rerankerBaseUrl: string
  rerankerModel: string
  rerankerTopN: number
  hasRerankerApiKey: boolean
  rerankerApiKeyMasked: string
  updatedBy: string
  updatedAt: string
}

export interface SaveRagSettingsPayload {
  embeddingProvider: RagEmbeddingProvider
  embeddingBaseUrl: string
  embeddingApiKey: string
  embeddingModel: string
  embeddingDimension: number
  chunkSize: number
  chunkOverlap: number
  topK: number
  milvusEnabled: boolean
  milvusBaseUrl: string
  milvusCollection: string
  elasticsearchEnabled: boolean
  elasticsearchBaseUrl: string
  elasticsearchIndex: string
  redisEnabled: boolean
  redisBaseUrl: string
  redisPrefix: string
  /** 交叉重排：留空的 baseUrl / apiKey 会回退使用 embedding 的对应配置 */
  rerankerEnabled: boolean
  rerankerBaseUrl: string
  rerankerApiKey: string
  rerankerModel: string
  rerankerTopN: number
}

export interface RagSettingsTestResponse {
  ok: boolean
  message: string
}

export function fetchRagDocuments() {
  return getData<RagDocumentItem[]>('/rag/documents')
}

export function queryRag(question: string, withTrace = false) {
  return postData<RagQueryResponse>('/rag/query', { question, withTrace })
}

/** 解析 PDF 并归纳，不落库；用于上传时先展示解析质量 */
export function analyzeRagPdf(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return postData<RagDocumentAnalysis>('/rag/documents/pdf/analyze', formData)
}

export function summarizeRagDocument(documentId: string) {
  return postData<RagDocumentAnalysis>(`/rag/documents/${documentId}/summarize`, {})
}

export function createRagDocument(payload: SaveRagDocumentPayload) {
  return postData<RagDocumentItem>('/rag/documents', payload)
}

/** 上传并按页分组入库；返回创建出的多个分页文档 */
export function uploadRagPdf(file: File, title: string) {
  const formData = new FormData()
  formData.append('file', file)
  if (title.trim()) {
    formData.append('title', title.trim())
  }
  return postData<RagDocumentItem[]>('/rag/documents/pdf', formData)
}

/** 某文档的全部分片，按 chunkIndex 顺序 */
export function fetchRagDocumentChunks(documentId: string) {
  return getData<RagChunkItem[]>(`/rag/documents/${documentId}/chunks`)
}

export function updateRagDocument(documentId: string, payload: SaveRagDocumentPayload) {
  return putData<RagDocumentItem>(`/rag/documents/${documentId}`, payload)
}

export function deleteRagDocument(documentId: string) {
  return deleteData<boolean>(`/rag/documents/${documentId}`)
}

export function depositRagDocument(documentId: string) {
  return postData<RagMemoryItem>(`/rag/documents/${documentId}/deposit`)
}

export function fetchRagMemories() {
  return getData<RagMemoryItem[]>('/rag/memories')
}

export function createRagMemory(payload: SaveRagMemoryPayload) {
  return postData<RagMemoryItem>('/rag/memories', payload)
}

export function deleteRagMemory(memoryId: string) {
  return deleteData<boolean>(`/rag/memories/${memoryId}`)
}

export function fetchRagGraphEdges() {
  return getData<RagGraphEdgeItem[]>('/rag/graph-edges')
}

export function createRagGraphEdge(payload: SaveRagGraphEdgePayload) {
  return postData<RagGraphEdgeItem>('/rag/graph-edges', payload)
}

export function updateRagGraphEdge(edgeId: string, payload: SaveRagGraphEdgePayload) {
  return putData<RagGraphEdgeItem>(`/rag/graph-edges/${edgeId}`, payload)
}

export function deleteRagGraphEdge(edgeId: string) {
  return deleteData<boolean>(`/rag/graph-edges/${edgeId}`)
}

export function fetchRagSettings() {
  return getData<RagSettingsView>('/rag/settings')
}

export function updateRagSettings(payload: SaveRagSettingsPayload) {
  return putData<RagSettingsView>('/rag/settings', payload)
}

export function testRagSettings() {
  return postData<RagSettingsTestResponse>('/rag/settings/test')
}

export function rebuildRagIndex() {
  return postData<boolean>('/rag/rebuild')
}

export interface RagGraphNode {
  id: string
  name: string
  nodeType: string
  description?: string
  salience: number
  mentionCount: number
}

export interface RagGraphEdge {
  id: string
  source: string
  target: string
  relation: string
  weight: number
  evidence?: string
}

export interface RagSubgraph {
  nodes: RagGraphNode[]
  edges: RagGraphEdge[]
}

export interface RagGraphStats {
  nodeCount: number
  edgeCount: number
  nodesByType: Record<string, number>
}

export function fetchRagGraph(params: { nodeType?: string; limit?: number } = {}) {
  const search = new URLSearchParams()
  if (params.nodeType) search.set('nodeType', params.nodeType)
  if (params.limit) search.set('limit', String(params.limit))
  const qs = search.toString()
  return getData<RagSubgraph>(`/rag/graph${qs ? `?${qs}` : ''}`)
}

export function fetchRagGraphNeighbors(node: string, hops = 1) {
  return getData<RagSubgraph>(`/rag/graph/neighbors?node=${encodeURIComponent(node)}&hops=${hops}`)
}

/** 图谱节点的来源分片：抽取时写入的绑定关系，不是按名字二次检索 */
export interface RagNodeChunkItem {
  chunkId: string
  documentId: string
  documentTitle: string
  chunkIndex: number
  title: string
  sourceType: string
  content: string
  /** 该实体在此分片中的累计提及次数 */
  weight: number
}

export function fetchRagNodeChunks(nodeId: string, limit = 10) {
  return getData<RagNodeChunkItem[]>(`/rag/graph/nodes/${encodeURIComponent(nodeId)}/chunks?limit=${limit}`)
}

export function fetchRagGraphStats() {
  return getData<RagGraphStats>('/rag/graph/stats')
}

/**
 * 图谱关系的增删改（作用于 /rag/graph 展示的那张 LLM 抽取图 app_kg_edge）。
 * 注意与 createRagGraphEdge 等区分：那组作用于另一张手工关系表 app_rag_graph_edge，
 * 两者 id 空间不通用。
 */
export interface SaveKgEdgePayload {
  sourceNode?: string
  relation: string
  targetNode?: string
  evidence?: string
}

export function createKgEdge(payload: SaveKgEdgePayload) {
  return postData<boolean>('/rag/graph/edges', payload)
}

/** 只支持改关系与证据；换端点等于换一条边，应新建后删除旧边 */
export function updateKgEdge(edgeId: string, payload: SaveKgEdgePayload) {
  return putData<boolean>(`/rag/graph/edges/${edgeId}`, payload)
}

export function deleteKgEdge(edgeId: string) {
  return deleteData<boolean>(`/rag/graph/edges/${edgeId}`)
}

export function rebuildRagGraph() {
  return postData<boolean>('/rag/graph/rebuild')
}
