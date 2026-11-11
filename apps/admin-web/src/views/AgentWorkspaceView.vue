<script setup lang="ts">
/**
 * Agent 全屏对话工作台（路由 /workspace，不套 AdminLayout）。
 *
 * 一句话：把「下达运维意图」和「看着机器怎么执行」放在同一块屏幕上。
 * 中心区三个视图共用一次 run —— 对话（发生了什么）/ 轨迹（每一轮的输入输出）/
 * 时序（回合、工具、子 Agent 的真实时间关系），顶部的运行节拍条在三者之间不动，
 * 是贯穿的锚点与游标。
 *
 * 数据通道：事件走 SSE 增量推送（api/agent-run-stream.ts），聚合量（工具调用、
 * handoff、子 run、预算用量）在相关事件到达时按需重拉；SSE 不可用时整体降级为
 * 轮询，顶栏的指示灯会如实显示当前是「实时」还是「轮询」。
 */
import { computed, onMounted, onUnmounted, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, Check, ChevronDown, ChevronRight, Eye, GitBranch, Loader2, MessagesSquare,
  PanelRight, Radio, ScrollText, Send, Server, ShieldCheck, WifiOff, XCircle, Zap,
} from 'lucide-vue-next'
import { fetchHosts, type HostSummary } from '../api/hosts'
import {
  cancelAgentRun,
  createAgentRun,
  fetchAgentRuns,
  fetchRunTimeline,
  type AgentRunItem,
  type RunTimeline,
  type RuntimeToolInvocationItem,
} from '../api/agent-runtime'
import { openRunEventStream, type RunStreamSubscription, type StreamPhase } from '../api/agent-run-stream'
import { fetchAgents, type AgentRegistryEntry } from '../api/registry'
import { fetchRuntimeStatus, type RuntimeStatus } from '../api/system'
import { confirm, toast } from '../ui'
import Tag from '../ui/Tag.vue'
import RunTape from '../components/workspace/RunTape.vue'
import ActivityStream from '../components/workspace/ActivityStream.vue'
import AgentBadgeStack from '../components/workspace/AgentBadgeStack.vue'
import TrajectoryPanel from '../components/workspace/TrajectoryPanel.vue'
import TimingGraph from '../components/workspace/TimingGraph.vue'
import InspectorPanel from '../components/workspace/InspectorPanel.vue'
import PlanCanvasPanel from '../components/workspace/PlanCanvasPanel.vue'
import { buildAgentBadges, type AgentBadgeModel } from '../components/workspace/agent-badge'
import type { InspectorTarget } from '../components/workspace/inspector'
import {
  buildTimingModel,
  buildTurns,
  fromStreamEvent,
  fromTimelineEvent,
  mergeEvents,
  runConclusion,
  type TimingNode,
  type WorkspaceEvent,
} from '../utils/run-events'
import { formatBudgetUsage, formatDurationMs } from '../utils/format'
import { executionModeLabel, runStatusLabel, runStatusTone, waitingReasonLabel } from '../utils/labels'
import '../components/workspace/workspace-tokens.css'

const router = useRouter()

/* ------------------------------ 运行时能力门控 ------------------------------ */
const runtime = ref<RuntimeStatus | null>(null)
const llmMissing = computed(() => runtime.value != null && !runtime.value.llmConfigured)
const strict = computed(() => runtime.value?.strictMode ?? false)
/** 严格模式 + 未配 LLM = 不允许发起任务。诚实门控，不假装能跑。 */
const sendBlocked = computed(() => strict.value && llmMissing.value)

/* --------------------------------- 主机 --------------------------------- */
const hosts = ref<HostSummary[]>([])
const selectedNode = ref('')
const loadingHosts = ref(false)

/* --------------------------------- 会话 --------------------------------- */
const allRuns = ref<AgentRunItem[]>([])
const activeRunId = ref('')
const timeline = ref<RunTimeline | null>(null)
const events = ref<WorkspaceEvent[]>([])
const registry = ref<AgentRegistryEntry[]>([])
const centerTab = ref<'chat' | 'trajectory' | 'timing'>('chat')
const sending = ref(false)
const chatScroll = ref<HTMLElement | null>(null)

/* -------------------------------- 详情侧栏 -------------------------------- */
const inspector = ref<InspectorTarget>(null)
const inspectorOpen = ref(false)
const selectedSequence = computed(() =>
  inspector.value?.kind === 'event' ? inspector.value.event.sequence : null)
const selectedTimingNodeId = ref<string | null>(null)

/* -------------------------------- 派生数据 -------------------------------- */
const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED']
const isTerminal = (status: string) => TERMINAL.includes(status)

const hostRuns = computed(() =>
  allRuns.value
    .filter((run) => (run.nodeId || '') === selectedNode.value)
    .slice()
    .sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1)))

