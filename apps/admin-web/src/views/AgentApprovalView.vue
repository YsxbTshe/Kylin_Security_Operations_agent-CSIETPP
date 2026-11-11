<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Check, RefreshCw, X } from 'lucide-vue-next'
import {
  decideApproval,
  fetchPendingApprovals,
  fetchRuntimeApprovals,
  type RuntimeApprovalItem,
} from '../api/agent-runtime'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  DataTable,
  Dialog,
  PageHeader,
  SectionCard,
  Tabs,
  Tag,
  confirm,
  toast,
  type DataTableColumn,
} from '../ui'
import { formatInstant } from '../utils/format'
import {
  approvalDecisionLabel,
  approvalDecisionTone,
  executionModeLabel,
  riskCodeTone,
} from '../utils/labels'

const router = useRouter()
const authStore = useAuthStore()

/** 决策权限（V8 迁移新增的专用码）；只读角色仍可查看队列，但不给决策入口。 */
const canDecide = computed(() => authStore.hasPermission('agent:approve'))

const activeTab = ref('pending')
const loadingPending = ref(false)
const loadingDecided = ref(false)
const deciding = ref('')
const pendingRows = ref<RuntimeApprovalItem[]>([])
const decidedRows = ref<RuntimeApprovalItem[]>([])
const detailTarget = ref<RuntimeApprovalItem | null>(null)

const tabs = [
  { value: 'pending', label: '待审' },
  { value: 'decided', label: '已决' },
]

const pendingColumns: DataTableColumn[] = [
  { key: 'toolId', label: '工具', minWidth: '200px' },
  { key: 'riskCeiling', label: '风险等级', width: '100px' },
  { key: 'nodeId', label: '目标节点', width: '130px' },
  { key: 'executionMode', label: '执行模式', width: '110px' },
  { key: 'runId', label: '所属任务', width: '120px' },
  { key: 'createdAt', label: '申请时间', width: '170px' },
  { key: 'actions', label: '操作', width: '210px', align: 'right' },
]

const decidedColumns: DataTableColumn[] = [
  { key: 'toolId', label: '工具', minWidth: '200px' },
  { key: 'riskCeiling', label: '风险等级', width: '100px' },
  { key: 'decision', label: '决策', width: '100px' },
  { key: 'decidedByUserId', label: '决策人', width: '140px' },
  { key: 'decidedAt', label: '决策时间', width: '170px' },
  { key: 'runId', label: '所属任务', width: '120px' },
]

const detailArguments = computed(() => {
  if (!detailTarget.value) return ''
  try {
    return JSON.stringify(JSON.parse(detailTarget.value.argumentsJson), null, 2)
  } catch {
    return detailTarget.value.argumentsJson
  }
})

async function loadPending() {
  loadingPending.value = true
  try {
    pendingRows.value = await fetchPendingApprovals()
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '加载待审列表失败')
  } finally {
    loadingPending.value = false
  }
}

async function loadDecided() {
  loadingDecided.value = true
  try {
    const all = await fetchRuntimeApprovals({ limit: 100 })
    decidedRows.value = all.filter((item) => item.decision !== 'PENDING')
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '加载已决列表失败')
  } finally {
    loadingDecided.value = false
  }
}

function reload() {
  void loadPending()
  void loadDecided()
}

async function handleDecide(approval: RuntimeApprovalItem, approve: boolean) {
  const action = approve ? '批准' : '拒绝'
  const ok = await confirm({
    title: `${action}审批`,
    message: `确定${action}工具「${approval.toolId}」在节点「${approval.nodeId}」上的调用申请吗？`,
    confirmText: action,
    tone: approve ? 'default' : 'danger',
  })
  if (!ok) return
  deciding.value = approval.id
  try {
    await decideApproval(approval.id, approve, '')
    toast.success(`已${action}该审批`)
    reload()
  } catch (err) {
    toast.error(err instanceof Error ? err.message : `${action}失败`)
  } finally {
    deciding.value = ''
  }
}

function openRunTimeline(runId: string) {
  void router.push(`/agent-runs/${encodeURIComponent(runId)}`)
}

watch(() => authStore.currentOrganizationId, () => {
  reload()
}, { immediate: true })

// 待审列表轮询：run 进入 WAITING 后审批人无需手动刷新即可看到新待审单，避免演示时“卡住”的观感。
// 只静默刷新待审（不弹 loading 遮罩），已决列表随决策动作按需刷新。
const POLL_MS = 5000
let pollTimer: number | null = null
onMounted(() => {
  pollTimer = window.setInterval(() => {
    fetchPendingApprovals()
      .then((rows) => { pendingRows.value = rows })
      .catch(() => { /* 轮询失败静默，下一拍重试 */ })
  }, POLL_MS)
})
onUnmounted(() => {
  if (pollTimer != null) window.clearInterval(pollTimer)
})
</script>

