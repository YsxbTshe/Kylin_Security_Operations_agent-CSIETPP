<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, RefreshCw, XCircle, MessagesSquare } from 'lucide-vue-next'
import {
  cancelAgentRun,
  createAgentRun,
  fetchAgentRuns,
  type AgentRunItem,
  type CreateAgentRunPayload,
} from '../api/agent-runtime'
import { fetchRuntimeStatus, type RuntimeStatus } from '../api/system'
import { linkIncidentTask } from '../api/incidents'
import { extractErrorMessage } from '../api/http'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  DataTable,
  Dialog,
  Empty,
  FormField,
  Input,
  PageHeader,
  SectionCard,
  Select,
  Tag,
  confirm,
  toast,
  type DataTableColumn,
} from '../ui'
import { formatBudgetUsage, formatInstant } from '../utils/format'
import {
  executionModeLabel,
  runStatusLabel,
  runStatusTone,
  waitingReasonLabel,
  RUN_STATUS_LABELS,
} from '../utils/labels'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const loading = ref(false)
const error = ref('')
const rows = ref<AgentRunItem[]>([])
const statusFilter = ref('ALL')
const cancelling = ref('')

const ACTIVE_STATUSES = ['QUEUED', 'RUNNING', 'WAITING']

// —— 新建任务对话框：动线第一步（自然语言目标 → 计划/执行）。也接受从告警/事件/主机
// 页跳转过来的 query 预填（objective/nodeId/service/mode），把这些入口都收敛到同一个创建口。
const showCreate = ref(false)
const creating = ref(false)
const canCreate = computed(() => authStore.hasPermission('assistant:use'))
const runtime = ref<RuntimeStatus | null>(null)
const llmMissing = computed(() => runtime.value != null && !runtime.value.llmConfigured)
const createBlocked = computed(() => (runtime.value?.strictMode ?? false) && llmMissing.value)
void fetchRuntimeStatus().then((s) => { runtime.value = s }).catch(() => {})
const modeOptions = [
  { value: 'PLAN', label: 'PLAN（只读规划，不写）' },
  { value: 'CAUTIOUS', label: 'CAUTIOUS（写操作需人工审批）' },
  { value: 'AUTO', label: 'AUTO（低风险自动执行）' },
]
const form = ref<CreateAgentRunPayload>({
  objective: '',
  executionMode: 'PLAN',
  nodeId: '',
  service: '',
})

function openCreate() {
  form.value = { objective: '', executionMode: 'PLAN', nodeId: '', service: '' }
  applyPrefill()
  showCreate.value = true
}

function applyPrefill() {
  const q = route.query
  if (typeof q.objective === 'string') form.value.objective = q.objective
  if (typeof q.nodeId === 'string') form.value.nodeId = q.nodeId
  if (typeof q.service === 'string') form.value.service = q.service
  const mode = typeof q.mode === 'string' ? q.mode.toUpperCase() : ''
  if (mode === 'PLAN' || mode === 'CAUTIOUS' || mode === 'AUTO') {
    form.value.executionMode = mode
  }
}

/**
 * 从安全事件页带 incidentId 跳来的处置任务：把 runId 回填到事件（TRIAGED → CONTAINING），
 * 让「事件 → 处置任务」闭环真正接上，遏制时效/MTTR 才有数据。
 * 失败只提示、不阻断任务本身（任务已创建成功），也不在前端伪造事件状态。
 */
async function linkIncidentIfAny(runId: string) {
  const incidentId = route.query.incidentId
  if (typeof incidentId !== 'string' || !incidentId) return
  try {
    await linkIncidentTask(incidentId, runId)
  } catch (err) {
    toast.error(
      `处置任务未回填到事件（${extractErrorMessage(err, '关联失败')}）：任务已创建，但事件不会自动进入「处置中」。`,
    )
  }
}

async function submitCreate() {
  if (createBlocked.value) {
    toast.error('严格模式已开启且未配置 LLM：请先在系统设置配置模型，或关闭严格模式')
    return
  }
  if (!form.value.objective.trim()) {
    toast.error('请填写任务目标')
    return
  }
  creating.value = true
  try {
    const payload: CreateAgentRunPayload = {
      objective: form.value.objective.trim(),
      executionMode: form.value.executionMode,
      nodeId: form.value.nodeId?.trim() || undefined,
      service: form.value.service?.trim() || undefined,
    }
    const created = await createAgentRun(payload)
    showCreate.value = false
    toast.success('任务已创建')
    await linkIncidentIfAny(created.runId)
    void router.push(`/agent-runs/${encodeURIComponent(created.runId)}`)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '创建任务失败')
  } finally {
    creating.value = false
  }
}