const activeRun = computed(() => allRuns.value.find((run) => run.id === activeRunId.value) ?? null)
const activeRunning = computed(() => activeRun.value != null && !isTerminal(activeRun.value.status))

const tools = computed<RuntimeToolInvocationItem[]>(() => timeline.value?.toolInvocations ?? [])
const turns = computed(() => buildTurns(events.value))
const badges = computed<AgentBadgeModel[]>(() =>
  buildAgentBadges(
    timeline.value?.childRuns ?? [],
    timeline.value?.handoffs ?? [],
    events.value,
    registry.value,
  ))

/** 让进行中的时序条随时间生长；只在运行时走表，终态后停掉。 */
const nowMs = ref(Date.now())
let clockTimer: number | null = null

const timing = computed(() =>
  buildTimingModel(
    turns.value,
    tools.value,
    timeline.value?.childRuns ?? [],
    activeRun.value?.startedAt || activeRun.value?.createdAt || '',
    nowMs.value,
  ))

/** 最近一次 CONTEXT_COMPILED 的帧 id —— 预留的 Context injection 面板要用。 */
const latestContextFrameId = computed(() => {
  for (let i = events.value.length - 1; i >= 0; i -= 1) {
    const id = events.value[i].payload.contextFrameId
    if (typeof id === 'string' && id) return id
  }
  return ''
})

/** 最近一次 PLAN_VERSION_* 的版本 id —— 预留的 Plan canvas 要用。 */
const latestPlanVersionId = computed(() => {
  for (let i = events.value.length - 1; i >= 0; i -= 1) {
    const event = events.value[i]
    if (!event.eventType.startsWith('PLAN_VERSION_')) continue
    const id = event.payload.planVersionId
    if (typeof id === 'string' && id) return id
  }
  return ''
})

/* --------------------------------- 加载 --------------------------------- */
async function loadRuntime() {
  try {
    runtime.value = await fetchRuntimeStatus()
  } catch {
    /* 顶栏的能力提示缺失不影响主流程 */
  }
}

async function loadRegistry() {
  try {
    registry.value = await fetchAgents()
  } catch {
    // 无 registry:read 权限时工牌退化为「未登记身份」，不是错误路径。
  }
}

async function loadHosts() {
  loadingHosts.value = true
  try {
    hosts.value = await fetchHosts()
    if (!selectedNode.value && hosts.value.length) {
      selectNode(hosts.value[0].nodeId || hosts.value[0].hostname)
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '加载主机失败')
  } finally {
    loadingHosts.value = false
  }
}

async function loadRuns() {
  try {
    allRuns.value = await fetchAgentRuns({ limit: 100 })
  } catch {
    /* 列表是背景信息，静默重试 */
  }
}

/** 拉取聚合量并把 REST 事件并入本地序列；同时充当 SSE 断线时的轮询兜底。 */
async function loadTimeline() {
  const runId = activeRunId.value
  if (!runId) return
  try {
    const data = await fetchRunTimeline(runId, 500)
    if (activeRunId.value !== runId) return
    timeline.value = data
    events.value = mergeEvents(events.value, data.events.map(fromTimelineEvent))
    const index = allRuns.value.findIndex((run) => run.id === data.run.id)
    if (index >= 0) allRuns.value[index] = data.run
  } catch {
    /* 瞬时失败交给下一次触发 */
  }
}

/* ------------------------------- SSE 事件流 ------------------------------- */
let subscription: RunStreamSubscription | null = null
let aggregateTimer: number | null = null
const streamPhase = ref<StreamPhase>('closed')

/** 会改变聚合量的事件：到达后合并重拉一次 timeline，避免每条事件都打一次接口。 */
const AGGREGATE_TRIGGERS = /^(TOOL_INVOCATION_|NODE_INVOCATION_|APPROVAL_|SUB_AGENT|RUN_)/

function scheduleAggregateRefresh() {
  if (aggregateTimer != null) return
  aggregateTimer = window.setTimeout(() => {
    aggregateTimer = null
    void loadTimeline()
  }, 400)
}

function closeStream() {
  subscription?.close()
  subscription = null
  if (aggregateTimer != null) {
    window.clearTimeout(aggregateTimer)
    aggregateTimer = null
  }
}

function openStream(runId: string) {
  closeStream()
  streamPhase.value = 'connecting'
  subscription = openRunEventStream(runId, {
    onEvent(event) {
      if (activeRunId.value !== runId) return
      events.value = mergeEvents(events.value, [fromStreamEvent(event)])
      if (AGGREGATE_TRIGGERS.test(event.eventType)) scheduleAggregateRefresh()
      if (event.eventType.startsWith('RUN_')) void loadRuns()
      if (centerTab.value === 'chat') scrollChatToBottom()
    },
    onPhase(phase) {
      if (activeRunId.value === runId) streamPhase.value = phase
    },
    // 降级轮询：直接重拉 timeline，事件与聚合量一起补齐。
    onPollTick() {
      if (activeRunId.value === runId) void loadTimeline()
    },
  })
}

