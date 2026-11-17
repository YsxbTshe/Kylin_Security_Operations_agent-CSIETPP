<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, type DashboardChartOption, type ECharts } from '../lib/echarts'

type TooltipParams = {
  name?: string
  value?: unknown
  percent?: string | number
}

function formatTooltip(params: unknown) {
  const item = Array.isArray(params) ? params[0] as TooltipParams | undefined : params as TooltipParams
  return `${item?.name ?? ''}<br/>${item?.value ?? 0} 条 · ${item?.percent ?? 0}%`
}

const props = defineProps<{
  items?: Array<{ level: string; count: number }>
}>()

const chartRef = ref<HTMLDivElement | null>(null)
let chart: ECharts | undefined

const FONT = 'Noto Sans SC, PingFang SC, Microsoft YaHei, sans-serif'

const NAME_LABELS: Record<string, string> = {
  HIGH: '高危',
  CRITICAL: '严重',
  MEDIUM: '中危',
  LOW: '低危',
  INFO: '提示',
}

const PALETTE_BY_NAME: Record<string, string> = {
  HIGH:     '#F25E5E',
  CRITICAL: '#F25E5E',
  MEDIUM:   '#FFB347',
  LOW:      '#5BC8FA',
  INFO:     '#00E5FF',
}
const FALLBACK = ['#F25E5E', '#FFB347', '#00E5FF', '#46D89C', '#5BC8FA', '#F97559']

const chartData = computed(() => (props.items ?? [])
  .filter((item) => item.count > 0)
  .map((item) => ({
    value: item.count,
    name: NAME_LABELS[item.level.toUpperCase()] ?? item.level,
    rawName: item.level.toUpperCase(),
  })))

const hasData = computed(() => chartData.value.length > 0)
const total = computed(() => chartData.value.reduce((sum, item) => sum + item.value, 0))

const resize = () => chart?.resize()

function renderChart() {
  if (!chart) return

  const data = chartData.value.map((item, index) => ({
    value: item.value,
    name: item.name,
    itemStyle: {
      color: PALETTE_BY_NAME[item.rawName] ?? FALLBACK[index % FALLBACK.length],
      borderColor: '#0C1632',
      borderWidth: 2,
    },
  }))

  const option: DashboardChartOption = {
    tooltip: {
      trigger: 'item',
      backgroundColor: '#0C1632',
      borderColor: 'rgba(0, 229, 255, 0.36)',
      textStyle: { color: '#D7E3F4', fontFamily: FONT, fontSize: 12 },
      formatter: formatTooltip,
    },
    legend: {
      bottom: 4,
      icon: 'rect',
      itemWidth: 10,
      itemHeight: 8,
      itemGap: 14,
      textStyle: { color: '#6F8FB3', fontSize: 12, fontFamily: FONT },
    },
    series: [
      {
        type: 'pie',
        radius: ['56%', '78%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        label: { show: false },
        labelLine: { show: false },
        emphasis: { scale: true, scaleSize: 4, label: { show: false } },
        data,
      },
    ],
    graphic: [
      {
        type: 'text',
        left: 'center',
        top: '38%',
        style: {
          text: '总计',
          fill: '#6F8FB3',
          fontSize: 12,
          fontFamily: FONT,
          align: 'center',
        },
      },
      {
        type: 'text',
        left: 'center',
        top: '46%',
        style: {
          text: String(total.value),
          fill: '#FFFFFF',
          fontSize: 28,
          fontWeight: 700,
          fontFamily: FONT,
          align: 'center',
        },
      },
    ],
  }
  chart.setOption(option, true)
}

onMounted(() => {
  if (!chartRef.value || !hasData.value) return
  chart = init(chartRef.value)
  renderChart()
  window.addEventListener('resize', resize)
})

watch(chartData, () => {
  if (!chartRef.value || !hasData.value) return
  if (!chart) {
    chart = init(chartRef.value)
    window.addEventListener('resize', resize)
  }
  renderChart()
}, { flush: 'post' })

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<template>
  <div v-if="hasData" ref="chartRef" class="chart-container"></div>
  <div v-else class="chart-empty">暂无告警数据</div>
</template>