const statusOptions = computed(() => [
  { value: 'ALL', label: '全部状态' },
  ...Object.entries(RUN_STATUS_LABELS).map(([value, label]) => ({ value, label })),
])

const columns: DataTableColumn[] = [
  { key: 'objective', label: '任务目标', minWidth: '260px' },
  { key: 'status', label: '状态', width: '120px' },
  { key: 'waitingReason', label: '等待原因', width: '120px' },
  { key: 'executionMode', label: '执行模式', width: '110px' },
  { key: 'budgetUsage', label: '预算用量（模型/工具）', width: '170px' },
  { key: 'createdAt', label: '创建时间', width: '170px' },
  { key: 'actions', label: '操作', width: '150px', align: 'right' },
]

const hasActiveRuns = computed(() => rows.value.some((run) => ACTIVE_STATUSES.includes(run.status)))

function canCancel(run: AgentRunItem) {
  return ACTIVE_STATUSES.includes(run.status)
}

function budgetUsageText(run: AgentRunItem) {
  const model = formatBudgetUsage(run.usage?.modelInvocations, run.budget?.maxModelInvocations)
  const tool = formatBudgetUsage(run.usage?.toolInvocations, run.budget?.maxToolInvocations)
  return `${model} / ${tool}`
}

async function loadRuns(showLoading = true) {
  if (showLoading) {
    loading.value = true
  }
  error.value = ''
  try {
    rows.value = await fetchAgentRuns({
      status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
      limit: 100,
    })
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载任务列表失败'
  } finally {
    loading.value = false
  }
}

function openTimeline(run: AgentRunItem) {
  void router.push(`/agent-runs/${encodeURIComponent(run.id)}`)
}

async function handleCancel(run: AgentRunItem) {
  const ok = await confirm({
    title: '取消任务',
    message: `确定取消任务「${truncate(run.objective)}」吗？其未完成的子任务与交接将一并取消。`,
    confirmText: '取消任务',
    tone: 'danger',
  })
  if (!ok) return
  cancelling.value = run.id
  try {
    const report = await cancelAgentRun(run.id)
    toast.success(`已取消 ${report.cancelledRunIds.length} 个任务、${report.cancelledHandoffIds.length} 个交接`)
    await loadRuns(false)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '取消失败')
  } finally {
    cancelling.value = ''
  }
}

function truncate(text: string) {
  return text.length > 40 ? `${text.slice(0, 40)}…` : text
}

function handleFilterChange() {
  void loadRuns()
}

// 存在活跃任务时轮询刷新，终态列表则只手动刷新。
const pollTimer = ref<number | null>(null)
watch(
  hasActiveRuns,
  (active) => {
    if (pollTimer.value != null) {
      window.clearInterval(pollTimer.value)
      pollTimer.value = null
    }
    if (active) {
      pollTimer.value = window.setInterval(() => void loadRuns(false), 8000)
    }
  },
  { immediate: false },
)

watch(() => authStore.currentOrganizationId, () => {
  void loadRuns()
}, { immediate: true })

// 从其它页带 query 跳转进来时（objective/nodeId/...），自动弹出预填的新建对话框。
onMounted(() => {
  const q = route.query
  if (canCreate.value && (typeof q.objective === 'string' || typeof q.nodeId === 'string')) {
    openCreate()
  }
})

