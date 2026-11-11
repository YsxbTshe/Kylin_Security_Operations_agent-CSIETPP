<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ShieldAlert, Siren, CircleCheck, Timer } from 'lucide-vue-next'
import {
  fetchIncidents,
  fetchIncident,
  fetchIncidentMetrics,
  beginIncidentTriage,
  resolveIncident,
  markIncidentFalsePositive,
  parseRunbookSteps,
  type IncidentSummary,
  type IncidentDetail,
  type IncidentMetrics,
} from '../api/incidents'
import { extractErrorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import {
  Alert,
  Button,
  DataTable,
  Drawer,
  Empty,
  MetricCard,
  PageHeader,
  SectionCard,
  Tag,
  confirm,
  toast,
  type DataTableColumn,
} from '../ui'

const authStore = useAuthStore()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const rows = ref<IncidentSummary[]>([])
const metrics = ref<IncidentMetrics | null>(null)

const drawerOpen = ref(false)
const detail = ref<IncidentDetail | null>(null)
const detailLoading = ref(false)
const acting = ref(false)

const columns: DataTableColumn[] = [
  { key: 'title', label: '事件', minWidth: '260px' },
  { key: 'severity', label: '严重度', width: '100px' },
  { key: 'dataMode', label: '数据来源', width: '120px' },
  { key: 'sourceIp', label: '来源 IP', width: '140px' },
  { key: 'status', label: '状态', width: '130px' },
  { key: 'occurrenceCount', label: '次数', width: '80px', align: 'right' },
  { key: 'actions', label: '', width: '90px', align: 'right' },
]

const openCount = computed(() =>
  rows.value.filter((r) => !['RESOLVED', 'CLOSED', 'FALSE_POSITIVE'].includes(r.status)).length,
)
const resolvedCount = computed(() => rows.value.filter((r) => r.status === 'RESOLVED').length)

const metricCards = computed(() => [
  { label: '事件总数', value: rows.value.length, icon: ShieldAlert, tone: 'accent' as const, description: '当前组织下的安全事件' },
  { label: '待处置', value: openCount.value, icon: Siren, tone: 'danger' as const, description: '尚未闭环的事件' },
  { label: '已闭环', value: resolvedCount.value, icon: CircleCheck, tone: 'ok' as const, description: '完成取证—处置—验证—恢复' },
  {
    label: 'MTTR（均值）',
    value: metrics.value?.avgResolveSeconds != null ? formatDuration(metrics.value.avgResolveSeconds) : '—',
    icon: Timer,
    tone: 'warn' as const,
    description: '已解决事件从发现到恢复的平均耗时',
  },
])

function severityTone(severity: string): 'danger' | 'warn' | 'neutral' {
  const s = (severity || '').toUpperCase()
  if (s === 'CRITICAL' || s === 'HIGH') return 'danger'
  if (s === 'MEDIUM') return 'warn'
  return 'neutral'
}

// 三层数据来源必须标明且不混写：颜色 + 文本双编码。
function dataModeTone(mode: string): 'neutral' | 'warn' | 'accent' {
  const m = (mode || '').toUpperCase()
  if (m === 'LIVE') return 'accent'
  if (m === 'REPLAY') return 'warn'
  return 'neutral'
}

function statusTone(status: string): 'danger' | 'warn' | 'ok' | 'neutral' {
  switch ((status || '').toUpperCase()) {
    case 'NEW':
      return 'danger'
    case 'TRIAGED':
    case 'CONTAINING':
    case 'VERIFYING':
      return 'warn'
    case 'CONTAINED':
    case 'RESOLVED':
    case 'CLOSED':
      return 'ok'
    default:
      return 'neutral'
  }
}

const STATUS_LABELS: Record<string, string> = {
  NEW: '待研判',
  TRIAGED: '研判中',
  CONTAINING: '处置中',
  CONTAINED: '已遏制',
  VERIFYING: '验证中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  FALSE_POSITIVE: '误报',
}
function statusLabel(status: string) {
  return STATUS_LABELS[(status || '').toUpperCase()] ?? status
}

function formatDuration(seconds: number | null): string {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds} 秒`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  if (m < 60) return s ? `${m} 分 ${s} 秒` : `${m} 分`
  const h = Math.floor(m / 60)
  return `${h} 时 ${m % 60} 分`
}

const canTriage = computed(() => {
  const s = detail.value?.summary.status
  return s === 'NEW' || s === 'TRIAGED'
})
const canResolve = computed(() => {
  const s = detail.value?.summary.status
  return s === 'CONTAINING' || s === 'CONTAINED' || s === 'VERIFYING'
})
const canFalsePositive = computed(() => {
  const s = detail.value?.summary.status
  return (s === 'NEW' || s === 'TRIAGED') && !detail.value?.summary.taskId
})

const runbookSteps = computed(() => parseRunbookSteps(detail.value?.runbook?.stepsJson))

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [list, m] = await Promise.all([fetchIncidents(), fetchIncidentMetrics()])
    rows.value = list
    metrics.value = m
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载安全事件失败'
  } finally {
    loading.value = false
  }
}

async function openDetail(id: string) {
  drawerOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await fetchIncident(id)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '加载事件详情失败')
    drawerOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

async function onTriage() {
  if (!detail.value) return
  const inc = detail.value.summary
  const ok = await confirm({
    title: '发起受控处置',
    message: `将把事件「${inc.title}」置为「研判中」，并创建一条 CAUTIOUS 处置任务（取证优先 → 高风险处置需人工审批后才会执行）。是否继续？`,
    confirmText: '发起处置',
  })
  if (!ok) return

  acting.value = true
  // 第一步：调后端「开始研判」推进事件状态（NEW → TRIAGED，记录 acknowledged_at）。
  // 后端返回的处置话术/目标主机优先于前端兜底文案——话术由服务端统一，避免前后端两套口径。
  let objective = `响应安全事件「${inc.title}」（类别 ${inc.category}${
    inc.sourceIp ? '，来源 IP ' + inc.sourceIp : ''
  }）：先取证核实，再对确认的威胁执行受控处置（如临时阻断），处置为高风险需人工审批。`
  let nodeId = inc.targetNodeId
  let triaged = false
  try {
    const plan = await beginIncidentTriage(inc.id)
    if (plan?.prompt) objective = plan.prompt
    if (plan?.targetNodeId) nodeId = plan.targetNodeId
    triaged = true
  } catch (err) {
    // 端点缺失/失败时如实告知：不阻断人工处置动线，但绝不在前端伪造状态推进——
    // 事件仍停留在原状态，遏制/MTTR 时效不会凭空出现。
    toast.error(
      `事件状态未推进（${extractErrorMessage(err, '开始研判失败')}）：仍可创建处置任务，但本事件不会进入「研判中/处置中」。`,
    )
  } finally {
    acting.value = false
  }

  // 第二步：跳到 Agent 任务创建口，用事件话术作目标、目标主机预填、CAUTIOUS 模式
  // （写操作强制人工审批）。incidentId 随行，任务创建成功后由 AgentRunView 回填 task_id。
  void router.push({
    name: 'agent-runs',
    query: {
      objective,
      mode: 'CAUTIOUS',
      ...(nodeId ? { nodeId } : {}),
      ...(triaged ? { incidentId: inc.id } : {}),
    },
  })
}

async function onResolve() {
  if (!detail.value) return
  const inc = detail.value.summary
  const ok = await confirm({
    title: '标记为已解决',
    message: `确认事件「${inc.title}」已完成验证与恢复（如已经回滚解除临时阻断）？`,
    confirmText: '标记已解决',
  })
  if (!ok) return
  acting.value = true
  try {
    detail.value = await resolveIncident(inc.id)
    toast.success('事件已闭环')
    await loadAll()
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败')
  } finally {
    acting.value = false
  }
}

async function onFalsePositive() {
  if (!detail.value) return
  const inc = detail.value.summary
  const ok = await confirm({
    title: '标记为误报',
    message: `确认事件「${inc.title}」为误报并关单？未发起处置的事件才可直接关为误报。`,
    confirmText: '标记误报',
    tone: 'danger',
  })
  if (!ok) return
  acting.value = true
  try {
    detail.value = await markIncidentFalsePositive(inc.id)
    toast.success('已标记为误报')
    await loadAll()
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败')
  } finally {
    acting.value = false
  }
}

watch(() => authStore.user?.currentOrganizationId, () => void loadAll(), { immediate: true })
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="安全事件" description="取证 → 判断 → 临时阻断 → 验证 → 恢复的受控响应闭环" />

    <Alert v-if="error" tone="danger" :title="error" />

    <div class="metrics">
      <MetricCard
        v-for="card in metricCards"
        :key="card.label"
        :label="card.label"
        :value="card.value"
        :icon="card.icon"
        :tone="card.tone"
        :description="card.description"
      />
    </div>

    <SectionCard title="事件列表" description="点击「详情」查看证据、运行手册与时效，并发起受控处置">
      <DataTable :columns="columns" :data="rows" row-key="id" empty="暂无安全事件">
        <template #title="{ row }">
          <div class="cell-title">
            <strong>{{ row.title }}</strong>
            <small class="muted">{{ row.category }} · {{ row.targetNodeId || '—' }}</small>
          </div>
        </template>
        <template #severity="{ value }"><Tag :tone="severityTone(value)">{{ value }}</Tag></template>
        <template #dataMode="{ value }"><Tag :tone="dataModeTone(value)">{{ value }}</Tag></template>
        <template #sourceIp="{ value }"><span class="num">{{ value || '—' }}</span></template>
        <template #status="{ value }"><Tag :tone="statusTone(value)">{{ statusLabel(value) }}</Tag></template>
        <template #occurrenceCount="{ value }"><span class="num">{{ value }}</span></template>
        <template #actions="{ row }">
          <Button size="sm" variant="subtle" @click="openDetail(row.id)">详情</Button>
        </template>
      </DataTable>
    </SectionCard>

    <Drawer v-model:open="drawerOpen" :title="detail?.summary.title || '事件详情'" width="560px">
      <div v-if="detailLoading" class="muted">加载中…</div>
      <div v-else-if="detail" class="detail">
        <div class="tag-row">
          <Tag :tone="severityTone(detail.summary.severity)">{{ detail.summary.severity }}</Tag>
          <Tag :tone="dataModeTone(detail.summary.dataMode)">{{ detail.summary.dataMode }}</Tag>
          <Tag :tone="statusTone(detail.summary.status)">{{ statusLabel(detail.summary.status) }}</Tag>
          <Tag v-if="detail.summary.occurrenceCount > 1" tone="warn">合并 {{ detail.summary.occurrenceCount }} 次</Tag>
        </div>

        <dl class="kv">
          <div><dt>事件类别</dt><dd>{{ detail.summary.category }}</dd></div>
          <div><dt>目标主机</dt><dd class="num">{{ detail.summary.targetNodeId || '—' }}</dd></div>
          <div><dt>来源 IP</dt><dd class="num">{{ detail.summary.sourceIp || '—' }}</dd></div>
          <div><dt>发现时间</dt><dd class="num">{{ detail.summary.detectedAt || '—' }}</dd></div>
          <div v-if="detail.summary.taskId"><dt>处置任务</dt><dd class="num">{{ detail.summary.taskId }}</dd></div>
        </dl>

        <p v-if="detail.description" class="desc">{{ detail.description }}</p>

        <section v-if="detail.timeToAcknowledgeSeconds != null || detail.timeToContainSeconds != null || detail.timeToResolveSeconds != null" class="block">
          <h4>时效</h4>
          <div class="timing">
            <span>响应启动 <b>{{ formatDuration(detail.timeToAcknowledgeSeconds) }}</b></span>
            <span>遏制 <b>{{ formatDuration(detail.timeToContainSeconds) }}</b></span>
            <span>恢复(MTTR) <b>{{ formatDuration(detail.timeToResolveSeconds) }}</b></span>
          </div>
        </section>

        <section v-if="runbookSteps.length" class="block">
          <h4>运行手册 · {{ detail.runbook?.name }} <small class="muted">v{{ detail.runbook?.version }}</small></h4>
          <ol class="runbook">
            <li v-for="(step, i) in runbookSteps" :key="i">
              <span class="rb-idx">{{ i + 1 }}</span>
              <span class="rb-body">
                <span class="rb-desc">{{ step.desc }}</span>
                <span class="rb-meta">
                  <code>{{ step.tool }}</code>
                  <Tag :tone="step.risk === 'R0' ? 'neutral' : 'warn'">{{ step.risk }}</Tag>
                  <Tag v-if="step.approval" tone="danger">需审批</Tag>
                </span>
              </span>
            </li>
          </ol>
        </section>

        <section v-if="detail.recommendedSop?.length" class="block">
          <h4>推荐 SOP / 官方文档 <small class="muted">来自 RAG 知识库</small></h4>
          <div class="chips">
            <Tag v-for="sop in detail.recommendedSop" :key="sop.id" tone="accent">{{ sop.title }}</Tag>
          </div>
        </section>

        <section v-if="detail.evidenceJson" class="block">
          <h4>证据摘要</h4>
          <pre class="evidence">{{ detail.evidenceJson }}</pre>
        </section>

        <div class="actions">
          <Button v-if="canTriage" variant="primary" :disabled="acting" @click="onTriage">发起受控处置</Button>
          <Button v-if="canResolve" variant="default" :disabled="acting" @click="onResolve">标记已解决</Button>
          <Button v-if="canFalsePositive" variant="subtle" :disabled="acting" @click="onFalsePositive">标记误报</Button>
        </div>
      </div>
      <Empty v-else description="无详情" />
    </Drawer>
  </div>
</template>

<style scoped>
.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.cell-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cell-title small {
  font-size: 12px;
}
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin: 0;
}
.kv div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.kv dt {
  font-size: 12px;
  color: var(--text-3);
}
.kv dd {
  margin: 0;
  font-size: 13.5px;
}
.desc {
  margin: 0;
  color: var(--text-2);
  line-height: 1.5;
}
.block h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--text-2);
}
.timing {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  color: var(--text-3);
}
.timing b {
  color: var(--text);
}
.runbook {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.runbook li {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.rb-idx {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.rb-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.rb-desc {
  font-size: 13.5px;
}
.rb-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.rb-meta code {
  font-size: 12px;
  color: var(--text-3);
}
.evidence {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius);
  background: var(--surface-2);
  border: 1px solid var(--border);
  font-size: 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 4px;
}
</style>
