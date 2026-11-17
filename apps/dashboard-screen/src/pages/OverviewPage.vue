<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import SectionCard from '../components/SectionCard.vue'
import MetricCard from '../components/MetricCard.vue'
import HostHealthChart from '../components/HostHealthChart.vue'
import type { ScreenDashboardResponse } from '../api/screen'
import { activeAgentCount, avgCpu, eventTone, agentStatusKey, highRiskCount, offlineCount, onlineRate, peakCpu } from './shared'

const TrendChart = defineAsyncComponent(() => import('../components/TrendChart.vue'))
const RiskChart = defineAsyncComponent(() => import('../components/RiskChart.vue'))
const GaugeChart = defineAsyncComponent(() => import('../components/GaugeChart.vue'))

const props = defineProps<{
  dashboard: ScreenDashboardResponse | null
  loading: boolean
}>()

const summary = computed(() => props.dashboard?.summary)

const trendSeries = computed(() => props.dashboard?.trend?.map((item) => item.count) ?? [])
const trendLabels = computed(() => props.dashboard?.trend?.map((item) => item.label) ?? [])
const events = computed(() => props.dashboard?.events ?? [])
const agents = computed(() => props.dashboard?.agents ?? [])
const hosts = computed(() => props.dashboard?.hostHealth ?? [])
const safeOps = computed(() => props.dashboard?.safeOps)
const redisStatus = computed(() => safeOps.value?.redisStreamStatus)
const hostRuntime = computed(() => safeOps.value?.hostRuntimeStatus)
const nativeGuard = computed(() => safeOps.value?.nativeGuardStatus)
const nodeExporter = computed(() => safeOps.value?.nodeExporterStatus)
const nodeExporterHosts = computed(() => safeOps.value?.nodeExporterHosts ?? [])
const nativeGuardAtRisk = computed(() => (nativeGuard.value?.criticalHosts ?? 0) + (nativeGuard.value?.warningHosts ?? 0))
const redisPending = computed(() => (redisStatus.value?.assistantPending ?? 0) + (redisStatus.value?.hostTaskPending ?? 0) + (redisStatus.value?.approvalPending ?? 0))
const redisLength = computed(() => (redisStatus.value?.assistantStreamLength ?? 0) + (redisStatus.value?.hostTaskStreamLength ?? 0) + (redisStatus.value?.approvalStreamLength ?? 0))
const mcpToolTop = computed(() => safeOps.value?.mcpToolTop ?? [])
const mcpToolCallCount = computed(() => mcpToolTop.value.reduce((sum, item) => sum + item.count, 0))

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

const safeOpsNodes = computed(() => [
  {
    label: 'gRPC/mTLS',
    value: safeOps.value?.grpcConnectedAgents ?? 0,
    suffix: '个',
    hint: `证书 ACTIVE ${safeOps.value?.activeCertificates ?? 0} · 临期 ${safeOps.value?.expiringCertificates ?? 0} · 吊销 ${safeOps.value?.revokedCertificates ?? 0}`,
    tone: (safeOps.value?.grpcConnectedAgents ?? 0) > 0 ? 'success' : 'info',
  },
  {
    label: 'MCP Manifest',
    value: mcpToolCallCount.value,
    suffix: '次',
    hint: `${mcpToolTop.value.length} 个工具进入 TopN`,
    tone: 'info',
  },
  {
    label: 'Policy Engine',
    value: safeOps.value?.r5Blocked ?? 0,
    suffix: '次',
    hint: 'R5 自动阻断',
    tone: (safeOps.value?.r5Blocked ?? 0) > 0 ? 'danger' : 'success',
  },
  {
    label: 'Native Guard',
    value: nativeGuardAtRisk.value,
    suffix: '台',
    hint: `健康 ${nativeGuard.value?.healthyHosts ?? 0} · 探针 C${nativeGuard.value?.criticalProbes ?? 0}/W${nativeGuard.value?.warningProbes ?? 0} · 未知 ${nativeGuard.value?.unknownHosts ?? 0} · stale ${nativeGuard.value?.staleHosts ?? 0}`,
    tone: nativeGuardTone(),
  },
  {
    label: 'Host Runtime',
    value: hostRuntime.value?.runningTasks ?? 0,
    suffix: '项',
    hint: `队列 ${hostRuntime.value?.queuedTasks ?? 0} · 本地锁 ${hostRuntime.value?.localActiveLocks ?? 0} · 并发 ${hostRuntime.value?.maxParallel ?? 0}`,
    tone: (hostRuntime.value?.queuedTasks ?? 0) > 0 ? 'warning' : 'success',
  },
  {
    label: 'node_exporter',
    value: nodeExporter.value?.upHosts ?? 0,
    suffix: '台',
    hint: `CPU ${formatAvgPercent(nodeExporter.value?.avgCpuUsagePercent)} · Mem ${formatAvgPercent(nodeExporter.value?.avgMemoryUsagePercent)} · FS ${formatAvgPercent(nodeExporter.value?.avgFilesystemUsagePercent)} · down ${nodeExporter.value?.downHosts ?? 0} · stale ${nodeExporter.value?.staleHosts ?? 0}`,
    tone: nodeExporterTone(),
  },
  {
    label: 'Redis Stream',
    value: redisPending.value,
    suffix: '条',
    hint: `${redisStatus.value?.status ?? 'UNKNOWN'} · Stream ${redisLength.value} · DLQ ${redisStatus.value?.deadLetterLength ?? 0}`,
    tone: redisTone(redisStatus.value?.status),
  },
  {
    label: 'Rollback',
    value: safeOps.value?.rollbackRecords ?? 0,
    suffix: '条',
    hint: '回滚上下文留痕',
    tone: 'info',
  },
])

