<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import SectionCard from '../components/SectionCard.vue'
import MetricCard from '../components/MetricCard.vue'
import HostHealthChart from '../components/HostHealthChart.vue'
import type { ScreenDashboardResponse } from '../api/screen'
import { avgCpu, avgDisk, avgMem, hostStatusBreakdown, offlineCount, onlineRate, parsePct, peakCpu, topHostsByCpu } from './shared'

const GaugeChart = defineAsyncComponent(() => import('../components/GaugeChart.vue'))

const props = defineProps<{
  dashboard: ScreenDashboardResponse | null
  loading: boolean
}>()

const hosts = computed(() => props.dashboard?.hostHealth ?? [])
const summary = computed(() => props.dashboard?.summary)
const breakdown = computed(() => hostStatusBreakdown(props.dashboard))
const topCpu = computed(() => topHostsByCpu(props.dashboard, 6))

const kpis = computed(() => {
  const offline = offlineCount(props.dashboard)
  const cpu = avgCpu(props.dashboard)
  const mem = avgMem(props.dashboard)
  const disk = avgDisk(props.dashboard)
  const peak = peakCpu(props.dashboard)
  return [
    {
      label: '纳管节点',
      value: summary.value?.hostCount ?? 0,
      suffix: '台',
      tone: 'primary' as const,
      footer: `本组织全部主机`,
      footerTone: 'muted' as const,
    },
    {
      label: '在线主机',
      value: summary.value?.onlineCount ?? 0,
      suffix: '台',
      tone: 'success' as const,
      footer: `心跳正常`,
      footerTone: 'success' as const,
    },
    {
      label: '离线 / 异常',
      value: offline,
      suffix: '台',
      tone: offline > 0 ? ('warning' as const) : ('primary' as const),
      footer: offline > 0 ? '需要关注' : '全部在线',
      footerTone: offline > 0 ? ('warning' as const) : ('success' as const),
    },
    {
      label: '平均 CPU',
      value: cpu,
      suffix: '%',
      tone: cpu >= 80 ? ('danger' as const) : cpu >= 60 ? ('warning' as const) : ('primary' as const),
      footer: `峰值 ${peak}%`,
      footerTone: peak >= 85 ? ('danger' as const) : peak >= 65 ? ('warning' as const) : ('muted' as const),
    },
    {
      label: '平均内存',
      value: mem,
      suffix: '%',
      tone: mem >= 80 ? ('danger' as const) : mem >= 60 ? ('warning' as const) : ('primary' as const),
      footer: `${hosts.value.length} 台均值`,
      footerTone: 'muted' as const,
    },
    {
      label: '平均磁盘',
      value: disk,
      suffix: '%',
      tone: disk >= 80 ? ('danger' as const) : disk >= 60 ? ('warning' as const) : ('primary' as const),
      footer: `${hosts.value.length} 台均值`,
      footerTone: 'muted' as const,
    },
  ]
})

function pct(value: string) {
  return `${parsePct(value)}%`
}
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
    <SectionCard title="主机资源占用" :hint="`${hosts.length} 台主机 · 含 CPU / 内存 / 磁盘`" class="screen-grid__span-2 screen-grid__row-2">
      <HostHealthChart :items="hosts" />
    </SectionCard>

    <SectionCard title="主机在线率" :hint="`离线 ${offlineCount(dashboard)} 台`">
      <GaugeChart :value="onlineRate(dashboard)" label="主机在线率" />
    </SectionCard>

    <SectionCard title="主机状态分布">
      <div class="status-grid">
        <div class="status-mini" data-tone="success">
          <div class="status-mini__label">在线</div>
          <div class="status-mini__value">{{ breakdown.online }}</div>
          <div class="status-mini__hint">心跳在窗口内</div>
        </div>
        <div class="status-mini" data-tone="warning">
          <div class="status-mini__label">告警中</div>
          <div class="status-mini__value">{{ breakdown.alerting }}</div>
          <div class="status-mini__hint">存在未处置告警</div>
        </div>
        <div class="status-mini" data-tone="warning">
          <div class="status-mini__label">降级</div>
          <div class="status-mini__value">{{ breakdown.degraded }}</div>
          <div class="status-mini__hint">资源指标超阈</div>
        </div>
        <div class="status-mini" data-tone="danger">
          <div class="status-mini__label">离线</div>
          <div class="status-mini__value">{{ breakdown.offline }}</div>
          <div class="status-mini__hint">心跳超时</div>
        </div>
      </div>
    </SectionCard>

    <SectionCard title="CPU 占用排行" hint="TOP 6">
      <ul class="rank-list">
        <li v-for="(item, index) in topCpu" :key="item.hostname" class="rank-list__item">
          <div class="rank-list__index">{{ String(index + 1).padStart(2, '0') }}</div>
          <div>
            <div class="rank-list__label">{{ item.hostname }}</div>
            <div class="rank-list__sub">内存 {{ pct(item.memoryUsage) }} · 磁盘 {{ pct(item.diskUsage) }}</div>
          </div>
          <div class="rank-list__value">{{ pct(item.cpuUsage) }}</div>
        </li>
        <li v-if="!loading && topCpu.length === 0" class="rank-list--empty">暂无主机数据</li>
      </ul>
    </SectionCard>
  </section>
</template>