<template>
  <div class="page" v-loading="loadingPending || loadingDecided">
    <PageHeader
      title="审批中心"
      description="Agent Runtime 的工具调用审批：待审申请可批准或拒绝，已决记录用于追溯。"
    >
      <template #actions>
        <Button :icon="RefreshCw" @click="reload">刷新</Button>
      </template>
    </PageHeader>

    <SectionCard flush>
      <Tabs v-model="activeTab" :tabs="tabs" class="tabs-pad">
        <template #pending>
          <DataTable
            :columns="pendingColumns"
            :data="pendingRows"
            row-key="id"
            :loading="loadingPending"
            empty="暂无待审申请"
          >
            <template #toolId="{ row }">
              <div class="cell-main">{{ (row as RuntimeApprovalItem).toolId }}</div>
              <div class="cell-sub num">{{ (row as RuntimeApprovalItem).toolVersion }}</div>
            </template>
            <template #riskCeiling="{ row }">
              <Tag :tone="riskCodeTone((row as RuntimeApprovalItem).riskCeiling)">
                {{ (row as RuntimeApprovalItem).riskCeiling }}
              </Tag>
            </template>
            <template #executionMode="{ row }">
              {{ executionModeLabel((row as RuntimeApprovalItem).executionMode) }}
            </template>
            <template #runId="{ row }">
              <Button size="sm" link @click="openRunTimeline((row as RuntimeApprovalItem).runId)">时间线</Button>
            </template>
            <template #createdAt="{ row }">
              <span class="num">{{ formatInstant((row as RuntimeApprovalItem).createdAt) }}</span>
            </template>
            <template #actions="{ row }">
              <div class="row-actions">
                <Button size="sm" link @click="detailTarget = row as RuntimeApprovalItem">参数</Button>
                <!--
                  无 agent:approve 的角色（auditor / analyst / viewer）仍能进本页看待审队列，
                  但不显示决策按钮——服务端本就会 403，提前隐藏免得点了才报错。
                  真正的闸门在后端：功能权限 + ApprovalService.decide 的资源级 ABAC。
                -->
                <template v-if="canDecide">
                  <Button
                    size="sm"
                    variant="primary"
                    :icon="Check"
                    :loading="deciding === (row as RuntimeApprovalItem).id"
                    @click="handleDecide(row as RuntimeApprovalItem, true)"
                  >
                    批准
                  </Button>
                  <Button
                    size="sm"
                    variant="danger"
                    :icon="X"
                    :loading="deciding === (row as RuntimeApprovalItem).id"
                    @click="handleDecide(row as RuntimeApprovalItem, false)"
                  >
                    拒绝
                  </Button>
                </template>
                <span v-else class="no-permission">无审批权限</span>
              </div>
            </template>
          </DataTable>
        </template>

        <template #decided>
          <DataTable
            :columns="decidedColumns"
            :data="decidedRows"
            row-key="id"
            :loading="loadingDecided"
            empty="暂无已决审批"
          >
            <template #toolId="{ row }">
              <div class="cell-main">{{ (row as RuntimeApprovalItem).toolId }}</div>
              <div class="cell-sub num">{{ (row as RuntimeApprovalItem).toolVersion }}</div>
            </template>
            <template #riskCeiling="{ row }">
              <Tag :tone="riskCodeTone((row as RuntimeApprovalItem).riskCeiling)">
                {{ (row as RuntimeApprovalItem).riskCeiling }}
              </Tag>
            </template>
            <template #decision="{ row }">
              <Tag :tone="approvalDecisionTone((row as RuntimeApprovalItem).decision)">
                {{ approvalDecisionLabel((row as RuntimeApprovalItem).decision) }}
              </Tag>
            </template>
            <template #decidedByUserId="{ row }">
              {{ (row as RuntimeApprovalItem).decidedByUserId || '—' }}
            </template>
            <template #decidedAt="{ row }">
              <span class="num">{{ formatInstant((row as RuntimeApprovalItem).decidedAt) }}</span>
            </template>
            <template #runId="{ row }">
              <Button size="sm" link @click="openRunTimeline((row as RuntimeApprovalItem).runId)">时间线</Button>
            </template>
          </DataTable>
        </template>
      </Tabs>
    </SectionCard>

    <Dialog
      :open="detailTarget != null"
      title="调用参数"
      :description="detailTarget ? `${detailTarget.toolId} · 风险 ${detailTarget.riskCeiling}` : ''"
      width="640px"
      @update:open="(value: boolean) => { if (!value) detailTarget = null }"
    >
      <pre class="args-body">{{ detailArguments }}</pre>
      <template #footer>
        <Button @click="detailTarget = null">关闭</Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.tabs-pad {
  padding: 16px 19px 0;
}
.cell-main {
  font-size: 13px;
  font-weight: 500;
}
.cell-sub {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 2px;
}
.row-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}
.no-permission {
  color: var(--text-3);
  font-size: 12px;
  align-self: center;
}
.args-body {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
  font-size: 12px;
  line-height: 1.6;
  max-height: 46vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