/* --------------------------------- 交互 --------------------------------- */
function selectNode(node: string) {
  selectedNode.value = node
  const runs = hostRuns.value
  openRun(runs.length ? runs[runs.length - 1].id : '')
}

function openRun(runId: string) {
  if (runId === activeRunId.value) return
  activeRunId.value = runId
  timeline.value = null
  events.value = []
  inspector.value = null
  selectedTimingNodeId.value = null
  closeStream()
  if (!runId) return
  void loadTimeline()
  openStream(runId)
  scrollChatToBottom()
}

const composer = ref('')
const mode = ref<'PLAN' | 'CAUTIOUS' | 'AUTO'>('PLAN')
/**
 * 执行模式的色相就是风险梯度，取自系统状态色：
 * 只读中性、需审批告警黄、免审批危险红——不另造一套模式配色。
 */
const MODES = [
  { value: 'PLAN', label: 'PLAN', hint: '只读规划，不写', icon: Eye, tone: 'neutral' },
  { value: 'CAUTIOUS', label: 'CAUTIOUS', hint: '写操作需人工审批', icon: ShieldCheck, tone: 'warn' },
  { value: 'AUTO', label: 'AUTO', hint: '低风险自动执行，无需审批', icon: Zap, tone: 'danger' },
] as const
const modeOpen = ref(false)
const currentMode = computed(() => MODES.find((item) => item.value === mode.value) ?? MODES[0])

async function pickMode(next: (typeof MODES)[number]) {
  modeOpen.value = false
  if (next.value === 'AUTO' && mode.value !== 'AUTO') {
    const ok = await confirm({
      title: '开启 AUTO 自动执行模式',
      message:
        'AUTO 模式下，低风险（≤R2）写操作将「无需人工审批」直接执行，仅高风险（R3+）仍需审批。'
        + '请确认你了解此风险，并已在受控/演示环境中操作，再继续。',
      confirmText: '我了解，开启 AUTO',
      tone: 'danger',
    })
    if (!ok) return
  }
  mode.value = next.value
}

async function send() {
  const text = composer.value.trim()
  if (!text) return
  if (sendBlocked.value) {
    toast.error('严格模式已开启且未配置 LLM：请先在系统设置配置模型，或关闭严格模式')
    return
  }
  if (!selectedNode.value) {
    toast.error('请先在左侧选择一台主机')
    return
  }
  sending.value = true
  try {
    // 每条消息 = 对该主机新建一个 run（一轮任务）。目标服务由意图推断，不单独填。
    const created = await createAgentRun({
      objective: text,
      executionMode: mode.value,
      nodeId: selectedNode.value,
    })
    composer.value = ''
    await loadRuns()
    centerTab.value = 'chat'
    openRun(created.runId)
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '发起任务失败')
  } finally {
    sending.value = false
  }
}

async function cancelActive() {
  if (!activeRun.value) return
  try {
    await cancelAgentRun(activeRun.value.id)
    toast.success('已请求取消')
    await loadRuns()
    await loadTimeline()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '取消失败')
  }
}

function inspectEvent(event: WorkspaceEvent) {
  inspector.value = { kind: 'event', event }
  inspectorOpen.value = true
}

function inspectTool(tool: RuntimeToolInvocationItem) {
  inspector.value = { kind: 'tool', tool }
  inspectorOpen.value = true
}

function inspectAgent(badge: AgentBadgeModel) {
  inspector.value = { kind: 'agent', badge }
  inspectorOpen.value = true
}

/**
 * 计划画布上的步骤回落到那一次真实执行。
 * 对不上说明该步骤还没落地——此时不开面板，比开一个空面板诚实。
 */
function inspectPlanStep(toolInvocationId: string) {
  const tool = tools.value.find((item) => item.id === toolInvocationId)
  if (tool) inspectTool(tool)
}

/** 时序图上的节点回落到对应的详情：工具节点找工具调用，子 Agent 节点找工牌。 */
function inspectTimingNode(node: TimingNode) {
  selectedTimingNodeId.value = node.id
  if (node.kind === 'tool') {
    const tool = tools.value.find((item) => item.id === node.refId)
    if (tool) inspectTool(tool)
    return
  }
  if (node.kind === 'subagent') {
    const badge = badges.value.find((item) => item.id === node.refId)
    if (badge) inspectAgent(badge)
    return
  }
  const turn = turns.value.find((item) => item.key === node.refId)
  if (turn?.events.length) inspectEvent(turn.events[0])
}

function scrollChatToBottom() {
  void nextTick(() => {
    if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight
  })
}

