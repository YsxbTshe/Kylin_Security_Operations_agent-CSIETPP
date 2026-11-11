/**
 * agent_run_event 的语义模型：分类、聚合成回合、派生时序节点。
 *
 * 事件流原始形态是一条流水账（几十上百条 RUN_/CONTEXT_/MODEL_/TOOL_/POLICY_…），
 * 直接铺开没人读得下去。这里把它归约成运维工程师真正关心的三层：
 *   频道（channel） 这条事件属于哪类活动 —— 生命周期 / 上下文 / 模型 / 工具 /
 *                   审批 / 子 Agent / 计划 / 策略；
 *   回合（turn）    一次 MODEL_INVOCATION_STARTED → COMPLETED|FAILED 及其后续动作，
 *                   对应 ReAct 主循环的一圈；
 *   时序（timing）  回合 / 工具调用 / 子 Agent 三条泳道上的带时间区间的节点。
 *
 * 颜色约定（全局遵守）：**色相只表达状态，不表达类别**。频道靠图标与位置区分，
 * 于是一次健康的运行整屏都是中性灰，红色一出现就一定是真的出事了。
 */
import type { RunEventItem, RuntimeChildRunItem, RuntimeToolInvocationItem } from '../api/agent-runtime'
import type { RunStreamEvent } from '../api/agent-run-stream'
import type { TagTone } from './labels'
import { runEventTypeLabel } from './labels'

/* ------------------------------ 归一化事件 ------------------------------ */

/**
 * 两个来源的事件统一成同一形状：
 *   REST  /agent-runtime/runs/{id}/timeline → payloadJson 是字符串
 *   SSE   /agent-runs/{id}/events           → payload 已经是对象
 */
export interface WorkspaceEvent {
  eventId: string
  sequence: number
  eventType: string
  actorType: string
  actorId: string
  payload: Record<string, unknown>
  /** 事件哈希链的本条摘要——原始 transcript 里用于对账/取证。 */
  eventHash: string
  createdAt: string
}

export function fromTimelineEvent(event: RunEventItem): WorkspaceEvent {
  let payload: Record<string, unknown> = {}
  try {
    const parsed: unknown = JSON.parse(event.payloadJson)
    if (parsed && typeof parsed === 'object') payload = parsed as Record<string, unknown>
  } catch {
    // 非 JSON payload 不该出现；出现时按空对象处理，原始文本仍在 raw 视图可见。
  }
  return {
    eventId: event.eventId,
    sequence: event.sequence,
    eventType: event.eventType,
    actorType: event.actorType,
    actorId: event.actorId,
    payload,
    eventHash: event.eventHash,
    createdAt: event.createdAt,
  }
}

export function fromStreamEvent(event: RunStreamEvent): WorkspaceEvent {
  return {
    eventId: event.eventId,
    sequence: event.sequence,
    eventType: event.eventType,
    actorType: event.actorType,
    actorId: event.actorId,
    payload: event.payload,
    eventHash: event.eventHash,
    createdAt: event.createdAt,
  }
}

/** 按 sequence 去重合并（SSE 连接时会回放历史，与 REST 拉到的必然重叠）。 */
export function mergeEvents(base: WorkspaceEvent[], incoming: WorkspaceEvent[]): WorkspaceEvent[] {
  if (!incoming.length) return base
  const bySequence = new Map<number, WorkspaceEvent>()
  for (const event of base) bySequence.set(event.sequence, event)
  for (const event of incoming) bySequence.set(event.sequence, event)
  return [...bySequence.values()].sort((a, b) => a.sequence - b.sequence)
}

/* ------------------------------- 频道分类 ------------------------------- */

export type EventChannel =
  | 'lifecycle'
  | 'context'
  | 'model'
  | 'tool'
  | 'approval'
  | 'subagent'
  | 'plan'
  | 'policy'

