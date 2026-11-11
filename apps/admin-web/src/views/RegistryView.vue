<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Bot, ShieldCheck, Layers, BadgeCheck, SlidersHorizontal } from 'lucide-vue-next'
import {
  fetchAgents,
  fetchDomains,
  setAgentEnabled,
  type AgentRegistryEntry,
  type KnowledgeDomain,
} from '../api/registry'
import { useAuthStore } from '../stores/auth'
import {
  Alert,
  Button,
  MetricCard,
  PageHeader,
  SectionCard,
  Switch,
  Tag,
  toast,
} from '../ui'

const router = useRouter()
const authStore = useAuthStore()
const canWrite = computed(() => authStore.hasPermission('registry:write'))

const loading = ref(false)
const error = ref('')
const agents = ref<AgentRegistryEntry[]>([])
const domains = ref<KnowledgeDomain[]>([])

const ROLE_LABELS: Record<string, string> = {
  sense: '态势采集',
  knowledge: '知识检索',
  safety: '安全护栏',
  planner: '计划编排',
  executor: '受控执行',
  verifier: '结果验证',
  memory: '记忆沉淀',
}
function roleLabel(role: string) {
  return ROLE_LABELS[role] ?? role
}

const metricCards = computed(() => [
  { label: '专家 Agent', value: agents.value.length, icon: Bot, tone: 'accent' as const, description: '注册的专家 Agent 总数' },
  { label: '启用中', value: agents.value.filter((a) => a.enabled).length, icon: BadgeCheck, tone: 'ok' as const, description: '当前可被调度的专家' },
  { label: '写能力专家', value: agents.value.filter((a) => riskRank(a.maxRisk) >= 2).length, icon: ShieldCheck, tone: 'warn' as const, description: '风险上限达 R2+（可写）的专家' },
  { label: '知识域', value: domains.value.length, icon: Layers, tone: 'accent' as const, description: '专家可绑定的知识范围' },
])

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

const domainName = (code: string) => domains.value.find((d) => d.code === code)?.name ?? code

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [a, d] = await Promise.all([fetchAgents(), fetchDomains()])
    agents.value = a
    domains.value = d
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载专家注册表失败'
  } finally {
    loading.value = false
  }
}

async function onToggle(agent: AgentRegistryEntry) {
  const next = !agent.enabled
  try {
    const updated = await setAgentEnabled(agent.agentKey, next)
    agent.enabled = updated.enabled
    toast.success(`${agent.name} 已${updated.enabled ? '启用' : '停用'}`)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '操作失败')
  }
}