/** 助手气泡的状态文案；结论正文另取 RUN_COMPLETED.result.content。 */
function outcomeText(run: AgentRunItem): string {
  if (run.status === 'WAITING') {
    const reason = run.waitingReason ? waitingReasonLabel(run.waitingReason) : '等待审批'
    return `${reason}（到「审批中心」处理后自动继续）`
  }
  if (run.status === 'FAILED') return `执行失败${run.outcome ? ' · ' + run.outcome : ''}`
  if (run.status === 'CANCELLED') return '已取消'
  if (run.status === 'COMPLETED') return run.outcome === 'SUCCEEDED' ? '任务完成' : `已结束（${run.outcome ?? '—'}）`
  if (run.status === 'RUNNING') return '正在执行…'
  return '排队中…'
}

const conclusion = computed(() => runConclusion(events.value))

function runElapsed(run: AgentRunItem): string {
  const start = Date.parse(run.startedAt || run.createdAt)
  const end = run.completedAt ? Date.parse(run.completedAt) : nowMs.value
  if (Number.isNaN(start) || Number.isNaN(end)) return ''
  return formatDurationMs(Math.max(0, end - start))
}

/* --------------------------------- 生命周期 --------------------------------- */
let runsTimer: number | null = null

onMounted(async () => {
  await loadRuntime()
  void loadRegistry()
  await loadHosts()
  await loadRuns()
  if (selectedNode.value) selectNode(selectedNode.value)
  // 事件走 SSE，run 列表没有推送通道，保留低频轮询（原来是 2.5s 全量轮询）。
  runsTimer = window.setInterval(() => void loadRuns(), 10000)
  clockTimer = window.setInterval(() => {
    if (activeRunning.value) nowMs.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  closeStream()
  if (runsTimer != null) window.clearInterval(runsTimer)
  if (clockTimer != null) window.clearInterval(clockTimer)
})

watch(selectedNode, () => scrollChatToBottom())
</script>

<template>
  <div class="ws ws-scope">
    <!-- 顶栏 -->
    <header class="ws-top">
      <button class="back" @click="router.push('/agent-runs')"><ArrowLeft :size="15" /> 返回</button>
      <div class="ws-id">
        <Server :size="14" />
        <b>{{ selectedNode || '未选择主机' }}</b>
      </div>

      <div class="ws-flags">
        <span v-if="activeRunId" class="link-state" :class="streamPhase">
          <component :is="streamPhase === 'polling' ? WifiOff : Radio" :size="12" />
          {{ streamPhase === 'live' ? '实时' : streamPhase === 'polling' ? '轮询' : streamPhase === 'connecting' ? '连接中' : '已结束' }}
        </span>
        <Tag v-if="strict" tone="accent">严格模式</Tag>
        <Tag :tone="runtime?.llmConfigured ? 'neutral' : 'warn'">
          {{ runtime?.llmConfigured ? (runtime?.llmModel || '模型已配置') : '未配置模型' }}
        </Tag>
      </div>

      <nav class="seg" role="tablist">
        <button role="tab" :aria-selected="centerTab === 'chat'" :class="{ on: centerTab === 'chat' }" @click="centerTab = 'chat'">
          <MessagesSquare :size="13" /> 对话
        </button>
        <button role="tab" :aria-selected="centerTab === 'trajectory'" :class="{ on: centerTab === 'trajectory' }" @click="centerTab = 'trajectory'">
          <ScrollText :size="13" /> 轨迹
        </button>
        <button role="tab" :aria-selected="centerTab === 'timing'" :class="{ on: centerTab === 'timing' }" @click="centerTab = 'timing'">
          <GitBranch :size="13" /> 时序
        </button>
      </nav>

      <button
        class="icon-btn"
        :class="{ on: inspectorOpen }"
        :aria-label="inspectorOpen ? '收起详情' : '展开详情'"
        @click="inspectorOpen = !inspectorOpen"
      >
        <PanelRight :size="15" />
      </button>
    </header>

    <div class="ws-body" :class="{ 'with-inspector': inspectorOpen }">
      <!-- 左：主机与会话 -->
      <aside class="ws-rail">
        <div class="rail-sec hosts">
          <div class="rail-hd">主机</div>
          <p v-if="loadingHosts" class="rail-hint">加载中…</p>
          <p v-else-if="!hosts.length" class="rail-hint">暂无已注册主机</p>
          <button
            v-for="host in hosts"
            :key="host.nodeId || host.hostname"
            class="host"
            :class="{ on: selectedNode === (host.nodeId || host.hostname) }"
            @click="selectNode(host.nodeId || host.hostname)"
          >
            <span class="host-dot" :class="{ up: host.grpcConnected }" />
            <span class="host-name">{{ host.hostname }}</span>
            <ChevronRight :size="13" class="host-chev" />
          </button>
        </div>

        <div class="rail-sec runs">
          <div class="rail-hd">会话</div>
          <p v-if="!hostRuns.length" class="rail-hint">该主机还没有任务</p>
          <button
            v-for="run in hostRuns"
            :key="run.id"
            class="run"
            :class="{ on: run.id === activeRunId }"
            @click="openRun(run.id)"
          >
            <span class="run-dot" :class="`st-${run.status.toLowerCase()}`" />
            <span class="run-text">{{ run.objective }}</span>
          </button>
        </div>
      </aside>

      <!-- 中：对话 / 轨迹 / 时序 -->
      <main class="ws-center">
        <!-- 运行条：节拍条 + 本次运行的元信息，三个视图共用 -->
        <div v-if="activeRun" class="runbar">
          <div class="runbar-meta">
            <Tag :tone="runStatusTone(activeRun.status)">{{ runStatusLabel(activeRun.status) }}</Tag>
            <span class="rb-mode">{{ executionModeLabel(activeRun.executionMode) }}</span>
            <span class="rb-stat num">{{ runElapsed(activeRun) }}</span>
            <span class="rb-stat num">模型 {{ formatBudgetUsage(activeRun.usage?.modelInvocations, activeRun.budget?.maxModelInvocations) }}</span>
            <span class="rb-stat num">工具 {{ formatBudgetUsage(activeRun.usage?.toolInvocations, activeRun.budget?.maxToolInvocations) }}</span>
            <span class="rb-stat num">tok ↓{{ activeRun.usage?.inputTokens ?? 0 }} ↑{{ activeRun.usage?.outputTokens ?? 0 }}</span>
            <button v-if="activeRunning" class="cancel" @click="cancelActive"><XCircle :size="12" /> 取消</button>
          </div>
          <RunTape :turns="turns" :selected-sequence="selectedSequence" @select="inspectEvent" />
        </div>

        <!-- 对话 -->
        <div v-if="centerTab === 'chat'" ref="chatScroll" class="pane chat">
          <div v-if="!selectedNode" class="blank">先在左侧选择一台主机</div>
          <div v-else-if="!hostRuns.length" class="blank">
            <MessagesSquare :size="24" />
            <p>对 {{ selectedNode }} 还没有任务</p>
            <span>在下方描述你要做的事，回车发起</span>
          </div>
          <div v-else class="thread">
            <section
              v-for="run in hostRuns"
              :key="run.id"
              class="exchange"
              :class="{ active: run.id === activeRunId }"
            >
              <div class="ask">
                <div class="ask-bubble">
                  {{ run.objective }}
                  <em class="ask-mode">{{ run.executionMode }}</em>
                </div>
              </div>

              <div class="reply">
                <button v-if="run.id !== activeRunId" class="reply-collapsed" @click="openRun(run.id)">
                  <span class="run-dot" :class="`st-${run.status.toLowerCase()}`" />
                  {{ outcomeText(run) }}
                  <ChevronRight :size="13" />
                </button>

                <div v-else class="reply-body">
                  <div class="reply-state" :class="`st-${run.status.toLowerCase()}`">
                    <Loader2 v-if="!isTerminal(run.status) && run.status !== 'WAITING'" :size="13" class="spin" />
                    <span v-else class="run-dot" :class="`st-${run.status.toLowerCase()}`" />
                    {{ outcomeText(run) }}
                  </div>

                  <AgentBadgeStack v-if="badges.length" :badges="badges" @select="inspectAgent" />

                  <!-- 按回合分段，让 ReAct 主循环的一圈一圈在对话里也看得见 -->
                  <div v-if="turns.length" class="reply-stream">
                    <div v-for="turn in turns" :key="turn.key" class="turn-block" :class="`s-${turn.status}`">
                      <span class="turn-tag num">{{ turn.label }}</span>
                      <ActivityStream
                        :groups="turn.groups"
                        :selected-sequence="selectedSequence"
                        @select="inspectEvent"
                      />
                    </div>
                  </div>

                  <!-- 结论：可信渲染器给的是结构化结果，按原样列出，不改写不润色 -->
                  <div v-if="conclusion" class="conclusion">
                    <h4 v-if="conclusion.title">{{ conclusion.title }}</h4>
                    <p v-if="conclusion.text" class="conclusion-text">{{ conclusion.text }}</p>
                    <dl v-if="conclusion.facts.length" class="conclusion-facts">
                      <template v-for="fact in conclusion.facts" :key="fact.key">
                        <dt>{{ fact.key }}</dt>
                        <dd>{{ fact.value }}</dd>
                      </template>
                    </dl>
                    <p v-if="conclusion.degraded" class="conclusion-warn">
                      降级结果，非模型输出{{ conclusion.degradedReason ? ' · ' + conclusion.degradedReason : '' }}
                    </p>
                    <p v-if="conclusion.path" class="conclusion-path num">{{ conclusion.path }}</p>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </div>

        <!-- 轨迹 -->
        <div v-else-if="centerTab === 'trajectory'" class="pane scroll">
          <div v-if="!activeRunId" class="blank">选择或发起一个任务查看轨迹</div>
          <TrajectoryPanel
            v-else
            :turns="turns"
            :events="events"
            :selected-sequence="selectedSequence"
            @select="inspectEvent"
          />
        </div>

        <!-- 时序 -->
        <div v-else class="pane scroll">
          <div v-if="!activeRunId" class="blank">选择或发起一个任务查看执行时序</div>
          <template v-else>
            <div v-if="!timing.lanes.length" class="blank">该任务还没有可绘制的执行节点</div>
            <template v-else>
              <div class="timing-hd">
                <h3>执行时序</h3>
                <p>
                  回合 → 工具调用 → 子 Agent 的真实时间关系与因果归属。
                  当前执行模型是 ReAct 主循环，这里画的是**已发生的执行**，不是预先编排的计划。
                </p>
              </div>
              <TimingGraph :model="timing" :selected-id="selectedTimingNodeId" @select="inspectTimingNode" />
            </template>
            <!-- 计划画布：原本打算怎么做，以及兑现到哪一步（与上面的执行时序刻意分开）。
                 刻意不跟执行时序共用 v-else：PLAN 模式下计划先于执行存在，
                 一个还没跑过的 run 也该能看到自己的计划。 -->
            <PlanCanvasPanel
              :run-id="activeRunId"
              :plan-version-id="latestPlanVersionId"
              @inspect="inspectPlanStep"
            />
          </template>
        </div>

        <!-- 组合框 -->
        <div class="composer">
          <div v-if="llmMissing" class="notice" :class="{ block: sendBlocked }">
            <b>{{ sendBlocked ? '严格模式下未配置 LLM，无法发起任务' : '未配置 LLM，任务会停在模型调用' }}</b>
            <a href="#" @click.prevent="router.push('/platform-llm')">去配置模型 →</a>
          </div>

          <div class="composer-box" :class="{ disabled: sendBlocked }">
            <div class="mode-dd">
              <button class="mode-trigger" :class="`tone-${currentMode.tone}`" :aria-expanded="modeOpen" @click="modeOpen = !modeOpen">
                <component :is="currentMode.icon" :size="14" />
                <span>{{ currentMode.label }}</span>
                <ChevronDown :size="12" class="mode-caret" :class="{ up: modeOpen }" />
              </button>
              <Transition name="pop">
                <div v-if="modeOpen" class="mode-menu">
                  <button
                    v-for="item in MODES"
                    :key="item.value"
                    class="mode-opt"
                    :class="[`tone-${item.tone}`, { on: mode === item.value }]"
                    @click="pickMode(item)"
                  >
                    <span class="mode-ic"><component :is="item.icon" :size="14" /></span>
                    <span class="mode-txt"><b>{{ item.label }}</b><small>{{ item.hint }}</small></span>
                    <Check v-if="mode === item.value" :size="13" class="mode-check" />
                  </button>
                </div>
              </Transition>
              <div v-if="modeOpen" class="pop-backdrop" @click="modeOpen = false" />
            </div>

            <textarea
              v-model="composer"
              rows="1"
              :placeholder="`对 ${selectedNode || '主机'} 下达运维意图，Enter 发送`"
              @keydown.enter.exact.prevent="send"
            />
            <button class="send" :disabled="sending || !composer.trim() || sendBlocked" aria-label="发送" @click="send">
              <Loader2 v-if="sending" :size="15" class="spin" /><Send v-else :size="15" />
            </button>
          </div>
        </div>
      </main>

      <!-- 右：详情 -->
      <InspectorPanel
        v-if="inspectorOpen"
        :target="inspector"
        :tools="tools"
        :context-frame-id="latestContextFrameId"
        @close="inspector = null"
        @pick-tool="inspectTool"
      />
    </div>
  </div>
</template>

<style scoped>
.ws {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--text);
  font-size: 14px;
}

/* ── 顶栏 ── */
.ws-top {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
  height: 50px;
  padding: 0 12px 0 14px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in oklch, var(--surface) 90%, transparent);
  backdrop-filter: blur(8px);
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text-2);
  font-size: 12.5px;
  cursor: pointer;
  transition: color var(--ws-fast) var(--ws-ease), border-color var(--ws-fast) var(--ws-ease);
}
.back:hover {
  color: var(--text);
  border-color: var(--border-2);
}
.ws-id {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 1;
  min-width: 0;
  color: var(--text-2);
}
.ws-id b {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ws-flags {
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 连接状态灯：实时是唯一会呼吸的元素，轮询降级如实显示，不隐瞒。 */
.link-state {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-3);
}
.link-state.live {
  color: var(--ws-ok);
}
.link-state.live :deep(svg) {
  animation: ws-breathe 1.8s ease-in-out infinite;
}
.link-state.polling {
  color: var(--ws-warn);
}

.seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--radius);
  background: var(--surface-2);
  border: 1px solid var(--border);
}
.seg button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-3);
  font-size: 12.5px;
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease), color var(--ws-fast) var(--ws-ease);
}
.seg button:hover {
  color: var(--text-2);
}
.seg button.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow);
}
.icon-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease), color var(--ws-fast) var(--ws-ease);
}
.icon-btn:hover {
  background: var(--surface-2);
  color: var(--text);
}
.icon-btn.on {
  background: var(--accent-soft);
  color: var(--accent);
}

