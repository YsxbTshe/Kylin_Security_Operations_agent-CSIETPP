<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import SectionCard from '../components/SectionCard.vue'
import MetricCard from '../components/MetricCard.vue'
import type { ScreenDashboardResponse } from '../api/screen'
import { activeAgentCount, agentStatusKey, eventTone } from './shared'

const TrendChart = defineAsyncComponent(() => import('../components/TrendChart.vue'))

const props = defineProps<{
  dashboard: ScreenDashboardResponse | null
  loading: boolean
}>()

const agents = computed(() => props.dashboard?.agents ?? [])
const events = computed(() => props.dashboard?.events ?? [])
const safeOps = computed(() => props.dashboard?.safeOps)
const hostEvents = computed(() => safeOps.value?.hostEvents ?? [])
const redisStatus = computed(() => safeOps.value?.redisStreamStatus)
const hostRuntime = computed(() => safeOps.value?.hostRuntimeStatus)
const nativeGuard = computed(() => safeOps.value?.nativeGuardStatus)
const nodeExporter = computed(() => safeOps.value?.nodeExporterStatus)
const nodeExporterHosts = computed(() => safeOps.value?.nodeExporterHosts ?? [])
const nodeExporterView = ref<'metrics' | 'hosts'>('metrics')
const nativeGuardAtRisk = computed(() => (nativeGuard.value?.criticalHosts ?? 0) + (nativeGuard.value?.warningHosts ?? 0))
const redisPending = computed(() => (redisStatus.value?.assistantPending ?? 0) + (redisStatus.value?.hostTaskPending ?? 0) + (redisStatus.value?.approvalPending ?? 0))
const redisLength = computed(() => (redisStatus.value?.assistantStreamLength ?? 0) + (redisStatus.value?.hostTaskStreamLength ?? 0) + (redisStatus.value?.approvalStreamLength ?? 0))
const runtimeEvents = computed(() => hostEvents.value.length ? hostEvents.value : events.value)
const mcpToolTop = computed(() => safeOps.value?.mcpToolTop ?? [])
const mcpToolCallCount = computed(() => mcpToolTop.value.reduce((sum, item) => sum + item.count, 0))
const trendSeries = computed(() => props.dashboard?.trend?.map((item) => item.count) ?? [])
const trendLabels = computed(() => props.dashboard?.trend?.map((item) => item.label) ?? [])

const grpcConnectedAgents = computed(() => safeOps.value?.grpcConnectedAgents ?? 0)

function redisTone(status?: string) {
  if (!status || status === 'DISABLED') return 'info'
  if (status === 'UP') return 'success'
  return 'danger'
}

function nativeGuardTone() {
  if ((nativeGuard.value?.criticalHosts ?? 0) > 0) return 'danger'
  if ((nativeGuard.value?.warningHosts ?? 0) > 0 || (nativeGuard.value?.staleHosts ?? 0) > 0) return 'warning'
  if ((nativeGuard.value?.healthyHosts ?? 0) > 0) return 'success'
  return 'info'
}

function nodeExporterTone() {
  if ((nodeExporter.value?.downHosts ?? 0) > 0) return 'danger'
  if ((nodeExporter.value?.staleHosts ?? 0) > 0 || (nodeExporter.value?.disabledHosts ?? 0) > 0) return 'warning'
  if ((nodeExporter.value?.upHosts ?? 0) > 0) return 'success'
  return 'info'
}

function formatAvgPercent(value?: number | null) {
  return typeof value === 'number' ? `${value.toFixed(1)}%` : '—'
}

function formatMetric(value?: number | null, suffix = '') {
  return typeof value === 'number' ? `${value.toFixed(1)}${suffix}` : '—'
}

function nodeExporterStatusTone(status?: string) {
  const normalized = (status || '').toUpperCase()
  if (normalized === 'UP') return 'success'
  if (normalized === 'DOWN' || normalized === 'ERROR') return 'danger'
  if (normalized === 'STALE' || normalized === 'DISABLED' || normalized === 'UNKNOWN') return 'warning'
  return 'info'
}

