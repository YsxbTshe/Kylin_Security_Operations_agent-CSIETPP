<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, RefreshCw, XCircle } from 'lucide-vue-next'
import {
  cancelAgentRun,
  fetchRunChain,
  fetchRunTimeline,
  type AgentRunItem,
  type RunChainView,
  type RunEventItem,
  type RunTimeline,
  type RuntimeChildRunItem,
  type RuntimeHandoffItem,
  type RuntimeToolInvocationItem,
} from '../api/agent-runtime'
import {
  Alert,
  Button,
  Empty,
  PageHeader,
  SectionCard,
  Spinner,
  Tag,
  Timeline,
  TimelineItem,
  confirm,
  toast,
} from '../ui'
import { formatBudgetUsage, formatDurationMs, formatInstant, formatInstantShort } from '../utils/format'
import {
  executionModeLabel,
  handoffStatusLabel,
  handoffStatusTone,
  riskCodeTone,
  runEventTypeLabel,
  runOutcomeLabel,
  runStatusLabel,
  runStatusTone,
  toolInvocationStatusLabel,
  toolInvocationStatusTone,
  waitingReasonLabel,
  type TagTone,
} from '../utils/labels'

const route = useRoute()
const router = useRouter()
const runId = computed(() => String(route.params.runId ?? ''))

const loading = ref(false)
const error = ref('')
const timeline = ref<RunTimeline | null>(null)
const chain = ref<RunChainView | null>(null)
const cancelling = ref(false)

const ACTIVE_STATUSES = ['QUEUED', 'RUNNING', 'WAITING']

const run = computed<AgentRunItem | null>(() => timeline.value?.run ?? null)
const isActive = computed(() => run.value != null && ACTIVE_STATUSES.includes(run.value.status))

interface TimelineEntry {
  key: string
  time: string
  tone: TagTone
  kind: 'event' | 'tool' | 'handoff' | 'subrun'
  title: string
  event?: RunEventItem
  tool?: RuntimeToolInvocationItem
  handoff?: RuntimeHandoffItem
  childRun?: RuntimeChildRunItem
}

const EVENT_TONES: Record<string, TagTone> = {
  RUN_CREATED: 'accent',
  RUN_STARTED: 'accent',
  RUN_WAITING: 'warn',
  RUN_WAKEUP: 'accent',
  RUN_COMPLETED: 'ok',
  RUN_FAILED: 'danger',
  RUN_CANCELLED: 'neutral',
  CONTEXT_COMPILED: 'neutral',
  MODEL_INVOCATION_STARTED: 'neutral',
  MODEL_INVOCATION_COMPLETED: 'neutral',
  MODEL_INVOCATION_FAILED: 'danger',
  TOOL_INVOCATION_QUEUED: 'neutral',
  TOOL_INVOCATION_SUCCEEDED: 'ok',
  TOOL_INVOCATION_FAILED: 'danger',
  NODE_INVOCATION_ACKNOWLEDGED: 'neutral',
  APPROVAL_CREATED: 'warn',
  APPROVAL_DECIDED: 'accent',
  SUB_AGENT_RESULT: 'accent',
}

/** 合并事件、工具调用、handoff 与子 run，按时间升序构成单一时间线。 */
const entries = computed<TimelineEntry[]>(() => {
  const data = timeline.value
  if (!data) return []
  const list: TimelineEntry[] = []
  for (const event of data.events) {
    list.push({
      key: `event-${event.eventId}`,
      time: event.createdAt,
      tone: EVENT_TONES[event.eventType] ?? 'neutral',
      kind: 'event',
      title: runEventTypeLabel(event.eventType),
      event,
    })
  }
  for (const tool of data.toolInvocations) {
    list.push({
      key: `tool-${tool.id}`,
      time: tool.queuedAt,
      tone: toolInvocationStatusTone(tool.status),
      kind: 'tool',
      title: `工具调用 · ${tool.toolId}`,
      tool,
    })
  }
  for (const handoff of data.handoffs) {
    list.push({
      key: `handoff-${handoff.id}`,
      time: handoff.createdAt,
      tone: handoffStatusTone(handoff.status),
      kind: 'handoff',
      title: 'SubAgent 招募 / Handoff',
      handoff,
    })
  }
  for (const childRun of data.childRuns) {
    list.push({
      key: `subrun-${childRun.id}`,
      time: childRun.createdAt,
      tone: runStatusTone(childRun.status),
      kind: 'subrun',
      title: '子任务 Run',
      childRun,
    })
  }
  return list.sort((a, b) => a.time.localeCompare(b.time) || a.key.localeCompare(b.key))
})