/* ── 主体三栏 ── */
.ws-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 228px minmax(0, 1fr);
}
.ws-body.with-inspector {
  grid-template-columns: 228px minmax(0, 1fr) 330px;
}

/* 左栏 */
.ws-rail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--border);
  background: var(--ws-panel);
}
.rail-sec {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 8px;
  overflow-y: auto;
}
.rail-sec.hosts {
  flex: 0 1 auto;
  max-height: 46%;
}
.rail-sec.runs {
  flex: 1 1 auto;
  border-top: 1px solid var(--border);
}
.rail-hd {
  padding: 3px 6px 8px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text-3);
}
.rail-hint {
  padding: 6px;
  font-size: 12px;
  color: var(--text-3);
}
.host,
.run {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: transparent;
  color: var(--text-2);
  text-align: left;
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease), border-color var(--ws-fast) var(--ws-ease);
}
.host:hover,
.run:hover {
  background: var(--surface-2);
}
.host.on,
.run.on {
  background: var(--accent-soft);
  border-color: var(--accent-ring);
  color: var(--text);
}
.host-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-3);
}
.host-dot.up {
  background: var(--ws-ok);
  box-shadow: 0 0 0 3px var(--ok-soft);
}
.host-name {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.host-chev {
  flex: none;
  color: var(--text-3);
  opacity: 0;
  transition: opacity var(--ws-fast) var(--ws-ease);
}
.host:hover .host-chev,
.host.on .host-chev {
  opacity: 1;
}
.run-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}
.run-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ws-neutral);
  align-self: flex-start;
  margin-top: 5px;
}
.run-dot.st-running,
.run-dot.st-queued {
  background: var(--ws-running);
}
.run-dot.st-waiting {
  background: var(--ws-warn);
}
.run-dot.st-completed {
  background: var(--ws-ok);
}
.run-dot.st-failed {
  background: var(--ws-danger);
}

