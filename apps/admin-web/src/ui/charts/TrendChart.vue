<script setup lang="ts">
/**
 * 多系列趋势图（面积 + 折线）—— 用于"随时间变化"。
 *
 * 规则：
 * - 单一 Y 轴。两个量级差很远的指标不共用一张图（双轴是最常见的图表错误）；
 * - 线宽 2px，hover 时才出现 ≥8px 的圆点，避免每个点都堆标记；
 * - 十字准星 + tooltip 是默认行为，不是可选增强；
 * - 系列颜色只标身份，数字和文案一律用文本色。
 */
import { computed, ref } from 'vue'

interface Point {
  date: string
  count: number
}
interface Series {
  label: string
  color: string
  fill: string
  points: Point[]
}

const props = withDefaults(
  defineProps<{
    series: Series[]
    height?: number
  }>(),
  { height: 190 },
)

const W = 760
const PAD = { top: 10, right: 8, bottom: 24, left: 8 }

const innerH = computed(() => props.height - PAD.top - PAD.bottom)
const innerW = W - PAD.left - PAD.right

const max = computed(() => {
  const all = props.series.flatMap((s) => s.points.map((p) => p.count))
  return Math.max(1, ...all)
})

const dates = computed(() => props.series[0]?.points.map((p) => p.date) ?? [])
const count = computed(() => dates.value.length)

function coord(index: number, value: number) {
  const x = count.value <= 1 ? innerW / 2 : (index / (count.value - 1)) * innerW
  const y = innerH.value - (value / max.value) * innerH.value
  return { x: x + PAD.left, y: y + PAD.top }
}

function linePath(s: Series) {
  if (!s.points.length) return ''
  return s.points
    .map((p, i) => {
      const c = coord(i, p.count)
      return `${i === 0 ? 'M' : 'L'}${c.x.toFixed(1)},${c.y.toFixed(1)}`
    })
    .join(' ')
}

function areaPath(s: Series) {
  if (!s.points.length) return ''
  const base = PAD.top + innerH.value
  const first = coord(0, s.points[0].count)
  const last = coord(s.points.length - 1, s.points[s.points.length - 1].count)
  return `${linePath(s)} L${last.x.toFixed(1)},${base.toFixed(1)} L${first.x.toFixed(1)},${base.toFixed(1)} Z`
}

/* ── 十字准星 ─────────────────────────────────────────────── */
const active = ref<number | null>(null)
const svgRef = ref<SVGSVGElement | null>(null)

function onMove(event: MouseEvent) {
  const svg = svgRef.value
  if (!svg || count.value === 0) return
  const rect = svg.getBoundingClientRect()
  // viewBox 宽度固定为 W，鼠标位置要换算回 viewBox 坐标系
  const x = ((event.clientX - rect.left) / rect.width) * W - PAD.left
  const step = count.value <= 1 ? innerW : innerW / (count.value - 1)
  const index = Math.round(x / step)
  active.value = Math.min(count.value - 1, Math.max(0, index))
}

const activeX = computed(() => (active.value === null ? 0 : coord(active.value, 0).x))

const tooltip = computed(() => {
  if (active.value === null) return null
  const i = active.value
  return {
    date: dates.value[i],
    rows: props.series.map((s) => ({
      label: s.label,
      color: s.color,
      value: s.points[i]?.count ?? 0,
    })),
    /** 靠右侧时把浮层翻到左边，避免溢出容器 */
    flip: count.value > 1 && i / (count.value - 1) > 0.62,
    leftPct: count.value <= 1 ? 50 : ((coord(i, 0).x / W) * 100),
  }
})

function shortDate(iso: string) {
  return iso ? iso.slice(5) : ''
}
</script>

<template>
  <div class="trend" :style="{ height: `${height}px` }">
    <svg
      ref="svgRef"
      class="trend__svg"
      :viewBox="`0 0 ${W} ${height}`"
      preserveAspectRatio="none"
      role="img"
      aria-label="近 7 天趋势"
      @mousemove="onMove"
      @mouseleave="active = null"
    >
      <defs>
        <linearGradient v-for="s in series" :id="`tg-${s.label}`" :key="s.label" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" :stop-color="s.fill" />
          <stop offset="100%" stop-color="transparent" />
        </linearGradient>
      </defs>

      <!-- 退让的水平参考线 -->
      <line
        v-for="r in [0, 0.5, 1]"
        :key="r"
        :x1="PAD.left"
        :x2="W - PAD.right"
        :y1="PAD.top + innerH * r"
        :y2="PAD.top + innerH * r"
        stroke="var(--chart-grid)"
        stroke-width="1"
        stroke-dasharray="3 4"
        opacity="0.7"
      />

      <g v-for="s in series" :key="s.label">
        <path :d="areaPath(s)" :fill="`url(#tg-${s.label})`" />
        <path :d="linePath(s)" fill="none" :stroke="s.color" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
      </g>

      <!-- 十字准星：竖线 + 每条系列一个 9px 圆点（含 2px 表面色描边做分离） -->
      <g v-if="active !== null" class="trend__cursor">
        <line
          :x1="activeX"
          :x2="activeX"
          :y1="PAD.top"
          :y2="PAD.top + innerH"
          stroke="var(--chart-axis)"
          stroke-width="1"
          stroke-dasharray="3 3"
        />
        <circle
          v-for="s in series"
          :key="s.label"
          :cx="activeX"
          :cy="coord(active, s.points[active]?.count ?? 0).y"
          r="4.5"
          :fill="s.color"
          stroke="var(--chart-surface)"
          stroke-width="2"
        />
      </g>
    </svg>

    <div class="trend__axis">
      <span v-for="(d, i) in dates" :key="d" class="trend__tick" :class="{ 'trend__tick--on': active === i }">
        {{ shortDate(d) }}
      </span>
    </div>

    <div
      v-if="tooltip"
      class="trend__tip"
      :class="{ 'trend__tip--flip': tooltip.flip }"
      :style="{ left: `${tooltip.leftPct}%` }"
    >
      <div class="trend__tip-date">{{ tooltip.date }}</div>
      <div v-for="row in tooltip.rows" :key="row.label" class="trend__tip-row">
        <i class="trend__tip-dot" :style="{ background: row.color }" />
        <span class="trend__tip-label">{{ row.label }}</span>
        <span class="trend__tip-value num">{{ row.value }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trend {
  position: relative;
  width: 100%;
}
.trend__svg {
  width: 100%;
  height: 100%;
  display: block;
  overflow: visible;
}
.trend__axis {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--text-3);
  pointer-events: none;
}
.trend__tick--on {
  color: var(--text);
  font-weight: 600;
}
.trend__tip {
  position: absolute;
  top: 4px;
  transform: translateX(10px);
  min-width: 128px;
  padding: 9px 11px;
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--radius);
  box-shadow: var(--shadow-pop);
  pointer-events: none;
  z-index: 2;
}
.trend__tip--flip {
  transform: translateX(calc(-100% - 10px));
}
.trend__tip-date {
  font-size: 11.5px;
  color: var(--text-3);
  margin-bottom: 6px;
}
.trend__tip-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  line-height: 1.9;
}
.trend__tip-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.trend__tip-label {
  color: var(--text-2);
}
.trend__tip-value {
  margin-left: auto;
  font-weight: 600;
  color: var(--text);
}
</style>