export const CHANNEL_LABELS: Record<EventChannel, string> = {
  lifecycle: '生命周期',
  context: '上下文',
  model: '模型推理',
  tool: '工具调用',
  approval: '审批',
  subagent: '子 Agent',
  plan: '计划版本',
  policy: '策略判定',
}

/**
 * 频道归属。前缀匹配，后端新增同族事件类型无需改前端。
 *
 * 注意 `POLICY_*`：后端目前**不把它写成事件类型**（PolicyEngine / DispatchGate /
 * IntentAlignmentGuard 里的 POLICY_* 是策略命中码，只出现在被拒工具的结果消息与
 * RUN_FAILED.errorCode 里）。这里的分支是前向兼容——真正的策略命中展示走
 * policyHitLabel()，挂在 errorCode 上。
 */
export function eventChannel(eventType: string): EventChannel {
  if (eventType.startsWith('POLICY_')) return 'policy'
  if (eventType.startsWith('PLAN_VERSION_')) return 'plan'
  if (eventType.startsWith('SUB_AGENT')) return 'subagent'
  if (eventType.startsWith('APPROVAL_')) return 'approval'
  if (eventType.startsWith('TOOL_INVOCATION_') || eventType.startsWith('NODE_INVOCATION_')) return 'tool'
  if (eventType.startsWith('MODEL_')) return 'model'
  if (eventType.startsWith('CONTEXT_')) return 'context'
  return 'lifecycle'
}

/** 明确判定为「坏」的事件类型；其余靠后缀推断，未知类型一律中性。 */
const DANGER_EVENTS = new Set([
  'RUN_FAILED',
  'MODEL_INVOCATION_FAILED',
  'TOOL_INVOCATION_FAILED',
  'POLICY_DENIED',
  'POLICY_IRRECOVERABLE_ACTION',
  'POLICY_MANIFEST_BLOCKED_PATH',
  'POLICY_SECURITY_SWITCH_OFF_DENY',
  'POLICY_R5_FORBIDDEN_OR_INJECTION',
  'POLICY_R5_NATIVE_GUARD_DISABLE',
])
const WARN_EVENTS = new Set([
  'RUN_WAITING',
  'APPROVAL_CREATED',
  'APPROVAL_EXPIRED',
  'MODEL_UNAVAILABLE_FALLBACK',
  'POLICY_HIGH_RISK_APPROVAL',
  'POLICY_INTENT_MISMATCH',
  'POLICY_SENSITIVE_PATH',
  'POLICY_AUDITD_DOWN_ESCALATION',
  'POLICY_KYSEC_DISABLED_ESCALATION',
  'POLICY_DSL_RULE',
  'CONTEXT_SUMMARIZED',
])
const OK_EVENTS = new Set([
  'RUN_COMPLETED',
  'TOOL_INVOCATION_SUCCEEDED',
  'APPROVAL_AUTO_GRANTED',
  'POLICY_READ_ONLY_AUTO',
  'SUB_AGENT_RESULT',
])

export function eventTone(eventType: string): TagTone {
  if (DANGER_EVENTS.has(eventType)) return 'danger'
  if (WARN_EVENTS.has(eventType)) return 'warn'
  if (OK_EVENTS.has(eventType)) return 'ok'
  if (eventType.endsWith('_FAILED')) return 'danger'
  if (eventType.endsWith('_SUCCEEDED')) return 'ok'
  return 'neutral'
}

/* ------------------------------ 事件明细提取 ------------------------------ */

/**
 * payload 里按优先级挑一个能一眼看懂的标识，取不到就返回空串（不编造）。
 * 键名对齐后端 appendEvent 的实际写入（AgentRuntimeStore / ApprovalService /
 * PlanService / SubAgentLifecycleService / AgentRoundExecutor）。
 */
const DETAIL_KEYS = [
  'toolId',
  'model',
  'chainTitle',
  'errorCode',
  'decision',
  'handoffStatus',
  'reason',
  'message',
  'nodeId',
  'outcome',
  'planVersionId',
  'contextFrameId',
  'approvalId',
  'childRunId',
]