/* 中栏 */
.ws-center {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.runbar {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 9px max(4%, 18px) 10px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.runbar-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px 12px;
}
.rb-mode {
  font-size: 11.5px;
  color: var(--text-2);
}
.rb-stat {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.cancel {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding: 3px 9px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ws-danger);
  font-size: 11.5px;
  cursor: pointer;
  transition: border-color var(--ws-fast) var(--ws-ease), background var(--ws-fast) var(--ws-ease);
}
.cancel:hover {
  border-color: var(--danger-ring);
  background: var(--danger-soft);
}

.pane {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.pane.chat {
  padding: 20px max(4%, 18px);
  scroll-behavior: smooth;
}
.pane.scroll {
  padding: 16px max(4%, 18px) 24px;
}
.blank {
  height: 100%;
  min-height: 180px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  color: var(--text-3);
  text-align: center;
  font-size: 13px;
}
.blank p {
  font-size: 14px;
  color: var(--text-2);
}

/* 对话 */
.thread {
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.exchange {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ask {
  display: flex;
  justify-content: flex-end;
}
.ask-bubble {
  max-width: 74%;
  padding: 9px 13px;
  border-radius: var(--radius-xl);
  border-bottom-right-radius: var(--radius-sm);
  background: var(--accent-soft);
  border: 1px solid var(--accent-ring);
  font-size: 13.5px;
  line-height: 1.6;
}
.ask-mode {
  margin-left: 8px;
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
}
.reply {
  display: flex;
}
.reply-collapsed {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  border-bottom-left-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text-3);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--ws-fast) var(--ws-ease), color var(--ws-fast) var(--ws-ease);
}
.reply-collapsed:hover {
  border-color: var(--border-2);
  color: var(--text-2);
}
.reply-collapsed .run-dot {
  align-self: center;
  margin-top: 0;
}
.reply-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  border-bottom-left-radius: var(--radius-sm);
  background: var(--surface);
  box-shadow: var(--shadow);
}
.exchange.active .reply-body {
  border-color: var(--accent-ring);
}
.reply-state {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--text-2);
}
.reply-state .run-dot {
  align-self: center;
  margin-top: 0;
}
.reply-state.st-failed {
  color: var(--ws-danger);
}
.reply-state.st-waiting {
  color: var(--ws-warn);
}
.reply-state.st-completed {
  color: var(--ws-ok);
}
.reply-stream {
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-top: 1px solid var(--border);
  padding-top: 10px;
}
.turn-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.turn-tag {
  align-self: flex-start;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-3);
}
.turn-block.s-running .turn-tag {
  color: var(--accent);
}
.turn-block.s-failed .turn-tag {
  color: var(--ws-danger);
}
/* 结论块：唯一使用左侧强调边的地方，把「答案」从「过程」里拎出来。 */
.conclusion {
  padding: 10px 0 0 12px;
  border-top: 1px solid var(--border);
  border-left: 2px solid var(--accent);
  margin-top: 2px;
  padding-left: 12px;
}
.conclusion h4 {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.conclusion-text {
  font-size: 13.5px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.conclusion-facts {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 3px 14px;
  margin: 0;
  align-items: baseline;
}
.conclusion-facts dt {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}
.conclusion-facts dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--text);
  overflow-wrap: anywhere;
}
.conclusion-warn {
  margin-top: 7px;
  font-size: 11.5px;
  color: var(--ws-warn);
}
.conclusion-path {
  margin-top: 7px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
}

