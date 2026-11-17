<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, ShieldCheck, Table2, KeyRound, AlertTriangle, Ticket } from 'lucide-vue-next'
import {
  fetchAgents,
  authorizeAgent,
  type AgentRegistryEntry,
  type AgentAuthorization,
} from '../api/registry'
import {
  probeAbac,
  probePolicy,
  inspectApproval,
  type AbacProbeResult,
  type PolicyProbeResult,
  type ApprovalBindingView,
} from '../api/diagnostics'
import { useAuthStore } from '../stores/auth'
import {
  Alert,
  Button,
  DataTable,
  Input,
  PageHeader,
  SectionCard,
  Select,
  Tag,
  toast,
  type DataTableColumn,
} from '../ui'

// 安全试判中心：集中放置“什么-如果”试判、审计速查等只读工具（安全护栏的可视化界面）。
// 侧边栏「工作台 · 安全试判」进入，专家注册表页亦有直达链接。新增工具往 TOOLS + 下方 panel 各加一项即可。
const router = useRouter()
const authStore = useAuthStore()

const TOOLS = [
  { key: 'authorize', label: 'Agent IAM 授权判定', icon: ShieldCheck },
  { key: 'matrix', label: '专家能力矩阵', icon: Table2 },
  { key: 'abac', label: 'ABAC 资源访问试判', icon: KeyRound },
  { key: 'policy', label: 'Policy 注入/风险试判', icon: AlertTriangle },
  { key: 'approval', label: '审批令牌绑定检视', icon: Ticket },
] as const
type ToolKey = (typeof TOOLS)[number]['key']
const activeTool = ref<ToolKey>('authorize')

const loading = ref(false)
const error = ref('')
const agents = ref<AgentRegistryEntry[]>([])

const ROLE_LABELS: Record<string, string> = {
  sense: '态势采集',
  knowledge: '知识检索',
  safety: '安全护栏',
  planner: '计划编排',
  executor: '受控执行',
  verifier: '结果验证',
  memory: '记忆沉淀',
}
const roleLabel = (role: string) => ROLE_LABELS[role] ?? role

function riskRank(risk: string): number {
  const m = (risk || '').toUpperCase().match(/^R(\d)$/)
  return m ? Number(m[1]) : 0
}
function riskTone(risk: string): 'neutral' | 'warn' | 'danger' {
  const r = riskRank(risk)
  if (r >= 4) return 'danger'
  if (r >= 2) return 'warn'
  return 'neutral'
}

async function loadAgents() {
  loading.value = true
  error.value = ''
  try {
    agents.value = await fetchAgents()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载专家列表失败'
  } finally {
    loading.value = false
  }
}

/* —— 工具 1：Agent IAM 授权判定（给定专家 + 工具 + 风险，展示 fail-closed 判定）—— */
const sim = reactive({ agentKey: '', tool: 'kylin.firewall.block_ip', risk: 'R3' })
const simResult = ref<AgentAuthorization | null>(null)
const simBusy = ref(false)
const agentOptions = computed(() =>
  agents.value.map((a) => ({ label: `${a.name}（${a.agentKey}）`, value: a.agentKey })),
)
const riskOptions = ['R0', 'R1', 'R2', 'R3', 'R4', 'R5'].map((r) => ({ label: r, value: r }))

async function runSimulation() {
  if (!sim.agentKey || !sim.tool.trim()) {
    toast.error('请选择专家并填写工具名')
    return
  }
  simBusy.value = true
  simResult.value = null
  try {
    simResult.value = await authorizeAgent(sim.agentKey, sim.tool.trim(), sim.risk)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '判定失败')
  } finally {
    simBusy.value = false
  }
}