const runtimeNodes = computed(() => [
  {
    label: 'MCP Tool Calls',
    value: mcpToolCallCount.value,
    suffix: '次',
    hint: `${mcpToolTop.value.length} 个工具进入排行`,
    tone: 'info',
  },
  {
    label: 'Resource Lock',
    value: hostRuntime.value?.runningTasks ?? 0,
    suffix: '项',
    hint: `队列 ${hostRuntime.value?.queuedTasks ?? 0} · 本地锁 ${hostRuntime.value?.localActiveLocks ?? 0}`,
    tone: (hostRuntime.value?.queuedTasks ?? 0) > 0 ? 'warning' : 'success',
  },
  {
    label: 'Rollback',
    value: safeOps.value?.rollbackRecords ?? 0,
    suffix: '条',
    hint: '变更回滚上下文',
    tone: 'info',
  },
  {
    label: 'Kylin Guard',
    value: nativeGuardAtRisk.value,
    suffix: '台',
    hint: `健康 ${nativeGuard.value?.healthyHosts ?? 0} · 探针 C${nativeGuard.value?.criticalProbes ?? 0}/W${nativeGuard.value?.warningProbes ?? 0} · stale ${nativeGuard.value?.staleHosts ?? 0}`,
    tone: nativeGuardTone(),
  },
  {
    label: 'Node Exporter',
    value: nodeExporter.value?.upHosts ?? 0,
    suffix: '台',
    hint: `down ${nodeExporter.value?.downHosts ?? 0} · disabled ${nodeExporter.value?.disabledHosts ?? 0} · stale ${nodeExporter.value?.staleHosts ?? 0} · CPU ${formatAvgPercent(nodeExporter.value?.avgCpuUsagePercent)}`,
    tone: nodeExporterTone(),
  },
])

const chainSteps = computed(() => {
  const countOf = (type: string) => runtimeEvents.value.filter((event) => event.type === type).length
  return [
    { label: '创建', type: 'TASK_CREATED' },
    { label: '策略评估', type: 'RISK_EVALUATED' },
    { label: '本地校验', type: 'LOCAL_GUARDRAIL_CHECKED' },
    { label: 'MCP 调用', type: 'MCP_TOOL_CALLED' },
    { label: '执行完成', type: 'EXECUTION_FINISHED' },
    { label: '任务完成', type: 'TASK_COMPLETED' },
  ].map((step) => ({ ...step, count: countOf(step.type) }))
})

const kpis = computed(() => [
  {
    label: 'gRPC/mTLS',
    value: grpcConnectedAgents.value,
    suffix: '个',
    tone: grpcConnectedAgents.value > 0 ? ('success' as const) : ('primary' as const),
    footer: `ACTIVE 证书 ${safeOps.value?.activeCertificates ?? 0} · 临期 ${safeOps.value?.expiringCertificates ?? 0}`,
    footerTone: grpcConnectedAgents.value < activeAgentCount(props.dashboard) ? ('warning' as const) : ('success' as const),
  },
  {
    label: '待审批',
    value: safeOps.value?.pendingApprovals ?? 0,
    suffix: '条',
    tone: (safeOps.value?.pendingApprovals ?? 0) > 0 ? ('warning' as const) : ('primary' as const),
    footer: 'R3/R4 人工确认',
    footerTone: (safeOps.value?.pendingApprovals ?? 0) > 0 ? ('warning' as const) : ('muted' as const),
  },
  {
    label: 'R5 拦截',
    value: safeOps.value?.r5Blocked ?? 0,
    suffix: '次',
    tone: (safeOps.value?.r5Blocked ?? 0) > 0 ? ('danger' as const) : ('success' as const),
    footer: 'Policy Engine 阻断',
    footerTone: (safeOps.value?.r5Blocked ?? 0) > 0 ? ('danger' as const) : ('success' as const),
  },
  {
    label: '资源锁',
    value: safeOps.value?.activeResourceLocks ?? 0,
    suffix: '把',
    tone: (safeOps.value?.activeResourceLocks ?? 0) > 0 ? ('warning' as const) : ('primary' as const),
    footer: `运行 ${hostRuntime.value?.runningTasks ?? 0} · 队列 ${hostRuntime.value?.queuedTasks ?? 0}`,
    footerTone: (safeOps.value?.activeResourceLocks ?? 0) > 0 ? ('warning' as const) : ('muted' as const),
  },
  {
    label: '回滚记录',
    value: safeOps.value?.rollbackRecords ?? 0,
    suffix: '条',
    tone: 'primary' as const,
    footer: 'rollback_id 留痕',
    footerTone: 'muted' as const,
  },
  {
    label: 'Native Guard',
    value: nativeGuardAtRisk.value,
    suffix: '台',
    tone: (nativeGuard.value?.criticalHosts ?? 0) > 0 ? ('danger' as const) : nativeGuardAtRisk.value > 0 ? ('warning' as const) : ('primary' as const),
    footer: `主机 C${nativeGuard.value?.criticalHosts ?? 0}/W${nativeGuard.value?.warningHosts ?? 0} · 探针 C${nativeGuard.value?.criticalProbes ?? 0}/W${nativeGuard.value?.warningProbes ?? 0}`,
    footerTone: (nativeGuard.value?.criticalHosts ?? 0) > 0 ? ('danger' as const) : nativeGuardAtRisk.value > 0 ? ('warning' as const) : ('muted' as const),
  },
  {
    label: 'node_exporter',
    value: nodeExporter.value?.upHosts ?? 0,
    suffix: '台',
    tone: (nodeExporter.value?.downHosts ?? 0) > 0 ? ('danger' as const) : ((nodeExporter.value?.staleHosts ?? 0) > 0 || (nodeExporter.value?.disabledHosts ?? 0) > 0) ? ('warning' as const) : ('primary' as const),
    footer: `CPU ${formatAvgPercent(nodeExporter.value?.avgCpuUsagePercent)} · Mem ${formatAvgPercent(nodeExporter.value?.avgMemoryUsagePercent)}`,
    footerTone: (nodeExporter.value?.downHosts ?? 0) > 0 ? ('danger' as const) : ((nodeExporter.value?.staleHosts ?? 0) > 0 || (nodeExporter.value?.disabledHosts ?? 0) > 0) ? ('warning' as const) : ('muted' as const),
  },
])
</script>

