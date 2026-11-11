<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import SectionCard from '../components/SectionCard.vue'
import MetricCard from '../components/MetricCard.vue'
import type { ScreenDashboardResponse } from '../api/screen'
import { eventTone, highRiskCount, riskLevelCount } from './shared'

const TrendChart = defineAsyncComponent(() => import('../components/TrendChart.vue'))
const RiskChart = defineAsyncComponent(() => import('../components/RiskChart.vue'))

const props = defineProps<{
  dashboard: ScreenDashboardResponse | null
  loading: boolean
}>()

const summary = computed(() => props.dashboard?.summary)
const events = computed(() => props.dashboard?.events ?? [])
const trendSeries = computed(() => props.dashboard?.trend?.map((item) => item.count) ?? [])
const trendLabels = computed(() => props.dashboard?.trend?.map((item) => item.label) ?? [])
const peak = computed(() => trendSeries.value.length ? Math.max(...trendSeries.value) : 0)

const dangerEvents = computed(() => events.value.filter((event) => eventTone(event.type) === 'danger').length)
const warningEvents = computed(() => events.value.filter((event) => eventTone(event.type) === 'warning').length)

const kpis = computed(() => [
  {
    label: '活跃告警',
    value: summary.value?.alertCount ?? 0,
    suffix: '条',
    tone: 'primary' as const,
    footer: '当前组织告警事件',
    footerTone: 'muted' as const,
  },
  {
    label: '高危告警',
    value: highRiskCount(props.dashboard),
    suffix: '条',
    tone: highRiskCount(props.dashboard) > 0 ? ('danger' as const) : ('success' as const),
    footer: highRiskCount(props.dashboard) > 0 ? '需要立即处置' : '当前无高危',
    footerTone: highRiskCount(props.dashboard) > 0 ? ('danger' as const) : ('success' as const),
  },
  {
    label: '中危告警',
    value: riskLevelCount(props.dashboard, 'MEDIUM'),
    suffix: '条',
    tone: 'warning' as const,
    footer: '建议核实研判',
    footerTone: 'muted' as const,
  },
  {
    label: '低危告警',
    value: riskLevelCount(props.dashboard, 'LOW'),
    suffix: '条',
    tone: 'primary' as const,
    footer: '关注事件分布',
    footerTone: 'muted' as const,
  },
  {
    label: '事件总数',
    value: events.value.length,
    suffix: '条',
    tone: 'primary' as const,
    footer: `异常 ${dangerEvents.value} · 警告 ${warningEvents.value}`,
    footerTone: dangerEvents.value > 0 ? ('danger' as const) : ('muted' as const),
  },
])
</script>

<template>
  <section class="kpi-row kpi-row--five">
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
    <SectionCard title="风险趋势" :hint="`峰值 ${peak} · ${trendSeries.length} 个采样点`" class="screen-grid__span-2">
      <TrendChart :data="trendSeries" :labels="trendLabels" />
    </SectionCard>

    <SectionCard title="告警等级分布" :hint="`${(dashboard?.riskDistribution ?? []).reduce((s, i) => s + i.count, 0)} 条`">
      <RiskChart :items="dashboard?.riskDistribution" />
    </SectionCard>

    <SectionCard title="近期事件流（全量）" :hint="`${events.length} 条`" class="screen-grid__span-3">
      <ul class="event-list event-list--tall">
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