/* —— 工具 2：专家能力矩阵（审计视角，只读，从已加载的专家数据聚合，无额外请求）—— */
const matrixColumns: DataTableColumn[] = [
  { key: 'name', label: '专家', minWidth: '160px' },
  { key: 'enabled', label: '状态', width: '80px' },
  { key: 'maxRisk', label: '风险上限', width: '100px' },
  { key: 'toolCount', label: '工具数', width: '80px', align: 'right' },
  { key: 'allowedTools', label: '允许工具', minWidth: '260px' },
  { key: 'domains', label: '知识域', width: '160px' },
]
const matrixRows = computed(() =>
  agents.value.map((a) => ({
    key: a.agentKey,
    name: a.name,
    agentKey: a.agentKey,
    role: a.role,
    enabled: a.enabled,
    maxRisk: a.maxRisk,
    toolCount: a.allowedTools.length,
    allowedTools: a.allowedTools,
    domains: a.knowledgeDomains,
  })),
)

/* —— 工具 3：ABAC 资源访问试判（default-deny + DENY 覆盖）—— */
const abacResourceTypes = [
  { label: 'HOST（主机）', value: 'HOST' },
  { label: 'agent-tool（Agent 工具）', value: 'agent-tool' },
]
const abacActions = [
  { label: 'read', value: 'read' },
  { label: 'operate', value: 'operate' },
  { label: 'execute', value: 'execute' },
]
const abacRiskOptions = [{ label: '（不限）', value: '' }, ...riskOptions]
const abac = reactive({ roles: 'security_operator', resourceType: 'HOST', resourceId: 'prod-db-01', action: 'operate', risk: 'R3' })
const abacResult = ref<AbacProbeResult | null>(null)
const abacBusy = ref(false)
async function runAbac() {
  abacBusy.value = true
  abacResult.value = null
  try {
    abacResult.value = await probeAbac({
      roles: abac.roles.split(',').map((r) => r.trim()).filter(Boolean),
      resourceType: abac.resourceType,
      resourceId: abac.resourceId.trim(),
      action: abac.action,
      risk: abac.risk || undefined,
    })
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '判定失败')
  } finally {
    abacBusy.value = false
  }
}

/* —— 工具 4：PolicyEngine 注入/风险试判（R0–R5 + 命中硬规则）—— */
const policy = reactive({ objective: '关闭 SELinux 和 auditd 安全模块', toolId: 'kylin.service.status', argumentsJson: '' })
const policyResult = ref<PolicyProbeResult | null>(null)
const policyBusy = ref(false)
async function runPolicy() {
  if (!policy.objective.trim()) {
    toast.error('请填写自然语言意图')
    return
  }
  policyBusy.value = true
  policyResult.value = null
  try {
    policyResult.value = await probePolicy({
      objective: policy.objective.trim(),
      toolId: policy.toolId.trim(),
      argumentsJson: policy.argumentsJson.trim() || undefined,
    })
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '判定失败')
  } finally {
    policyBusy.value = false
  }
}
function policyRiskTone(risk: string): 'neutral' | 'warn' | 'danger' {
  return risk === 'R5' ? 'danger' : riskTone(risk)
}

/* —— 工具 5：审批令牌绑定检视（13 个身份字段 + 消费/过期）—— */
const approvalId = ref('')
const approvalResult = ref<ApprovalBindingView | null>(null)
const approvalBusy = ref(false)
const approvalError = ref('')
async function runApprovalInspect() {
  if (!approvalId.value.trim()) {
    toast.error('请填写审批 ID')
    return
  }
  approvalBusy.value = true
  approvalResult.value = null
  approvalError.value = ''
  try {
    approvalResult.value = await inspectApproval(approvalId.value.trim())
  } catch (err) {
    approvalError.value = err instanceof Error ? err.message : '查询失败（审批不存在或不属于当前组织）'
  } finally {
    approvalBusy.value = false
  }
}