onUnmounted(() => {
  if (pollTimer.value != null) {
    window.clearInterval(pollTimer.value)
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader
      title="Agent 任务"
      description="Agent Runtime 的任务（Run）列表：查看状态、等待原因与预算用量，支持取消未完成任务。"
    >
      <template #actions>
        <Button :icon="MessagesSquare" @click="router.push('/workspace')">对话工作台</Button>
        <Button v-if="canCreate" variant="primary" :icon="Plus" @click="openCreate">新建任务</Button>
        <Button :icon="RefreshCw" @click="loadRuns()">刷新</Button>
      </template>
    </PageHeader>

    <Dialog
      :open="showCreate"
      title="新建 Agent 任务"
      description="用自然语言描述运维目标；PLAN 只做只读规划，CAUTIOUS/AUTO 需填目标节点与服务。"
      @update:open="showCreate = $event"
    >
      <div class="create-form">
        <div v-if="llmMissing" class="llm-warn" :class="{ block: createBlocked }">
          {{ createBlocked
            ? '严格模式已开启且未配置 LLM：无法发起任务。请先在「系统设置 → 模型配置」配置 OpenAI 兼容端点，或关闭严格模式。'
            : '未配置 LLM：任务会停在模型调用（Connection refused）。可先在「系统设置 → 模型配置」配置端点。' }}
        </div>
        <FormField label="任务目标">
          <Input
            v-model="form.objective"
            type="textarea"
            :rows="3"
            placeholder="例如：排查 node-kylin-01 上 business-service 的运行状态并给出结论"
          />
        </FormField>
        <FormField label="执行模式">
          <Select v-model="form.executionMode" :options="modeOptions" />
        </FormField>
        <div class="create-grid">
          <FormField label="目标节点（可选）">
            <Input v-model="form.nodeId" placeholder="node-kylin-01" />
          </FormField>
          <FormField label="目标服务（可选）">
            <Input v-model="form.service" placeholder="business-service" />
          </FormField>
        </div>
      </div>
      <template #footer>
        <Button @click="showCreate = false">取消</Button>
        <Button variant="primary" :loading="creating" :disabled="createBlocked" @click="submitCreate">创建并查看时间线</Button>
      </template>
    </Dialog>

    <SectionCard title="任务列表" flush>
      <template #actions>
        <FormField label="状态" class="status-filter">
          <Select v-model="statusFilter" :options="statusOptions" @change="handleFilterChange" />
        </FormField>
      </template>

      <DataTable :columns="columns" :data="rows" row-key="id" empty="暂无 Agent 任务" :loading="loading">
        <template #objective="{ row }">
          <button type="button" class="objective" @click="openTimeline(row as AgentRunItem)">
            {{ truncate((row as AgentRunItem).objective) }}
          </button>
          <div class="objective-meta">
            <Tag v-if="(row as AgentRunItem).parentRunId" tone="accent">子任务</Tag>
            <span class="mono">{{ (row as AgentRunItem).id }}</span>
          </div>
        </template>
        <template #status="{ row }">
          <Tag :tone="runStatusTone((row as AgentRunItem).status)">
            {{ runStatusLabel((row as AgentRunItem).status) }}
          </Tag>
        </template>
        <template #waitingReason="{ row }">
          <span v-if="(row as AgentRunItem).waitingReason">
            {{ waitingReasonLabel((row as AgentRunItem).waitingReason) }}
          </span>
          <span v-else class="muted">—</span>
        </template>
        <template #executionMode="{ row }">
          {{ executionModeLabel((row as AgentRunItem).executionMode) }}
        </template>
        <template #budgetUsage="{ row }">
          <span class="num">{{ budgetUsageText(row as AgentRunItem) }}</span>
        </template>
        <template #createdAt="{ row }">
          <span class="num">{{ formatInstant((row as AgentRunItem).createdAt) }}</span>
        </template>
        <template #actions="{ row }">
          <div class="row-actions">
            <Button size="sm" link @click="openTimeline(row as AgentRunItem)">时间线</Button>
            <Button
              v-if="canCancel(row as AgentRunItem)"
              size="sm"
              variant="danger"
              :loading="cancelling === (row as AgentRunItem).id"
              :icon="XCircle"
              @click="handleCancel(row as AgentRunItem)"
            >
              取消
            </Button>
          </div>
        </template>
      </DataTable>

      <div v-if="!loading && error" class="list-error">
        <Empty :description="error" />
      </div>
    </SectionCard>
  </div>
</template>

<style scoped>
.status-filter {
  min-width: 160px;
}
.objective {
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}
.objective:hover {
  color: var(--accent);
}
.objective-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.objective-meta .mono {
  font-size: 11px;
  color: var(--text-3);
}
.muted {
  color: var(--text-3);
}
.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
.list-error {
  padding: 12px 0;
}
.create-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.create-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.llm-warn {
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
  border: 1px solid color-mix(in srgb, #d97706 40%, var(--border));
  background: color-mix(in srgb, #d97706 8%, var(--surface));
  color: var(--text-2);
}
.llm-warn.block {
  border-color: color-mix(in srgb, #dc2626 45%, var(--border));
  background: color-mix(in srgb, #dc2626 8%, var(--surface));
}
</style>