export function eventDetail(event: WorkspaceEvent): string {
  for (const key of DETAIL_KEYS) {
    const value = event.payload[key]
    if (typeof value === 'string' && value.trim()) {
      // 策略命中码走词表翻译，其余原样（labels 约定：查不到的键原样返回）。
      return key === 'errorCode' ? policyHitLabel(value.trim()) : value.trim()
    }
    if (typeof value === 'number') return String(value)
  }
  return ''
}

/** payload 里可选的数值指标；只在字段真实存在时返回条目，缺字段就不显示。 */
export interface EventMetric {
  label: string
  value: string
}

/**
 * 键名逐一对齐后端实际写入：
 *   MODEL_INVOCATION_COMPLETED  inputTokens/outputTokens/cacheReadTokens/
 *                               cacheWriteTokens/latencyMillis/toolCallCount
 *   CONTEXT_COMPILED            tokenEstimate
 *   CONTEXT_SUMMARIZED          tokenEstimate/tokensSaved/compressedMessageCount
 * 不存在 durationMs/latencyMs/promptTokens 这类猜测键，别加。
 */
const METRIC_KEYS: Array<[string, string, (value: number) => string]> = [
  ['inputTokens', '输入', (value) => `↓${value}`],
  ['outputTokens', '输出', (value) => `↑${value}`],
  ['cacheReadTokens', '缓存读', (value) => `⌁${value}`],
  ['latencyMillis', '耗时', (value) => `${value}ms`],
  ['toolCallCount', '工具调用', (value) => `${value} 次`],
  ['tokenEstimate', '上下文', (value) => `~${value} tok`],
  ['tokensSaved', '压缩节省', (value) => `-${value} tok`],
  ['compressedMessageCount', '压缩消息', (value) => `${value} 条`],
]

export function eventMetrics(event: WorkspaceEvent): EventMetric[] {
  const metrics: EventMetric[] = []
  for (const [key, label, render] of METRIC_KEYS) {
    const value = event.payload[key]
    if (typeof value !== 'number' || Number.isNaN(value) || value === 0) continue
    metrics.push({ label, value: render(value) })
  }
  const risk = event.payload.riskCode ?? event.payload.riskCeiling
  if (typeof risk === 'string' && risk.trim()) metrics.push({ label: '风险', value: risk.trim() })
  return metrics
}

/**
 * 事件正文。
 *
 * 后端**不把提示词与模型回复写进事件链**：提示词在 context_frame 表，
 * 消息在 agent_run_message 表，两者目前都没有只读 API（见 ContextInjectionPanel
 * 的接口约定）。事件里带正文的只有 RUN_COMPLETED.result.content（降级剧本路径）
 * 与 RUN_FAILED / MODEL_UNAVAILABLE_FALLBACK 的 message，这里就只取这些。
 *
 * 刻意**不**从 TOOL_INVOCATION_SUCCEEDED.result 里挖文本：那是工具返回值，
 * 拿它冒充「助手的回答」是撒谎。工具结果走工具详情。
 */
const TEXT_EVENTS = new Set([
  'RUN_COMPLETED',
  'RUN_FAILED',
  'MODEL_UNAVAILABLE_FALLBACK',
  'MODEL_INVOCATION_FAILED',
])

export function eventText(event: WorkspaceEvent): string {
  if (!TEXT_EVENTS.has(event.eventType)) return ''
  const result = event.payload.result
  if (result && typeof result === 'object') {
    const content = (result as Record<string, unknown>).content
    if (typeof content === 'string' && content.trim()) return content.trim()
  }
  const message = event.payload.message
  if (typeof message === 'string' && message.trim().length > 1) return message.trim()
  return ''
}

