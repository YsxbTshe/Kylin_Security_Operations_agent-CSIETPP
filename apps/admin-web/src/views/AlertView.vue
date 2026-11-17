<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Bell, CircleCheck, TriangleAlert } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { fetchAlerts, type AlertSummary } from '../api/alerts'
import { intakeIncidentFromAlert } from '../api/incidents'
import { useAuthStore } from '../stores/auth'
import { alertStatusLabel } from '../utils/labels'
import {
  Alert,
  Button,
  DataTable,
  MetricCard,
  PageHeader,
  SectionCard,
  Tag,
  toast,
  type DataTableColumn,
} from '../ui'

const authStore = useAuthStore()
const loading = ref(false)
const error = ref('')
const rows = ref<AlertSummary[]>([])

const metrics = computed(() => [
  { label: '告警总数', value: rows.value.length, icon: Bell, tone: 'accent' as const, description: '当前组织下记录的告警事件' },
  {
    label: '高危告警',
    value: rows.value.filter((item) => item.level.toUpperCase() === 'HIGH').length,
    icon: TriangleAlert,
    tone: 'danger' as const,
    description: '需要优先研判和处置的高风险事件',
  },
  {
    label: '已关闭',
    value: rows.value.filter((item) => item.status.toUpperCase() === 'CLOSED').length,
    icon: CircleCheck,
    tone: 'ok' as const,
    description: '已完成处置或确认关闭的告警',
  },
])

const router = useRouter()
const converting = ref('')
const canRespond = computed(() => authStore.hasPermission('incident:respond'))

const columns: DataTableColumn[] = [
  { key: 'id', label: '告警 ID', width: '140px' },
  { key: 'level', label: '等级', width: '110px' },
  { key: 'type', label: '类型', minWidth: '160px' },
  { key: 'host', label: '主机', minWidth: '150px' },
  { key: 'status', label: '状态', width: '120px' },
  { key: 'suggestion', label: '处置建议', minWidth: '240px' },
  { key: 'actions', label: '', width: '90px' },
]

async function convertToIncident(row: AlertSummary) {
  converting.value = row.id
  try {
    const result = await intakeIncidentFromAlert(row.id)
    toast.success(result.merged ? '已合并到既有事件' : '已创建安全事件')
    router.push('/incidents')
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '转事件失败')
  } finally {
    converting.value = ''
  }
}

function levelTone(level: string): 'danger' | 'warn' | 'neutral' {
  const normalized = level.toUpperCase()
  if (normalized === 'HIGH') return 'danger'
  if (normalized === 'MEDIUM') return 'warn'
  return 'neutral'
}

function statusTone(status: string): 'danger' | 'ok' | 'neutral' {
  const normalized = status.toUpperCase()
  if (normalized === 'OPEN') return 'danger'
  if (normalized === 'CLOSED') return 'ok'
  return 'neutral'
}

async function loadAlerts() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await fetchAlerts()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载告警失败'
  } finally {
    loading.value = false
  }
}

watch(() => authStore.user?.currentOrganizationId, () => void loadAlerts(), { immediate: true })
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="告警" description="查看告警事件和处置建议" />

    <Alert v-if="error" tone="danger" :title="error" />

    <div class="metric-grid">
      <MetricCard
        v-for="metric in metrics"
        :key="metric.label"
        :label="metric.label"
        :value="metric.value"
        :icon="metric.icon"
        :tone="metric.tone"
        :description="metric.description"
      />
    </div>

    <SectionCard title="告警列表" flush>
      <DataTable :columns="columns" :data="rows" row-key="id" empty="暂无告警">
        <template #level="{ row }">
          <Tag :tone="levelTone(row.level)">{{ row.level }}</Tag>
        </template>
        <template #status="{ row }">
          <Tag :tone="statusTone(row.status)">{{ alertStatusLabel(row.status) }}</Tag>
        </template>
        <template #actions="{ row }">
          <Button v-if="canRespond" size="sm" variant="subtle" :disabled="converting === row.id" @click="convertToIncident(row)">
            转事件
          </Button>
        </template>
      </DataTable>
    </SectionCard>
  </div>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
