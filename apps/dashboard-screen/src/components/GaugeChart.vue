<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, type DashboardChartOption, type ECharts } from '../lib/echarts'

const props = withDefaults(defineProps<{
  value: number
  label?: string
  unit?: string
}>(), {
  label: '在线率',
  unit: '%',
})

const FONT = 'Noto Sans SC, PingFang SC, Microsoft YaHei, sans-serif'

const chartRef = ref<HTMLDivElement | null>(null)
let chart: ECharts | undefined
const clamped = computed(() => Math.max(0, Math.min(100, Math.round(props.value || 0))))

const resize = () => chart?.resize()

function colorForValue(v: number): string {
  if (v >= 90) return '#46D89C'
  if (v >= 70) return '#00E5FF'
  if (v >= 40) return '#FFB347'
  return '#F25E5E'
}

function renderChart() {
  if (!chart) return
  const value = clamped.value
  const color = colorForValue(value)

  const option: DashboardChartOption = {
    series: [
      {
        type: 'gauge',
        startAngle: 220,
        endAngle: -40,
        min: 0,
        max: 100,
        radius: '92%',
        center: ['50%', '58%'],
        progress: {
          show: true,
          width: 14,
          roundCap: true,
          itemStyle: { color },
        },
        axisLine: {
          lineStyle: {
            width: 14,
            color: [[1, 'rgba(0, 229, 255, 0.10)']],
          },
        },
        axisTick: { show: false },
        splitLine: {
          show: true,
          length: 6,
          distance: -22,
          lineStyle: { color: 'rgba(0, 229, 255, 0.32)', width: 1 },
        },
        axisLabel: {
          show: true,
          distance: 22,
          color: '#6F8FB3',
          fontSize: 11,
          fontFamily: FONT,
          formatter: (v: number) => (v === 0 || v === 100 ? String(v) : ''),
        },
        pointer: { show: false },
        anchor: { show: false },
        title: {
          show: true,
          offsetCenter: [0, '68%'],
          color: '#6F8FB3',
          fontSize: 12,
          fontFamily: FONT,
        },
        detail: {
          valueAnimation: true,
          offsetCenter: [0, '8%'],
          fontFamily: FONT,
          fontSize: 36,
          fontWeight: 700,
          color: '#FFFFFF',
          formatter: (v: number) => `${Math.round(v)}{u|${props.unit}}`,
          rich: {
            u: {
              fontSize: 14,
              color: '#00E5FF',
              fontFamily: FONT,
              padding: [0, 0, 0, 4],
              fontWeight: 500,
            },
          },
        },
        data: [{ value, name: props.label }],
      },
    ],
  }
  chart.setOption(option, true)
}

onMounted(() => {
  if (!chartRef.value) return
  chart = init(chartRef.value)
  renderChart()
  window.addEventListener('resize', resize)
})

watch(() => props.value, () => {
  if (!chart && chartRef.value) {
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
  <div ref="chartRef" class="chart-container chart-container--short"></div>
</template>