/* 时序 */
.timing-hd {
  margin-bottom: 14px;
}
.timing-hd h3 {
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 4px;
}
.timing-hd p {
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-3);
  max-width: 62ch;
}

/* ── 组合框 ── */
.composer {
  flex: none;
  padding: 10px max(4%, 18px) 14px;
  border-top: 1px solid var(--border);
  background: var(--surface);
}
.notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 9px;
  padding: 7px 11px;
  border-radius: var(--radius);
  font-size: 12px;
  color: var(--text-2);
  border: 1px solid var(--warn-ring);
  background: var(--warn-soft);
}
.notice.block {
  border-color: var(--danger-ring);
  background: var(--danger-soft);
}
.notice a {
  color: var(--accent);
  white-space: nowrap;
}
.composer-box {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 6px 6px 6px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  background: var(--ws-inset);
  transition: border-color var(--ws-fast) var(--ws-ease), box-shadow var(--ws-fast) var(--ws-ease);
}
.composer-box:focus-within {
  border-color: color-mix(in oklch, var(--accent) 50%, var(--border));
  box-shadow: var(--ws-glow);
}
.composer-box.disabled {
  opacity: 0.6;
}
.composer-box textarea {
  flex: 1;
  min-width: 0;
  max-height: 160px;
  padding: 8px 0;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--text);
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.6;
}
.send {
  display: grid;
  place-items: center;
  flex: none;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: var(--radius-lg);
  background: var(--accent);
  color: var(--accent-contrast);
  cursor: pointer;
  transition: opacity var(--ws-fast) var(--ws-ease), background var(--ws-fast) var(--ws-ease);
}
.send:hover:not(:disabled) {
  background: var(--accent-2);
}
.send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