watch(() => authStore.user?.currentOrganizationId, () => void loadAll(), { immediate: true })
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="专家注册表" description="管理专家 Agent 的能力范围与知识域">
      <template #actions>
        <!-- 直达安全试判中心（侧边栏亦有一级入口），从专家能力顺势看护栏判定 -->
        <Button link size="sm" :icon="SlidersHorizontal" @click="router.push('/diagnostics')">
          安全试判
        </Button>
      </template>
    </PageHeader>

    <Alert v-if="error" tone="danger" :title="error" />

    <!--
      口径红线：注册表已接入任务下发路径（DispatchGate 第 8 步查本表），这里必须**准确**写出
      强制点与其边界——既不能继续说「尚未接入」（已过期），也不能说成「所有 Agent 都受本表约束」。
      真实边界：recruit_agent 的 agent_key 已改为**必填且招募时校验**（未注册 / 已停用即拒绝招募），
      因此全部子 Agent 都有身份、都受本表约束；根 Agent 由人直接发起，不在注册表内，不判定。
    -->
    <Alert tone="info" title="已接入任务下发路径：全部子 Agent 的逐次工具调用受本表约束">
      下方的工具封套与风险上限由注册表的 <code>authorize</code> 判定强制（fail-closed：越封套 / 超风险 / 停用 / 未注册即拒），
      可在「安全试判」页逐条验证。<strong>强制点在 DispatchGate 第 8 步</strong>（工具维 ABAC 之后、主机维 ABAC 之前）：
      招募子 Agent 时<strong>必须声明一个已注册且启用的专家</strong>（未注册或已停用直接拒绝招募），
      因此每个子 Agent 的每一次工具调用都要再过一遍本表——在这里停用某个专家、删掉它的某把工具、
      调低它的风险上限，下一次下发即刻生效，拒绝码为 <code>AGENT_ENVELOPE_DENIED</code>。
      边界：<strong>由人直接发起的根 Agent 不在注册表内</strong>（没有专家身份），它仍只受 RBAC + 资源级 ABAC + 风险分级 + 审批门约束。
    </Alert>

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

    <SectionCard title="专家 Agent" description="每个专家登记了工具封套与风险上限，由下发门禁逐次调用强制；生效范围见上方说明">
      <div class="agents">
        <div v-for="agent in agents" :key="agent.agentKey" class="agent-card" :class="{ 'agent-card--off': !agent.enabled }">
          <div class="agent-head">
            <div class="agent-id">
              <strong>{{ agent.name }}</strong>
              <small class="muted">{{ agent.agentKey }}</small>
            </div>
            <Switch :model-value="agent.enabled" :disabled="!canWrite" @update:model-value="onToggle(agent)" />
          </div>
          <div class="agent-tags">
            <Tag tone="accent">{{ roleLabel(agent.role) }}</Tag>
            <Tag :tone="riskTone(agent.maxRisk)">上限 {{ agent.maxRisk }}</Tag>
            <Tag tone="neutral">预算 {{ Math.round(agent.budgetTokens / 1000) }}k</Tag>
          </div>
          <p v-if="agent.description" class="agent-desc">{{ agent.description }}</p>
          <div class="agent-section" v-if="agent.knowledgeDomains.length">
            <span class="agent-label">知识域</span>
            <div class="chips">
              <Tag v-for="code in agent.knowledgeDomains" :key="code" tone="neutral">{{ domainName(code) }}</Tag>
            </div>
          </div>
          <div class="agent-section">
            <span class="agent-label">允许工具 ({{ agent.allowedTools.length }})</span>
            <div class="chips" v-if="agent.allowedTools.length">
              <code v-for="tool in agent.allowedTools" :key="tool" class="tool">{{ tool }}</code>
            </div>
            <span v-else class="muted">无（不直接操作主机）</span>
          </div>
        </div>
      </div>
    </SectionCard>

    <!-- 知识域：纯说明性参考（专家 knowledge_domains 字段的取值字典），只读、不可编辑。
         用定义列表 + 弱化样式呈现，与上方可交互的专家卡片明确区分。 -->
    <section class="ref-block">
      <header class="ref-block__head">
        <Layers :size="14" />
        <span class="ref-block__title">知识域</span>
        <Tag tone="neutral">只读参考</Tag>
        <span class="ref-block__hint">专家可绑定的知识范围字典，说明性内容，不可在此编辑</span>
      </header>
      <dl class="domain-ref">
        <div v-for="d in domains" :key="d.code" class="domain-ref__item">
          <dt>{{ d.name }} <code>{{ d.code }}</code></dt>
          <dd v-if="d.description">{{ d.description }}</dd>
        </div>
      </dl>
    </section>
  </div>
</template>

<style scoped>
.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.agents {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}
.agent-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background: var(--surface-2);
}
.agent-card--off {
  opacity: 0.6;
}
.agent-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.agent-id {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.agent-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.agent-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.5;
}
.agent-section {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.agent-label {
  font-size: 12px;
  color: var(--text-3);
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
/* 只读参考块：虚线边框 + 弱化配色，与上方可交互的专家卡片（实线边框 + 开关）明确区分。 */
.ref-block {
  margin-top: 8px;
  padding: 12px 14px;
  border-radius: var(--radius);
  border: 1px dashed var(--border);
  background: color-mix(in srgb, var(--surface-2) 55%, transparent);
}
.ref-block__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
  color: var(--text-3);
}
.ref-block__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}
.ref-block__hint {
  font-size: 12px;
  color: var(--text-3);
}
.domain-ref {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 4px 20px;
  margin: 0;
}
.domain-ref__item {
  padding: 4px 0;
}
.domain-ref dt {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  color: var(--text-2);
}
.domain-ref dt code {
  font-size: 11px;
  color: var(--text-3);
}
.domain-ref dd {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.4;
}
</style>