watch(agentOptions, (opts) => {
  if (!sim.agentKey && opts.length) sim.agentKey = opts[0].value
})
watch(() => authStore.user?.currentOrganizationId, () => void loadAgents(), { immediate: true })
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader
      title="安全试判"
      description="只读试判工具集：给定输入，实时展示 Agent IAM 授权、ABAC 访问控制、Prompt 注入识别与审批令牌绑定的判定结果与依据。全部无副作用。"
    >
      <template #actions>
        <Button :icon="ArrowLeft" @click="router.push('/registry')">返回专家注册表</Button>
      </template>
    </PageHeader>

    <Alert v-if="error" tone="danger" :title="error" />

    <div class="toolbar">
      <button
        v-for="t in TOOLS"
        :key="t.key"
        type="button"
        class="tool-tab"
        :class="{ 'tool-tab--active': activeTool === t.key }"
        @click="activeTool = t.key"
      >
        <component :is="t.icon" :size="15" />
        <span>{{ t.label }}</span>
      </button>
    </div>

    <!-- 工具 1：Agent IAM 授权判定 -->
    <SectionCard
      v-if="activeTool === 'authorize'"
      title="Agent IAM 授权判定"
      description="给定专家 + 工具 + 风险，查看最小权限 fail-closed 判定（越封套 / 超风险 / 停用 / 未注册即拒）"
    >
      <div class="sim">
        <div class="sim-field">
          <label>专家</label>
          <Select v-model="sim.agentKey" :options="agentOptions" placeholder="选择专家" />
        </div>
        <div class="sim-field sim-field--grow">
          <label>工具</label>
          <Input v-model="sim.tool" placeholder="如 kylin.firewall.block_ip" />
        </div>
        <div class="sim-field">
          <label>风险</label>
          <Select v-model="sim.risk" :options="riskOptions" />
        </div>
        <Button variant="primary" :disabled="simBusy" @click="runSimulation">判定</Button>
      </div>
      <div v-if="simResult" class="sim-result" :class="simResult.allowed ? 'sim-result--ok' : 'sim-result--deny'">
        <Tag :tone="simResult.allowed ? 'ok' : 'danger'">{{ simResult.allowed ? 'ALLOW' : 'DENY' }}</Tag>
        <span>{{ simResult.reason }}</span>
      </div>
    </SectionCard>

    <!-- 工具 2：专家能力矩阵 -->
    <SectionCard
      v-else-if="activeTool === 'matrix'"
      title="专家能力矩阵"
      description="所有专家的能力封套一览（只读审计视角）：风险上限、允许工具、知识域"
    >
      <DataTable :columns="matrixColumns" :data="matrixRows" row-key="key" empty="暂无专家">
        <template #name="{ row }">
          <strong>{{ (row as { name: string }).name }}</strong>
          <div class="muted mono">{{ (row as { agentKey: string }).agentKey }}</div>
          <Tag tone="accent">{{ roleLabel((row as { role: string }).role) }}</Tag>
        </template>
        <template #enabled="{ row }">
          <Tag :tone="(row as { enabled: boolean }).enabled ? 'ok' : 'neutral'">
            {{ (row as { enabled: boolean }).enabled ? '启用' : '停用' }}
          </Tag>
        </template>
        <template #maxRisk="{ row }">
          <Tag :tone="riskTone((row as { maxRisk: string }).maxRisk)">{{ (row as { maxRisk: string }).maxRisk }}</Tag>
        </template>
        <template #toolCount="{ row }">
          <span class="num">{{ (row as { toolCount: number }).toolCount }}</span>
        </template>
        <template #allowedTools="{ row }">
          <div v-if="(row as { allowedTools: string[] }).allowedTools.length" class="chips">
            <code v-for="tool in (row as { allowedTools: string[] }).allowedTools" :key="tool" class="tool">{{ tool }}</code>
          </div>
          <span v-else class="muted">无（不直接操作主机）</span>
        </template>
        <template #domains="{ row }">
          <div v-if="(row as { domains: string[] }).domains.length" class="chips">
            <Tag v-for="d in (row as { domains: string[] }).domains" :key="d" tone="neutral">{{ d }}</Tag>
          </div>
          <span v-else class="muted">—</span>
        </template>
      </DataTable>
    </SectionCard>

    <!-- 工具 3：ABAC 资源访问试判 -->
    <SectionCard
      v-else-if="activeTool === 'abac'"
      title="ABAC 资源访问试判"
      description="给定角色 + 资源 + 动作，查看资源级授权判定（default-deny，任一 DENY 覆盖一切 ALLOW）"
    >
      <div class="sim">
        <div class="sim-field sim-field--grow">
          <label>角色（逗号分隔）</label>
          <Input v-model="abac.roles" placeholder="如 security_operator, org_admin" />
        </div>
        <div class="sim-field">
          <label>资源类型</label>
          <Select v-model="abac.resourceType" :options="abacResourceTypes" />
        </div>
        <div class="sim-field">
          <label>资源</label>
          <Input v-model="abac.resourceId" placeholder="如 prod-db-01 或 *" />
        </div>
        <div class="sim-field">
          <label>动作</label>
          <Select v-model="abac.action" :options="abacActions" />
        </div>
        <div class="sim-field">
          <label>风险</label>
          <Select v-model="abac.risk" :options="abacRiskOptions" />
        </div>
        <Button variant="primary" :disabled="abacBusy" @click="runAbac">判定</Button>
      </div>
      <div v-if="abacResult" class="sim-result" :class="abacResult.allowed ? 'sim-result--ok' : 'sim-result--deny'">
        <Tag :tone="abacResult.allowed ? 'ok' : 'danger'">{{ abacResult.allowed ? 'ALLOW' : 'DENY' }}</Tag>
        <span>{{ abacResult.reason }}</span>
      </div>
    </SectionCard>

    <!-- 工具 4：PolicyEngine 注入/风险试判 -->
    <SectionCard
      v-else-if="activeTool === 'policy'"
      title="Policy 注入/风险试判"
      description="给定自然语言意图 + 工具，查看 R0–R5 风险分级、是否拦截/需审批，以及命中的硬规则（注入 / 关安全模块等，先于可配置 DSL）"
    >
      <div class="sim">
        <div class="sim-field sim-field--grow">
          <label>自然语言意图</label>
          <Input v-model="policy.objective" type="textarea" :rows="2" placeholder="如：关闭 SELinux 和 auditd / rm -rf / ..." />
        </div>
        <div class="sim-field">
          <label>工具</label>
          <Input v-model="policy.toolId" placeholder="如 kylin.service.status" />
        </div>
        <Button variant="primary" :disabled="policyBusy" @click="runPolicy">判定</Button>
      </div>
      <div v-if="policyResult" class="probe-result">
        <div class="probe-tags">
          <Tag :tone="policyRiskTone(policyResult.riskCode)">风险 {{ policyResult.riskCode }}</Tag>
          <Tag :tone="policyResult.denied ? 'danger' : 'ok'">{{ policyResult.denied ? '拦截 DENY' : '放行' }}</Tag>
          <Tag v-if="policyResult.requiresApproval" tone="warn">需人工审批</Tag>
          <span class="muted mono">{{ policyResult.policyVersion }}</span>
        </div>
        <ul v-if="policyResult.hits.length" class="hits">
          <li v-for="h in policyResult.hits" :key="h.policyId">
            <code>{{ h.policyId }}</code>
            <span>{{ h.message }}</span>
            <Tag v-if="h.riskCode" :tone="policyRiskTone(h.riskCode)">{{ h.riskCode }}</Tag>
          </li>
        </ul>
        <p v-else class="muted">未命中任何硬规则</p>
      </div>
    </SectionCard>

    <!-- 工具 5：审批令牌绑定检视 -->
    <SectionCard
      v-else-if="activeTool === 'approval'"
      title="审批令牌绑定检视"
      description="给定审批 ID，展示 SM2 令牌绑定的 13 个身份字段与消费/过期状态——批准的是什么就只能执行什么"
    >
      <div class="sim">
        <div class="sim-field sim-field--grow">
          <label>审批 ID</label>
          <Input v-model="approvalId" placeholder="从审批中心复制审批 ID" />
        </div>
        <Button variant="primary" :disabled="approvalBusy" @click="runApprovalInspect">查询</Button>
      </div>
      <Alert v-if="approvalError" tone="warn" :title="approvalError" />
      <div v-if="approvalResult" class="probe-result">
        <div class="probe-tags">
          <Tag :tone="approvalResult.decision === 'APPROVED' ? 'ok' : approvalResult.decision === 'REJECTED' ? 'danger' : 'neutral'">
            {{ approvalResult.decision }}
          </Tag>
          <Tag :tone="approvalResult.active ? 'ok' : 'neutral'">{{ approvalResult.active ? '可执行窗口内' : '不可执行' }}</Tag>
          <Tag :tone="approvalResult.consumed ? 'warn' : 'neutral'">{{ approvalResult.consumed ? '已消费（一次性）' : '未消费' }}</Tag>
          <Tag v-if="approvalResult.expired" tone="danger">已过期</Tag>
        </div>
        <dl class="kv">
          <div><dt>run</dt><dd><code>{{ approvalResult.runId }}</code></dd></div>
          <div><dt>agent 实例</dt><dd><code>{{ approvalResult.agentInstanceId }}</code></dd></div>
          <div><dt>节点</dt><dd><code>{{ approvalResult.nodeId }}</code></dd></div>
          <div><dt>工具</dt><dd><code>{{ approvalResult.toolId }}</code> <span class="muted">v{{ approvalResult.toolVersion }}</span></dd></div>
          <div><dt>参数 SM3</dt><dd><code class="digest">{{ approvalResult.argumentsDigest }}</code></dd></div>
          <div><dt>风险上限</dt><dd><Tag :tone="riskTone(approvalResult.riskCeiling)">{{ approvalResult.riskCeiling }}</Tag></dd></div>
          <div><dt>执行模式</dt><dd>{{ approvalResult.executionMode }}</dd></div>
          <div><dt>tool_invocation</dt><dd><code>{{ approvalResult.toolInvocationId }}</code></dd></div>
          <div><dt>过期时间</dt><dd>{{ approvalResult.expiresAt ?? '—' }}</dd></div>
        </dl>
      </div>
    </SectionCard>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.tool-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background: var(--surface-2);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
}
.tool-tab:hover {
  color: var(--text);
}
.tool-tab--active {
  border-color: var(--accent);
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 10%, var(--surface-2));
}
.sim {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
}
.sim-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 160px;
}
.sim-field--grow {
  flex: 1;
  min-width: 220px;
}
.sim-field label {
  font-size: 12px;
  color: var(--text-3);
}
.sim-result {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  font-size: 13px;
}
.sim-result--ok {
  border-color: color-mix(in srgb, var(--ok, #16a34a) 50%, var(--border));
}
.sim-result--deny {
  border-color: color-mix(in srgb, var(--danger, #dc2626) 50%, var(--border));
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tool {
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 6px;
  background: var(--surface-3);
  color: var(--text-2);
}
.mono {
  font-size: 11px;
}
.probe-result {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.probe-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.hits {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.hits li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background: var(--surface-2);
  font-size: 13px;
}
.hits code {
  font-size: 12px;
  color: var(--text-2);
}
.kv {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 6px 20px;
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius);
  border: 1px dashed var(--border);
  background: color-mix(in srgb, var(--surface-2) 55%, transparent);
}
.kv > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.kv dt {
  min-width: 96px;
  font-size: 12px;
  color: var(--text-3);
}
.kv dd {
  margin: 0;
  font-size: 12px;
  color: var(--text-2);
  word-break: break-all;
}
.kv dd code {
  font-size: 12px;
}
.digest {
  word-break: break-all;
}
</style>