/**
 * 策略命中码 → 中文。
 *
 * 后端的 POLICY_* 不是事件类型，而是 PolicyEngine / DispatchGate /
 * IntentAlignmentGuard 的判定码，只出现在 RUN_FAILED.errorCode、
 * TOOL_INVOCATION_FAILED.errorCode 与被拒工具回灌给模型的结果里。
 * 因此策略展示挂在 errorCode 上，而不是单独一条事件通道。
 */
export function policyHitLabel(code: string): string {
  return runEventTypeLabel(code) || code
}

export function isPolicyHit(code: string | null | undefined): boolean {
  return typeof code === 'string' && code.startsWith('POLICY_')
}

export function eventTitle(event: WorkspaceEvent): string {
  return runEventTypeLabel(event.eventType) || event.eventType
}

/* -------------------------------- 回合聚合 -------------------------------- */

export type TurnStatus = 'running' | 'ok' | 'failed'

/** 同频道连续事件折叠成一组，让活动流读起来是「做了什么」而不是「发生了什么」。 */
export interface ActivityGroup {
  key: string
  channel: EventChannel
  tone: TagTone
  title: string
  events: WorkspaceEvent[]
}

/**
 * 本回合的模型路由与上下文指纹——「输入侧」目前能拿到的全部真实信息。
 * 提示词正文不在事件链里（在 context_frame 表，API 待补），所以这里只给指针。
 */
export interface TurnInputs {
  provider: string
  model: string
  reasoningLevel: string
  routeReason: string
  agentInvocationId: string
  /** CONTEXT_COMPILED.contextFrameId —— 未来 context_frame API 的检索键。 */
  contextFrameId: string
  contextDigest: string
  tokenEstimate: number | null
  prefixCacheHit: boolean | null
}

export interface RunTurn {
  key: string
  /** 1 起的回合序号；准备/收尾阶段为 0。 */
  index: number
  kind: 'prologue' | 'turn' | 'epilogue'
  label: string
  startedAt: string
  endedAt: string | null
  /** MODEL_INVOCATION_STARTED → COMPLETED|FAILED 的真实耗时；缺任一端为 null。 */
  modelDurationMs: number | null
  /** 后端上报的 latencyMillis（比时间戳差更准）；无则 null。 */
  latencyMs: number | null
  /** 本回合首事件到下一回合首事件的跨度。 */
  spanMs: number | null
  status: TurnStatus
  events: WorkspaceEvent[]
  groups: ActivityGroup[]
  metrics: EventMetric[]
  inputs: TurnInputs
  /** 模型完成事件里的正文（若 payload 带）。 */
  text: string
}