/* 模式选择（向上弹出） */
.mode-dd {
  position: relative;
  flex: none;
  align-self: center;
}
.mode-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  font-size: 11.5px;
  font-weight: 600;
  font-family: var(--font-mono);
  letter-spacing: 0.02em;
  cursor: pointer;
  transition: border-color var(--ws-fast) var(--ws-ease), color var(--ws-fast) var(--ws-ease);
}
/* 模式色 = 风险梯度，取系统状态色 */
.tone-neutral {
  color: var(--text-2);
}
.tone-warn {
  color: var(--ws-warn);
}
.tone-danger {
  color: var(--ws-danger);
}
.mode-trigger:hover {
  border-color: var(--border-2);
}
.mode-caret {
  color: var(--text-3);
  transition: transform var(--ws-base) var(--ws-ease);
}
.mode-caret.up {
  transform: rotate(180deg);
}
.mode-menu {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  z-index: var(--z-dropdown);
  width: 246px;
  padding: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-pop);
}
.mode-opt {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: var(--radius);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease);
}
.mode-opt:hover,
.mode-opt.on {
  background: var(--surface-2);
}
.mode-ic {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border: 1px solid color-mix(in oklch, currentcolor 24%, var(--border));
  border-radius: var(--radius-sm);
  background: color-mix(in oklch, currentcolor 8%, transparent);
}
.mode-txt {
  display: flex;
  flex-direction: column;
  gap: 1px;
  flex: 1;
  min-width: 0;
}
.mode-txt b {
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.02em;
}
.mode-txt small {
  font-size: 11px;
  color: var(--text-3);
}
.mode-check {
  flex: none;
  color: var(--accent);
}
.pop-backdrop {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
}
.pop-enter-active,
.pop-leave-active {
  transition: opacity var(--ws-base) var(--ws-ease), transform var(--ws-base) var(--ws-ease);
  transform-origin: bottom left;
}
.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: translateY(4px) scale(0.98);
}

.spin {
  animation: ws-spin 0.9s linear infinite;
}
@keyframes ws-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1100px) {
  .ws-body,
  .ws-body.with-inspector {
    grid-template-columns: 190px minmax(0, 1fr);
  }
  .ws-body.with-inspector :deep(.inspector) {
    display: none;
  }
}
</style>
