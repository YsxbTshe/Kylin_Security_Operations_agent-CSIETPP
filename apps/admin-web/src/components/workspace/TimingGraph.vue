<script setup lang="ts">
/**
 * 执行时序图。
 *
 * ⚠ 这**不是「计划 DAG」**。当前执行模型是 ReAct 主循环，架构上不存在预先编排的
 * 计划图。这里画的是已经发生的事：三条泳道（回合 / 工具调用 / 子 Agent），
 * x 轴是真实墙钟时间，连边表达「哪个回合触发了这次工具调用或这个子 Agent」的
 * 因果归属。结构化计划与阶段 DAG 由后端另一批工作提供，届时另开视图。
 *
 * 实现用手写 SVG：时序图结构简单（矩形 + 折线 + 刻度），没必要为它引入
 * echarts/d3 这种量级的依赖，也就不用付出打包体积和主题适配的代价。
 * 颜色全部走 CSS 变量，明暗主题与 --ws-* 状态色自动跟随。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { TimingModel, TimingNode } from '../../utils/run-events'
import { formatDurationMs } from '../../utils/format'

const props = defineProps<{
  model: TimingModel
  selectedId?: string | null
}>()

defineEmits<{ (e: 'select', node: TimingNode): void }>()

/* 版面常量：左侧泳道名固定宽度，其余按容器宽度自适应。 */
const GUTTER = 72
const PAD_RIGHT = 16
const PAD_TOP = 10
const LANE_H = 44
const BAR_H = 22
const AXIS_H = 22
const MIN_BAR_W = 6

const host = ref<HTMLElement | null>(null)
const width = ref(720)
let observer: ResizeObserver | null = null

onMounted(() => {
  if (!host.value) return
  observer = new ResizeObserver((entries) => {
    const next = entries[0]?.contentRect.width ?? 0
    if (next > 0) width.value = Math.max(360, Math.round(next))
  })
  observer.observe(host.value)
})
onBeforeUnmount(() => observer?.disconnect())

const plotWidth = computed(() => Math.max(120, width.value - GUTTER - PAD_RIGHT))
const height = computed(() => PAD_TOP + props.model.lanes.length * LANE_H + AXIS_H)

function x(ms: number): number {
  return GUTTER + (ms / props.model.totalMs) * plotWidth.value
}
function laneY(index: number): number {
  return PAD_TOP + index * LANE_H
}
function barY(index: number): number {
  return laneY(index) + (LANE_H - BAR_H) / 2
}
function barWidth(node: TimingNode): number {
  return Math.max(MIN_BAR_W, x(node.endMs) - x(node.startMs))
}

/** 回合节点的 y 中心，供连边起点使用。 */
const turnLaneIndex = computed(() => props.model.lanes.findIndex((lane) => lane.id === 'turn'))

interface Edge {
  key: string
  d: string
}

/**
 * 连边：从触发回合的下沿走到子节点的左上角，折线（V-H-V）而不是贝塞尔——
 * 时序图上直角折线更容易读出「先后」，曲线会让人误以为是权重。
 */
const edges = computed<Edge[]>(() => {
  const turnLane = turnLaneIndex.value
  if (turnLane < 0) return []
  const turnNodes = new Map(props.model.lanes[turnLane].nodes.map((node) => [node.turnIndex, node]))
  const list: Edge[] = []
  props.model.lanes.forEach((lane, laneIndex) => {
    if (lane.id === 'turn') return
    for (const node of lane.nodes) {
      const parent = turnNodes.get(node.turnIndex)
      if (!parent) continue
      const x1 = Math.min(x(parent.endMs), x(node.startMs))
      const y1 = barY(turnLane) + BAR_H
      const x2 = x(node.startMs)
      const y2 = barY(laneIndex) + BAR_H / 2
      const mid = y1 + (y2 - y1) / 2
      list.push({ key: `${parent.id}->${node.id}`, d: `M ${x1} ${y1} V ${mid} H ${x2} V ${y2}` })
    }
  })
  return list
})

/** 时间轴刻度：0 / ⅓ / ⅔ / 全长，四个够读，再多就成栅栏了。 */
const ticks = computed(() =>
  [0, 1 / 3, 2 / 3, 1].map((ratio) => {
    const ms = Math.round(props.model.totalMs * ratio)
    return { ms, x: x(ms), label: ratio === 0 ? '0' : formatDurationMs(ms) }
  }),
)

function nodeTitle(node: TimingNode): string {
  const span = formatDurationMs(node.endMs - node.startMs)
  return `${node.label}${node.sublabel ? ` · ${node.sublabel}` : ''} · 起 +${formatDurationMs(node.startMs)} · 历时 ${span}${node.open ? '（进行中）' : ''}`
}

/** 条形太窄时不画标签，避免文字压出泳道。 */
function showLabel(node: TimingNode): boolean {
  return barWidth(node) > 54
}
</script>