function parsePayload(event: RunEventItem): Record<string, unknown> {
  try {
    const parsed = JSON.parse(event.payloadJson) as unknown
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** 事件摘要：只挑关键字段展示，避免把整段 JSON 糊在时间线上。 */
function eventSummary(event: RunEventItem): string {
  const payload = parsePayload(event)
  const parts: string[] = []
  const pick = (key: string, prefix: string) => {
    const value = payload[key]
    if (typeof value === 'string' && value.trim()) parts.push(`${prefix}${value}`)
  }
  pick('toolId', '工具 ')
  pick('nodeId', '节点 ')
  pick('decision', '决策 ')
  pick('reason', '原因 ')
  pick('handoffStatus', '交接 ')
  pick('errorCode', '错误码 ')
  pick('message', '')
  return parts.join(' · ').slice(0, 300)
}

async function loadTimeline(showLoading = true) {
  if (showLoading) {
    loading.value = true
  }
  error.value = ''
  try {
    timeline.value = await fetchRunTimeline(runId.value)
    try {
      chain.value = await fetchRunChain(runId.value)
    } catch {
      chain.value = null
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载任务时间线失败'
  } finally {
    loading.value = false
  }
}

async function handleCancel() {
  if (!run.value) return
  const ok = await confirm({
    title: '取消任务',
    message: '确定取消该任务吗？其未完成的子任务与交接将一并取消。',
    confirmText: '取消任务',
    tone: 'danger',
  })
  if (!ok) return
  cancelling.value = true
  try {
    const report = await cancelAgentRun(run.value.id)
    toast.success(`已取消 ${report.cancelledRunIds.length} 个任务、${report.cancelledHandoffIds.length} 个交接`)
    await loadTimeline(false)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '取消失败')
  } finally {
    cancelling.value = false
  }
}

function openChildRun(childRun: RuntimeChildRunItem) {
  void router.push(`/agent-runs/${encodeURIComponent(childRun.id)}`)
}

// 活跃任务轮询刷新，进入终态自动停止。
const pollTimer = ref<number | null>(null)
watch(isActive, (active) => {
  if (pollTimer.value != null) {
    window.clearInterval(pollTimer.value)
    pollTimer.value = null
  }
  if (active) {
    pollTimer.value = window.setInterval(() => void loadTimeline(false), 4000)
  }
}, { immediate: false })

watch(runId, () => {
  timeline.value = null
  void loadTimeline()
}, { immediate: true })

onUnmounted(() => {
  if (pollTimer.value != null) {
    window.clearInterval(pollTimer.value)
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader :title="`任务时间线`" :description="runId">
      <template #actions>
        <Button link :icon="ArrowLeft" @click="router.push('/agent-runs')">返回列表</Button>
        <Button :icon="RefreshCw" @click="loadTimeline()">刷新</Button>
        <Button
          v-if="isActive"
          variant="danger"
          :icon="XCircle"
          :loading="cancelling"
          @click="handleCancel"
        >
          取消任务
        </Button>
      </template>
    </PageHeader>

    <Alert v-if="error" tone="danger" :title="error" />

    <template v-if="run">
      <SectionCard title="任务概要" class="overview">
        <div class="overview-title">{{ run.objective }}</div>
        <div class="overview-tags">
          <Tag :tone="runStatusTone(run.status)">{{ runStatusLabel(run.status) }}</Tag>
          <Tag v-if="run.waitingReason" tone="warn">{{ waitingReasonLabel(run.waitingReason) }}</Tag>
          <Tag v-if="run.outcome" tone="neutral">结果：{{ runOutcomeLabel(run.outcome) }}</Tag>
          <Tag tone="neutral">{{ executionModeLabel(run.executionMode) }}</Tag>
          <Tag v-if="chain" :tone="chain.valid ? 'ok' : 'danger'">{{ chain.message }}</Tag>
          <Tag v-if="run.parentRunId" tone="accent">子任务</Tag>
        </div>
        <div class="overview-grid">
          <div class="kv">
            <div class="kv-k">预算用量（模型调用）</div>
            <div class="kv-v num">{{ formatBudgetUsage(run.usage?.modelInvocations, run.budget?.maxModelInvocations) }}</div>
          </div>
          <div class="kv">
            <div class="kv-k">预算用量（工具调用）</div>
            <div class="kv-v num">{{ formatBudgetUsage(run.usage?.toolInvocations, run.budget?.maxToolInvocations) }}</div>
          </div>
          <div class="kv">
            <div class="kv-k">Token（输入 / 输出）</div>
            <div class="kv-v num">
              {{ formatBudgetUsage(run.usage?.inputTokens, run.budget?.maxInputTokens) }}
              /
              {{ formatBudgetUsage(run.usage?.outputTokens, run.budget?.maxOutputTokens) }}
            </div>
          </div>
          <div class="kv">
            <div class="kv-k">创建时间</div>
            <div class="kv-v num">{{ formatInstant(run.createdAt) }}</div>
          </div>
          <div class="kv">
            <div class="kv-k">开始时间</div>
            <div class="kv-v num">{{ formatInstant(run.startedAt) }}</div>
          </div>
          <div class="kv">
            <div class="kv-k">完成时间</div>
            <div class="kv-v num">{{ formatInstant(run.completedAt) }}</div>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="事件时间线" description="按时间顺序聚合任务事件、工具调用、SubAgent 招募与 Handoff 结果。">
        <Empty v-if="entries.length === 0 && !loading" description="暂无事件" />
        <Timeline v-else>
          <TimelineItem
            v-for="(entry, index) in entries"
            :key="entry.key"
            :tone="entry.tone"
            :timestamp="formatInstantShort(entry.time)"
            :last="index === entries.length - 1"
          >
            <template #title>
              <span class="tl-title">{{ entry.title }}</span>
            </template>

            <template v-if="entry.kind === 'event' && entry.event">
              <div class="tl-meta">
                <Tag tone="neutral">{{ entry.event.eventType }}</Tag>
                <span class="tl-actor">{{ entry.event.actorType }}{{ entry.event.actorId ? ` · ${entry.event.actorId}` : '' }}</span>
                <span class="tl-seq num">#{{ entry.event.sequence }}</span>
              </div>
              <div v-if="eventSummary(entry.event)" class="tl-summary">{{ eventSummary(entry.event) }}</div>
            </template>

            <template v-else-if="entry.kind === 'tool' && entry.tool">
              <div class="tl-meta">
                <Tag :tone="toolInvocationStatusTone(entry.tool.status)">
                  {{ toolInvocationStatusLabel(entry.tool.status) }}
                </Tag>
                <Tag :tone="riskCodeTone(entry.tool.riskCode)">{{ entry.tool.riskCode }}</Tag>
                <Tag v-if="entry.tool.readOnly" tone="neutral">只读</Tag>
                <span v-if="entry.tool.targetNodeId" class="tl-actor">节点 {{ entry.tool.targetNodeId }}</span>
                <span class="tl-dur num">耗时 {{ formatDurationMs(entry.tool.durationMs) }}</span>
              </div>
              <div v-if="entry.tool.errorCode" class="tl-summary tl-summary--danger">错误码：{{ entry.tool.errorCode }}</div>
            </template>

            <template v-else-if="entry.kind === 'handoff' && entry.handoff">
              <div class="tl-meta">
                <Tag :tone="handoffStatusTone(entry.handoff.status)">{{ handoffStatusLabel(entry.handoff.status) }}</Tag>
                <span class="tl-actor">子实例 {{ entry.handoff.childAgentInstanceId }}</span>
              </div>
              <div class="tl-summary">{{ entry.handoff.objective }}</div>
            </template>

            <template v-else-if="entry.kind === 'subrun' && entry.childRun">
              <div class="tl-meta">
                <Tag :tone="runStatusTone(entry.childRun.status)">{{ runStatusLabel(entry.childRun.status) }}</Tag>
                <Tag v-if="entry.childRun.outcome" tone="neutral">{{ runOutcomeLabel(entry.childRun.outcome) }}</Tag>
                <Button size="sm" link @click="openChildRun(entry.childRun!)">查看子任务时间线</Button>
              </div>
              <div class="tl-summary">{{ entry.childRun.objective }}</div>
            </template>
          </TimelineItem>
        </Timeline>
        <div v-if="loading && entries.length > 0" class="tl-loading"><Spinner :size="18" /></div>
      </SectionCard>
    </template>

    <div v-if="loading && !run" class="page-loading"><Spinner :size="24" /></div>
  </div>
</template>

<style scoped>
.overview {
  margin-bottom: 16px;
}
.overview-title {
  font-size: 14.5px;
  font-weight: 600;
  margin-bottom: 10px;
}
.overview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px 24px;
}
.kv-k {
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 3px;
}
.kv-v {
  font-size: 13.5px;
  font-weight: 500;
}
.tl-title {
  font-weight: 600;
}
.tl-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}
.tl-actor {
  font-size: 12px;
  color: var(--text-3);
}
.tl-seq {
  font-size: 12px;
  color: var(--text-3);
}
.tl-dur {
  font-size: 12px;
  color: var(--text-3);
}
.tl-summary {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-2);
  word-break: break-all;
}
.tl-summary--danger {
  color: var(--danger);
}
.tl-loading {
  display: flex;
  justify-content: center;
  padding-top: 12px;
  color: var(--accent);
}
.page-loading {
  display: grid;
  place-items: center;
  padding: 60px 0;
  color: var(--accent);
}
@media (max-width: 960px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