function str(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function num(value: unknown): number | null {
  return typeof value === 'number' && !Number.isNaN(value) ? value : null
}

function collectInputs(bucket: WorkspaceEvent[]): TurnInputs {
  const started = bucket.find((event) => event.eventType === MODEL_START)?.payload ?? {}
  const context = bucket.find((event) => event.eventType.startsWith('CONTEXT_'))?.payload ?? {}
  return {
    provider: str(started.provider),
    model: str(started.model),
    reasoningLevel: str(started.reasoningLevel),
    routeReason: str(started.routeReason),
    agentInvocationId: str(started.agentInvocationId),
    contextFrameId: str(context.contextFrameId),
    contextDigest: str(context.contextDigest),
    tokenEstimate: num(context.tokenEstimate),
    prefixCacheHit: typeof context.prefixCacheHit === 'boolean' ? context.prefixCacheHit : null,
  }
}

const MODEL_START = 'MODEL_INVOCATION_STARTED'
const MODEL_SETTLED = new Set(['MODEL_INVOCATION_COMPLETED', 'MODEL_INVOCATION_FAILED'])

/**
 * 切分回合：以 MODEL_INVOCATION_STARTED 为界。第一次模型调用之前的事件归入
 * 「准备」，最后一个回合之后若只剩终态事件则归入「收尾」。
 */
export function buildTurns(events: WorkspaceEvent[]): RunTurn[] {
  if (!events.length) return []
  const buckets: WorkspaceEvent[][] = []
  let current: WorkspaceEvent[] = []
  for (const event of events) {
    if (event.eventType === MODEL_START && current.length) {
      buckets.push(current)
      current = []
    }
    current.push(event)
  }
  if (current.length) buckets.push(current)

  const turns: RunTurn[] = []
  let turnIndex = 0
  buckets.forEach((bucket, position) => {
    const isTurn = bucket[0].eventType === MODEL_START
    if (isTurn) turnIndex += 1
    const kind: RunTurn['kind'] = isTurn ? 'turn' : position === 0 ? 'prologue' : 'epilogue'
    const settled = bucket.find((event) => MODEL_SETTLED.has(event.eventType)) ?? null
    const failed = bucket.some((event) => eventTone(event.eventType) === 'danger')
    const terminal = bucket.some((event) =>
      ['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'].includes(event.eventType))
    const startedAt = bucket[0].createdAt
    const endedAt = bucket[bucket.length - 1].createdAt

    let status: TurnStatus = 'running'
    if (failed) status = 'failed'
    else if (isTurn ? settled != null : terminal || position === 0) status = 'ok'

    turns.push({
      key: `${kind}-${bucket[0].sequence}`,
      index: isTurn ? turnIndex : 0,
      kind,
      label: isTurn ? `回合 ${turnIndex}` : kind === 'prologue' ? '准备' : '收尾',
      startedAt,
      endedAt: endedAt === startedAt ? null : endedAt,
      modelDurationMs: settled ? diffMs(startedAt, settled.createdAt) : null,
      latencyMs: settled ? num(settled.payload.latencyMillis) : null,
      spanMs: null,
      status,
      events: bucket,
      groups: groupByChannel(bucket),
      metrics: settled ? eventMetrics(settled) : [],
      inputs: collectInputs(bucket),
      // 终态 run 的结论正文在 RUN_COMPLETED.result.content 上，不在模型完成事件里。
      text: eventText(bucket.find((event) => event.eventType === 'RUN_COMPLETED') ?? settled ?? bucket[0]),
    })
  })

  // spanMs 需要下一回合的起点，回填一次。
  for (let i = 0; i < turns.length; i += 1) {
    const next = turns[i + 1]
    const until = next ? next.startedAt : turns[i].endedAt
    turns[i].spanMs = until ? diffMs(turns[i].startedAt, until) : null
  }
  return turns
}

function groupByChannel(events: WorkspaceEvent[]): ActivityGroup[] {
  const groups: ActivityGroup[] = []
  for (const event of events) {
    const channel = eventChannel(event.eventType)
    const last = groups[groups.length - 1]
    if (last && last.channel === channel) {
      last.events.push(event)
      continue
    }
    groups.push({
      key: `g-${event.sequence}`,
      channel,
      tone: 'neutral',
      title: CHANNEL_LABELS[channel],
      events: [event],
    })
  }
  // 组的色调取组内最重的一条：一组里只要有失败，整组就是失败色。
  for (const group of groups) {
    group.tone = group.events.reduce<TagTone>((worst, event) => {
      const tone = eventTone(event.eventType)
      return toneWeight(tone) > toneWeight(worst) ? tone : worst
    }, 'neutral')
  }
  return groups
}

function toneWeight(tone: TagTone): number {
  return { neutral: 0, accent: 1, ok: 2, warn: 3, danger: 4 }[tone] ?? 0
}

/** 回合耗时：优先用后端上报的 latencyMillis，退化到事件时间戳差。 */
export function turnLatency(turn: RunTurn): number | null {
  return turn.latencyMs ?? turn.modelDurationMs
}

/**
 * 一次 run 的结论。
 *
 * RUN_COMPLETED.result 大多不是一段文字，而是可信渲染器输出的**结构化结果**，
 * 例如 { type:"service-status", title:"business-service 服务状态",
 *        service:"business-service", activeState:"active", subState:"running", … }
 * 加上一组管线自述字段（path / controlOverheadMillis / modelRewriteCount /
 * directDisplay / type / title）。降级剧本路径才会给 content 文本。
 *
 * 所以这里两种都认：有 content 就当正文，没有就把业务字段原样列出来——
 * 那本来就是这次任务的答案，丢掉它等于让用户看着「任务完成」四个字发呆。
 * 管线自述字段单独拎出来做溯源脚注，不混进结论里。
 */
export interface RunConclusion {
  title: string
  text: string
  facts: Array<{ key: string; value: string }>
  /** 渲染路径，如 task-agent>mcp>grpc-v2>trusted-renderer。 */
  path: string
  degraded: boolean
  degradedReason: string
}

/** 管线自述字段：是「怎么产出的」，不是「产出了什么」。 */
const PLUMBING_KEYS = new Set([
  'path',
  'type',
  'title',
  'content',
  'steps',
  'directDisplay',
  'modelRewriteCount',
  'controlOverheadMillis',
  'degraded',
  'degradedReason',
  'fallbackChainId',
  'fallbackChainTitle',
])

export function runConclusion(events: WorkspaceEvent[]): RunConclusion | null {
  for (let i = events.length - 1; i >= 0; i -= 1) {
    const event = events[i]
    if (!TEXT_EVENTS.has(event.eventType)) continue
    const text = eventText(event)
    const raw = event.payload.result
    const result = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
    const facts = Object.entries(result)
      .filter(([key, value]) => !PLUMBING_KEYS.has(key) && (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'))
      .map(([key, value]) => ({ key, value: String(value) }))
    if (!text && !facts.length) continue
    return {
      title: str(result.title),
      text,
      facts,
      path: str(result.path),
      degraded: result.degraded === true,
      degradedReason: str(result.degradedReason),
    }
  }
  return null
}

export function diffMs(from: string, to: string): number | null {
  const start = Date.parse(from)
  const end = Date.parse(to)
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  return Math.max(0, end - start)
}

/* -------------------------------- 时序模型 -------------------------------- */

export type TimingState = 'pending' | 'running' | 'ok' | 'warn' | 'failed' | 'cancelled'

export interface TimingNode {
  id: string
  kind: 'turn' | 'tool' | 'subagent'
  label: string
  sublabel: string
  /** 相对 run 起点的毫秒偏移。 */
  startMs: number
  endMs: number
  /** 端点是否为真实完成时间；false 表示仍在进行（画成敞口）。 */
  open: boolean
  state: TimingState
  /** 归属回合（用于连边）；找不到归属时为 null。 */
  turnIndex: number | null
  refId: string
}

export interface TimingLane {
  id: 'turn' | 'tool' | 'subagent'
  label: string
  nodes: TimingNode[]
}

export interface TimingModel {
  originMs: number
  totalMs: number
  lanes: TimingLane[]
  /** 当前进行中的节点 id，界面据此高亮。 */
  activeIds: string[]
}

function runStateOf(status: string): TimingState {
  return ({
    QUEUED: 'pending',
    RUNNING: 'running',
    WAITING: 'warn',
    COMPLETED: 'ok',
    FAILED: 'failed',
    CANCELLED: 'cancelled',
  } as Record<string, TimingState>)[status] ?? 'pending'
}

function toolStateOf(status: string): TimingState {
  return ({
    QUEUED: 'pending',
    RUNNING: 'running',
    SENT: 'running',
    SUCCEEDED: 'ok',
    FAILED: 'failed',
    REJECTED: 'failed',
    CANCELLED: 'cancelled',
    UNKNOWN: 'warn',
  } as Record<string, TimingState>)[status] ?? 'pending'
}

/**
 * 由回合、工具调用、子 run 派生时序泳道。
 *
 * 注意这是**执行时序图**，不是「计划 DAG」：当前执行模型是 ReAct 主循环，
 * 边表达的是「哪一回合触发了这次工具调用/子 Agent」的真实因果，不是预先编排的
 * 计划依赖。结构化计划与阶段 DAG 由后端另一批工作提供，这里不臆造。
 */
export function buildTimingModel(
  turns: RunTurn[],
  tools: RuntimeToolInvocationItem[],
  childRuns: RuntimeChildRunItem[],
  runStartedAt: string,
  nowMs: number,
): TimingModel {
  const originMs = Date.parse(runStartedAt)
  const origin = Number.isNaN(originMs) ? nowMs : originMs
  const rel = (iso: string | null): number | null => {
    if (!iso) return null
    const parsed = Date.parse(iso)
    return Number.isNaN(parsed) ? null : Math.max(0, parsed - origin)
  }

  const realTurns = turns.filter((turn) => turn.kind === 'turn')
  const turnNodes: TimingNode[] = realTurns.map((turn) => {
    const start = rel(turn.startedAt) ?? 0
    const end = turn.endedAt ? rel(turn.endedAt) ?? start : null
    return {
      id: `turn-${turn.index}`,
      kind: 'turn',
      label: turn.label,
      sublabel: turnLatency(turn) != null ? `${turnLatency(turn)}ms` : '',
      startMs: start,
      endMs: end ?? Math.max(start, nowMs - origin),
      open: turn.status === 'running',
      state: turn.status === 'failed' ? 'failed' : turn.status === 'running' ? 'running' : 'ok',
      turnIndex: turn.index,
      refId: turn.key,
    }
  })

  /** 按时间落点找归属回合：落在某回合区间内即归它，否则归最近的前一个回合。 */
  const attribute = (startMs: number): number | null => {
    let owner: number | null = null
    for (const node of turnNodes) {
      if (startMs >= node.startMs) owner = node.turnIndex
    }
    return owner
  }

  const toolNodes: TimingNode[] = tools.map((tool) => {
    const queued = rel(tool.queuedAt) ?? 0
    const started = rel(tool.startedAt)
    const completed = rel(tool.completedAt)
    const start = started ?? queued
    const running = completed == null
    return {
      id: `tool-${tool.id}`,
      kind: 'tool',
      label: tool.toolId,
      sublabel: tool.durationMs != null ? `${tool.durationMs}ms` : tool.riskCode,
      startMs: start,
      endMs: completed ?? Math.max(start, nowMs - origin),
      open: running,
      state: toolStateOf(tool.status),
      turnIndex: attribute(queued),
      refId: tool.id,
    }
  })

  const subAgentNodes: TimingNode[] = childRuns.map((child) => {
    const start = rel(child.createdAt) ?? 0
    const completed = rel(child.completedAt)
    return {
      id: `sub-${child.id}`,
      kind: 'subagent',
      label: child.objective || '子 Agent',
      sublabel: child.outcome ?? '',
      startMs: start,
      endMs: completed ?? Math.max(start, nowMs - origin),
      open: completed == null,
      state: runStateOf(child.status),
      turnIndex: attribute(start),
      refId: child.id,
    }
  })

  const lanes: TimingLane[] = [
    { id: 'turn', label: '回合', nodes: turnNodes },
    { id: 'tool', label: '工具调用', nodes: toolNodes },
    { id: 'subagent', label: '子 Agent', nodes: subAgentNodes },
  ].filter((lane) => lane.nodes.length > 0) as TimingLane[]

  const all = lanes.flatMap((lane) => lane.nodes)
  // 下限 1s：run 刚起步时全部时间戳挤在同一毫秒，除数为 0 会让节点宽度炸掉。
  const totalMs = Math.max(1000, ...all.map((node) => node.endMs))

  return {
    originMs: origin,
    totalMs,
    lanes,
    activeIds: all.filter((node) => node.state === 'running').map((node) => node.id),
  }
}