<template>
  <div ref="host" class="graph">
    <svg
      v-if="model.lanes.length"
      class="canvas"
      :viewBox="`0 0 ${width} ${height}`"
      :width="width"
      :height="height"
      role="img"
      aria-label="执行时序图"
    >
      <!-- 泳道底纹与名称 -->
      <g v-for="(lane, index) in model.lanes" :key="lane.id">
        <rect
          class="lane-bg"
          :x="GUTTER"
          :y="laneY(index)"
          :width="plotWidth"
          :height="LANE_H"
          :class="{ alt: index % 2 === 1 }"
        />
        <text class="lane-label" :x="GUTTER - 10" :y="laneY(index) + LANE_H / 2 + 4">{{ lane.label }}</text>
      </g>

      <!-- 时间刻度线 -->
      <g>
        <line
          v-for="tick in ticks"
          :key="`grid-${tick.ms}`"
          class="grid"
          :x1="tick.x"
          :y1="PAD_TOP"
          :x2="tick.x"
          :y2="PAD_TOP + model.lanes.length * LANE_H"
        />
      </g>

      <!-- 因果连边（画在节点下层） -->
      <path v-for="edge in edges" :key="edge.key" class="edge" :d="edge.d" />

      <!-- 节点 -->
      <g v-for="(lane, index) in model.lanes" :key="`nodes-${lane.id}`">
        <g
          v-for="node in lane.nodes"
          :key="node.id"
          class="node"
          :class="[`st-${node.state}`, { on: node.id === selectedId, open: node.open }]"
          tabindex="0"
          role="button"
          :aria-label="nodeTitle(node)"
          @click="$emit('select', node)"
          @keydown.enter="$emit('select', node)"
          @keydown.space.prevent="$emit('select', node)"
        >
          <title>{{ nodeTitle(node) }}</title>
          <rect
            class="bar"
            :x="x(node.startMs)"
            :y="barY(index)"
            :width="barWidth(node)"
            :height="BAR_H"
            rx="5"
          />
          <text v-if="showLabel(node)" class="bar-label" :x="x(node.startMs) + 8" :y="barY(index) + BAR_H / 2 + 4">
            {{ node.label }}
          </text>
          <text
            v-if="node.sublabel && barWidth(node) > 110"
            class="bar-sub"
            :x="x(node.startMs) + barWidth(node) - 8"
            :y="barY(index) + BAR_H / 2 + 4"
            text-anchor="end"
          >
            {{ node.sublabel }}
          </text>
        </g>
      </g>

      <!-- 时间轴 -->
      <g class="axis" :transform="`translate(0 ${PAD_TOP + model.lanes.length * LANE_H})`">
        <line class="axis-line" :x1="GUTTER" :y1="0.5" :x2="GUTTER + plotWidth" :y2="0.5" />
        <text v-for="tick in ticks" :key="`tick-${tick.ms}`" class="axis-label" :x="tick.x" y="14">
          {{ tick.label }}
        </text>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.graph {
  width: 100%;
  overflow-x: auto;
}
.canvas {
  display: block;
}
.lane-bg {
  fill: transparent;
}
.lane-bg.alt {
  fill: color-mix(in oklch, var(--surface-2) 45%, transparent);
}
.lane-label {
  fill: var(--text-3);
  font-size: 11px;
  text-anchor: end;
}
.grid {
  stroke: var(--ws-rail);
  stroke-width: 1;
  stroke-dasharray: 2 4;
}
.edge {
  fill: none;
  stroke: var(--ws-rail);
  stroke-width: 1;
}
.axis-line {
  stroke: var(--ws-rail);
  stroke-width: 1;
}
.axis-label {
  fill: var(--text-3);
  font-family: var(--font-mono);
  font-size: 10px;
  text-anchor: middle;
}

.node {
  cursor: pointer;
  outline: none;
}
.bar {
  fill: var(--surface-3);
  stroke: var(--border-2);
  stroke-width: 1;
  transition: fill var(--ws-fast) var(--ws-ease), stroke var(--ws-fast) var(--ws-ease);
}
.bar-label {
  fill: var(--text);
  font-size: 11px;
  pointer-events: none;
}
.bar-sub {
  fill: var(--text-3);
  font-family: var(--font-mono);
  font-size: 10px;
  pointer-events: none;
}

/* 状态着色：只用系统状态色，类别靠泳道区分 */
.node.st-ok .bar {
  fill: color-mix(in oklch, var(--ws-ok) 18%, var(--surface));
  stroke: color-mix(in oklch, var(--ws-ok) 45%, var(--border));
}
.node.st-warn .bar {
  fill: color-mix(in oklch, var(--ws-warn) 18%, var(--surface));
  stroke: color-mix(in oklch, var(--ws-warn) 50%, var(--border));
}
.node.st-failed .bar {
  fill: color-mix(in oklch, var(--ws-danger) 18%, var(--surface));
  stroke: color-mix(in oklch, var(--ws-danger) 55%, var(--border));
}
.node.st-cancelled .bar {
  fill: var(--surface-2);
  stroke: var(--border);
  stroke-dasharray: 3 3;
}
.node.st-pending .bar {
  fill: var(--surface-2);
  stroke: var(--border-2);
}
/* 进行中：唯一使用发光与呼吸的地方 */
.node.st-running .bar {
  fill: color-mix(in oklch, var(--ws-running) 20%, var(--surface));
  stroke: var(--ws-running);
  animation: ws-breathe 1.8s ease-in-out infinite;
}
.node:hover .bar,
.node:focus-visible .bar {
  stroke: var(--accent);
}
.node:focus-visible .bar {
  stroke-width: 2;
}
.node.on .bar {
  stroke: var(--accent);
  stroke-width: 2;
}
</style>