const kpis = computed(() => [
  {
    label: '纳管节点',
    value: summary.value?.hostCount ?? 0,
    suffix: '台',
    tone: 'primary' as const,
    footer: `在线 ${summary.value?.onlineCount ?? 0} · 离线 ${offlineCount(props.dashboard)}`,
    footerTone: offlineCount(props.dashboard) > 0 ? ('warning' as const) : ('success' as const),
  },
  {
    label: '活跃告警',
    value: summary.value?.alertCount ?? 0,
    suffix: '条',
    tone: highRiskCount(props.dashboard) > 0 ? ('danger' as const) : ('primary' as const),
    footer: highRiskCount(props.dashboard) > 0 ? `高危 ${highRiskCount(props.dashboard)} 条` : '无高危告警',
    footerTone: highRiskCount(props.dashboard) > 0 ? ('danger' as const) : ('success' as const),
  },
  {
    label: '执行次数',
    value: summary.value?.agentExecutions ?? 0,
    suffix: '次',
    tone: 'primary' as const,
    footer: '最近 24 小时',
    footerTone: 'muted' as const,
  },
  {
    label: 'Agent 实例',
    value: agents.value.length,
    suffix: '个',
    tone: 'primary' as const,
    footer: `gRPC ${safeOps.value?.grpcConnectedAgents ?? 0} · 就绪 ${activeAgentCount(props.dashboard)}`,
    footerTone: activeAgentCount(props.dashboard) === agents.value.length
      ? ('success' as const)
      : ('warning' as const),
  },
  {
    label: '平均 CPU',
    value: avgCpu(props.dashboard),
    suffix: '%',
    tone: avgCpu(props.dashboard) >= 80 ? ('danger' as const) : avgCpu(props.dashboard) >= 60 ? ('warning' as const) : ('primary' as const),
    footer: `峰值 ${peakCpu(props.dashboard)}%`,
    footerTone: peakCpu(props.dashboard) >= 85 ? ('danger' as const) : peakCpu(props.dashboard) >= 65 ? ('warning' as const) : ('muted' as const),
  },
  {
    label: '事件流',
    value: events.value.length,
    suffix: '条',
    tone: 'primary' as const,
    footer: '当前窗口',
    footerTone: 'muted' as const,
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
    <SectionCard title="风险趋势" :hint="`${trendSeries.length} 个采样点`" class="screen-grid__span-2">
      <TrendChart :data="trendSeries" :labels="trendLabels" />
    </SectionCard>

    <SectionCard title="告警等级分布">
      <RiskChart :items="dashboard?.riskDistribution" />
    </SectionCard>

    <SectionCard title="节点健康度" :hint="`${hosts.length} 台`" class="screen-grid__span-2">
      <HostHealthChart :items="hosts" />
    </SectionCard>

    <SectionCard title="主机在线率" :hint="`离线 ${offlineCount(dashboard)} 台`">
      <GaugeChart :value="onlineRate(dashboard)" label="主机在线率" />
    </SectionCard>

    <SectionCard title="SafeOps 主链路" :hint="`Redis ${redisStatus?.status ?? 'UNKNOWN'} · pending ${redisPending} · DLQ ${redisStatus?.deadLetterLength ?? 0}`" class="screen-grid__span-2">
      <div class="safeops-overview">
        <div class="safeops-pipeline">
          <div
            v-for="node in safeOpsNodes"
            :key="node.label"
            class="safeops-node"
            :data-tone="node.tone"
          >
            <span class="safeops-node__label">{{ node.label }}</span>
            <strong>{{ node.value }}<small>{{ node.suffix }}</small></strong>
            <span class="safeops-node__hint">{{ node.hint }}</span>
          </div>
        </div>
        <ul class="rank-list rank-list--compact">
          <li v-for="(item, index) in mcpToolTop.slice(0, 4)" :key="item.toolName" class="rank-list__item">
            <div class="rank-list__index">{{ String(index + 1).padStart(2, '0') }}</div>
            <div>
              <div class="rank-list__label">{{ item.toolName }}</div>
              <div class="rank-list__sub">MCP Tool Call</div>
            </div>
            <div class="rank-list__value">{{ item.count }}</div>
          </li>
          <li v-if="!loading && mcpToolTop.length === 0" class="rank-list--empty rank-list--inline">暂无 MCP 调用</li>
        </ul>
      </div>
    </SectionCard>

    <SectionCard title="node_exporter 明细" :hint="`CPU ${formatAvgPercent(nodeExporter?.avgCpuUsagePercent)} · Mem ${formatAvgPercent(nodeExporter?.avgMemoryUsagePercent)} · FS ${formatAvgPercent(nodeExporter?.avgFilesystemUsagePercent)}`" class="screen-grid__span-2">
      <div class="node-exporter-summary">
        <div class="status-grid">
          <div class="status-mini" :data-tone="nodeExporterTone()">
            <span class="status-mini__label">Fresh UP</span>
            <strong class="status-mini__value">{{ nodeExporter?.upHosts ?? 0 }}</strong>
            <span class="status-mini__hint">down {{ nodeExporter?.downHosts ?? 0 }} · stale {{ nodeExporter?.staleHosts ?? 0 }}</span>
          </div>
          <div class="status-mini" :data-tone="(nodeExporter?.disabledHosts ?? 0) > 0 || (nodeExporter?.unknownHosts ?? 0) > 0 ? 'warning' : 'success'">
            <span class="status-mini__label">采集覆盖</span>
            <strong class="status-mini__value">{{ (nodeExporter?.disabledHosts ?? 0) + (nodeExporter?.unknownHosts ?? 0) }}</strong>
            <span class="status-mini__hint">disabled / unknown</span>
          </div>
        </div>
        <ul class="node-exporter-list node-exporter-list--compact">
          <li v-for="item in nodeExporterHosts.slice(0, 4)" :key="item.hostname" class="node-exporter-row" :data-tone="nodeExporterStatusTone(item.status)">
            <div>
              <strong>{{ item.hostname }}</strong>
              <span>{{ item.status || 'UNKNOWN' }}</span>
            </div>
            <div class="node-exporter-row__metrics">
              <span>CPU {{ formatMetric(item.cpuUsagePercent, '%') }}</span>
              <span>Mem {{ formatMetric(item.memoryUsagePercent, '%') }}</span>
              <span>FS {{ formatMetric(item.filesystemUsagePercent, '%') }}</span>
            </div>
            <em>{{ formatMetric(item.load1) }}</em>
          </li>
          <li v-if="!loading && nodeExporterHosts.length === 0" class="is-empty">暂无 node_exporter 明细</li>
        </ul>
      </div>
    </SectionCard>

    <SectionCard title="Agent 状态" :hint="`${activeAgentCount(dashboard)} / ${agents.length} 就绪`">
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

    <SectionCard title="近期事件流" :hint="`${events.length} 条`">
      <ul class="event-list">
        <li
          v-for="item in events"
          :key="`${item.time}-${item.message}`"
          :data-tone="eventTone(item.type)"
        >
          <span class="event-list__time">{{ item.time }}</span>
          <span class="event-list__type" :data-tone="eventTone(item.type)">{{ item.type }}</span>
          <span class="event-list__msg">{{ item.message }}</span>
        </li>
        <li v-if="!loading && events.length === 0" class="is-empty">暂无近期事件</li>
      </ul>
    </SectionCard>
  </section>
</template>
