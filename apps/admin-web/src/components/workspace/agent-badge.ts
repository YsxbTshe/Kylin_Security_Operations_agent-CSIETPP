/**
 * 子 Agent「工牌」的视图模型：把 timeline 的 childRuns / handoffs / SUB_AGENT_RESULT
 * 事件与专家注册表合成一张卡片需要的数据。
 *
 * 严格只搬运真实字段。注册表身份（identity）解析不到时留 null，界面显示
 * 「未登记身份」并退化到 run 上的真实标识，不编造名字、能力或风险上限。
 */
import type { RuntimeChildRunItem, RuntimeHandoffItem } from '../../api/agent-runtime'
import type { AgentRegistryEntry } from '../../api/registry'
import type { WorkspaceEvent } from '../../utils/run-events'

export interface AgentBadgeModel {
  /** 子 run id。 */
  id: string
  objective: string
  status: string
  outcome: string | null
  createdAt: string
  completedAt: string | null
  /** handoff.status（OPEN/COMPLETED/FAILED/CANCELLED），无对应 handoff 时为 null。 */
  handoffStatus: string | null
  /** SUB_AGENT_RESULT.childInstanceId 或 handoff.childAgentInstanceId。 */
  agentInstanceId: string | null
  /** SUB_AGENT_RESULT.artifactRefs 的条数；无事件时为 0。 */
  artifactCount: number
  /** 注册表身份，解析不到为 null（见文件头注释）。 */
  identity: AgentRegistryEntry | null
}

/**
 * 合成工牌列表。
 *
 * handoff 与 childRun 的关联键：handoff.childAgentInstanceId 对应子 run 的
 * rootAgentInstanceId——但 timeline 的 childRuns 不返回 rootAgentInstanceId，
 * 所以这里改用 SUB_AGENT_RESULT 事件（同时带 childRunId 与 childInstanceId 与
 * handoffId）做桥接；桥接不到就按创建顺序对齐，仍对不上则 handoff 字段留空。
 */
export function buildAgentBadges(
  childRuns: RuntimeChildRunItem[],
  handoffs: RuntimeHandoffItem[],
  events: WorkspaceEvent[],
  registry: AgentRegistryEntry[],
): AgentBadgeModel[] {
  const resultsByChildRun = new Map<string, WorkspaceEvent>()
  for (const event of events) {
    if (event.eventType !== 'SUB_AGENT_RESULT') continue
    const childRunId = typeof event.payload.childRunId === 'string' ? event.payload.childRunId : ''
    if (childRunId) resultsByChildRun.set(childRunId, event)
  }

  const handoffById = new Map(handoffs.map((handoff) => [handoff.id, handoff]))
  const handoffByInstance = new Map(handoffs.map((handoff) => [handoff.childAgentInstanceId, handoff]))

  return childRuns.map((child, index) => {
    const result = resultsByChildRun.get(child.id)
    const payload = result?.payload ?? {}
    const handoffId = typeof payload.handoffId === 'string' ? payload.handoffId : ''
    const instanceId = typeof payload.childInstanceId === 'string' ? payload.childInstanceId : ''
    const handoff =
      handoffById.get(handoffId)
      ?? handoffByInstance.get(instanceId)
      // 位置兜底：子 run 与 handoff 都按 created_at 升序返回，一一对应。
      ?? (handoffs.length === childRuns.length ? handoffs[index] : undefined)
    const artifactRefs = payload.artifactRefs

    return {
      id: child.id,
      objective: child.objective,
      status: child.status,
      outcome: child.outcome,
      createdAt: child.createdAt,
      completedAt: child.completedAt,
      handoffStatus: handoff?.status ?? (typeof payload.handoffStatus === 'string' ? payload.handoffStatus : null),
      agentInstanceId: instanceId || handoff?.childAgentInstanceId || null,
      artifactCount: Array.isArray(artifactRefs) ? artifactRefs.length : 0,
      identity: resolveIdentity(child.objective, registry),
    }
  })
}

/**
 * 身份解析。
 *
 * 后端目前不在任何子 Agent 事件里落 agentKey（SUB_AGENT_RESULT 只有 handoffId /
 * childRunId / childInstanceId / handoffStatus / artifactRefs），因此唯一可用的
 * 线索是子 run 的 objective 文本里是否**原样出现**注册表里的 agentKey。
 * 匹配不到就返回 null——宁可显示「未登记身份」，也不猜。
 *
 * TODO(backend)：在 handoff 或子 run 上暴露 agentKey / agentInstanceId → agentKey
 * 的映射后，这里换成精确查表，删掉文本匹配。
 */
function resolveIdentity(objective: string, registry: AgentRegistryEntry[]): AgentRegistryEntry | null {
  if (!objective || !registry.length) return null
  const haystack = objective.toLowerCase()
  return registry.find((entry) => entry.agentKey && haystack.includes(entry.agentKey.toLowerCase())) ?? null
}