<template>
  <section class="kpi-row">
    <MetricCard
      v-for="item in kpis"
      :key="item.label"
      :label="item.label"
      :value="item.value"
      :suffix="item.suffix"
      :tone="item.tone"
      :footer="item.footer"
      :footer-tone="item.footerTone"
    />
  </section>

  <section class="screen-grid">
    <SectionCard title="gRPC/mTLS Agent 矩阵" :hint="`${grpcConnectedAgents} 条 ControlStream · 证书吊销 ${safeOps?.revokedCertificates ?? 0}`" class="screen-grid__span-2 screen-grid__row-2">
      <div class="agent-grid agent-grid--two">
        <div
          v-for="item in agents"
          :key="item.name"
          class="agent-card"
          :data-status="agentStatusKey(item.status)"
        >
          <div class="agent-card__head">
            <div class="agent-card__title">{{ item.name }}</div>
            <span class="agent-card__dot" />
          </div>
          <div class="agent-card__status">{{ item.status }}</div>
          <div class="agent-card__detail">{{ item.detail }}</div>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="Host Runtime 本地调度" :hint="`运行 ${hostRuntime?.runningTasks ?? 0} · 队列 ${hostRuntime?.queuedTasks ?? 0} · 并发 ${hostRuntime?.maxParallel ?? 0}`">
      <div class="safeops-pipeline safeops-pipeline--two">
        <div
          v-for="node in runtimeNodes"
          :key="node.label"
          class="safeops-node"
          :data-tone="node.tone"
        >
          <span class="safeops-node__label">{{ node.label }}</span>
          <strong>{{ node.value }}<small>{{ node.suffix }}</small></strong>
          <span class="safeops-node__hint">{{ node.hint }}</span>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="MCP Manifest TopN" :hint="`${mcpToolCallCount} 次调用`">
      <ul class="rank-list">
        <li v-for="(item, index) in mcpToolTop" :key="item.toolName" class="rank-list__item">
          <div class="rank-list__index">{{ String(index + 1).padStart(2, '0') }}</div>
          <div>
            <div class="rank-list__label">{{ item.toolName }}</div>
            <div class="rank-list__sub">manifest tool</div>
          </div>
          <div class="rank-list__value">{{ item.count }}</div>
        </li>
        <li v-if="!loading && mcpToolTop.length === 0" class="rank-list--empty">暂无 MCP 调用</li>
      </ul>
    </SectionCard>

    <SectionCard title="Redis Stream 事件流" :hint="`${redisStatus?.status ?? 'UNKNOWN'} · pending ${redisPending} · DLQ ${redisStatus?.deadLetterLength ?? 0}`" class="screen-grid__span-2">
      <div class="safeops-pipeline safeops-pipeline--two stream-health">
        <div class="safeops-node" :data-tone="redisTone(redisStatus?.status)">
          <span class="safeops-node__label">Stream Status</span>
          <strong>{{ redisStatus?.status ?? 'UNKNOWN' }}</strong>
          <span class="safeops-node__hint">消息 {{ redisLength }} · pending {{ redisPending }}</span>
        </div>
        <!-- outbox 计数已随后端 outbox_event 表下线移除（该表无消费者，数字不代表投递健康度）；
             这里只保留死信队列与消费失败两个还有真实来源的信号。 -->
        <div class="safeops-node" :data-tone="(redisStatus?.deadLetterLength ?? 0) > 0 || (redisStatus?.consumerFailures ?? 0) > 0 ? 'danger' : 'success'">
          <span class="safeops-node__label">DLQ / 消费失败</span>
          <strong>{{ redisStatus?.deadLetterLength ?? 0 }}<small>条</small></strong>
          <span class="safeops-node__hint">消费失败 {{ redisStatus?.consumerFailures ?? 0 }} 条</span>
        </div>
      </div>
      <ul class="event-list event-list--wide">
        <li
          v-for="item in runtimeEvents"
          :key="`${item.time}-${item.message}`"
          :data-tone="eventTone(item.type)"
        >
          <span class="event-list__time">{{ item.time }}</span>
          <span class="event-list__type" :data-tone="eventTone(item.type)">{{ item.type }}</span>
          <span class="event-list__msg">{{ item.message }}</span>
        </li>
        <li v-if="!loading && runtimeEvents.length === 0" class="is-empty">暂无 Host Runtime 事件</li>
      </ul>
    </SectionCard>

    <SectionCard title="node_exporter 节点细节" :hint="`${nodeExporter?.upHosts ?? 0} UP · ${nodeExporter?.downHosts ?? 0} DOWN · ${nodeExporter?.staleHosts ?? 0} stale`" class="screen-grid__span-2">
      <div class="panel-switch">
        <button type="button" :class="{ on: nodeExporterView === 'metrics' }" @click="nodeExporterView = 'metrics'">聚合指标</button>
        <button type="button" :class="{ on: nodeExporterView === 'hosts' }" @click="nodeExporterView = 'hosts'">主机明细</button>
      </div>
      <div v-if="nodeExporterView === 'metrics'" class="node-exporter-metrics">
        <div class="status-mini" :data-tone="nodeExporterTone()">
          <span class="status-mini__label">UP / DOWN</span>
          <strong class="status-mini__value">{{ nodeExporter?.upHosts ?? 0 }} / {{ nodeExporter?.downHosts ?? 0 }}</strong>
          <span class="status-mini__hint">disabled {{ nodeExporter?.disabledHosts ?? 0 }} · unknown {{ nodeExporter?.unknownHosts ?? 0 }}</span>
        </div>
        <div class="node-exporter-gauges">
          <div class="node-exporter-gauge">
            <span>CPU</span>
            <strong>{{ formatAvgPercent(nodeExporter?.avgCpuUsagePercent) }}</strong>
          </div>
          <div class="node-exporter-gauge">
            <span>Memory</span>
            <strong>{{ formatAvgPercent(nodeExporter?.avgMemoryUsagePercent) }}</strong>
          </div>
          <div class="node-exporter-gauge">
            <span>Filesystem</span>
            <strong>{{ formatAvgPercent(nodeExporter?.avgFilesystemUsagePercent) }}</strong>
          </div>
        </div>
      </div>
      <ul v-else class="node-exporter-list">
        <li v-for="item in nodeExporterHosts" :key="item.hostname" class="node-exporter-row" :data-tone="nodeExporterStatusTone(item.status)">
          <div>
            <strong>{{ item.hostname }}</strong>
            <span>{{ item.checkedAt || '未采集' }}</span>
          </div>
          <div class="node-exporter-row__metrics">
            <span>CPU {{ formatMetric(item.cpuUsagePercent, '%') }}</span>
            <span>Mem {{ formatMetric(item.memoryUsagePercent, '%') }}</span>
            <span>FS {{ formatMetric(item.filesystemUsagePercent, '%') }}</span>
            <span>Load {{ formatMetric(item.load1) }}</span>
          </div>
          <em>{{ item.status || 'UNKNOWN' }}</em>
        </li>
        <li v-if="!loading && nodeExporterHosts.length === 0" class="is-empty">暂无 node_exporter 明细</li>
      </ul>
    </SectionCard>

    <SectionCard title="任务完成事件链" :hint="`最近窗口 ${runtimeEvents.length} 条事件`">
      <div class="chain-board">
        <div
          v-for="step in chainSteps"
          :key="step.type"
          class="chain-board__step"
          :data-active="step.count > 0"
        >
          <span class="chain-board__dot" />
          <span class="chain-board__label">{{ step.label }}</span>
          <strong>{{ step.count }}</strong>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="执行趋势" :hint="`${trendSeries.length} 个采样点`" class="screen-grid__span-3">
      <TrendChart :data="trendSeries" :labels="trendLabels" />
    </SectionCard>
  </section>
</template>
