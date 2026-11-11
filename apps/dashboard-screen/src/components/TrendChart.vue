<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { graphic, init, type DashboardChartOption, type ECharts } from '../lib/echarts'

const props = defineProps<{
  data?: number[]
  labels?: string[]
}>()

const chartRef = ref<HTMLDivElement | null>(null)
let chart: ECharts | undefined
const hasData = computed(() => (props.data?.length ?? 0) > 0)

const movingAverage = computed(() => {
  const values = props.data ?? []
  if (!values.length) return [] as number[]
  const window = 3
  return values.map((_, i) => {
    const slice = values.slice(Math.max(0, i - window + 1), i + 1)
    const sum = slice.reduce((a, b) => a + b, 0)
    return Math.round((sum / slice.length) * 10) / 10
  })
})

const resize = () => chart?.resize()

const FONT = 'Noto Sans SC, PingFang SC, Microsoft YaHei, sans-serif'

function renderChart() {
  if (!chart) return

  const values = props.data ?? []
  const labels = props.labels ?? []

  const option: DashboardChartOption = {
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#0C1632',
      borderColor: 'rgba(0, 229, 255, 0.36)',
      textStyle: { color: '#D7E3F4', fontFamily: FONT, fontSize: 12 },
      axisPointer: {
        type: 'line',
        lineStyle: { color: 'rgba(0, 229, 255, 0.36)', type: 'dashed' },
      },
    },
    legend: {
      top: 0,
      right: 8,
      itemWidth: 10,
      itemHeight: 6,
      itemGap: 14,
      textStyle: { color: '#6F8FB3', fontSize: 12, fontFamily: FONT },
    },
    grid: { left: 36, right: 22, top: 36, bottom: 28 },
    xAxis: {
      type: 'category',
      data: labels,
      boundaryGap: false,
      axisLine: { lineStyle: { color: 'rgba(0, 229, 255, 0.28)' } },
      axisTick: { show: false },
      axisLabel: { color: '#6F8FB3', fontSize: 11, fontFamily: FONT },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(0, 229, 255, 0.10)', type: 'dashed' } },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#6F8FB3', fontSize: 11, fontFamily: FONT },
    },
    series: [
      {
        name: '事件数',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: values,
        areaStyle: {
          color: new graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(0, 229, 255, 0.36)' },
            { offset: 1, color: 'rgba(0, 229, 255, 0.02)' },
          ]),
        },
        lineStyle: { color: '#00E5FF', width: 2 },
        itemStyle: { color: '#00E5FF' },
        emphasis: { focus: 'series' },
        markPoint: values.length > 0 ? {
          symbol: 'pin',
          symbolSize: 38,
          itemStyle: {
            color: 'rgba(255, 179, 71, 0.18)',
            borderColor: '#FFB347',
            borderWidth: 1,
          },
          label: { color: '#FFD894', fontSize: 11, fontFamily: FONT, formatter: '峰值\n{c}' },
          data: [{ type: 'max', name: '峰值' }],
        } : undefined,
      },
      {
        name: '滑动均值',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: movingAverage.value,
        lineStyle: { color: '#FFB347', width: 1.5, type: 'dashed' },
        itemStyle: { color: '#FFB347' },
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

watch(() => [props.data, props.labels], () => {
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
  <div v-if="hasData" ref="chartRef" class="chart-container chart-container--tall"></div>
  <div v-else class="chart-empty">暂无趋势数据</div>
</template>
