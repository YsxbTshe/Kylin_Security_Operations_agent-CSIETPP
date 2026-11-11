<script setup lang="ts">
/**
 * 环形图 —— 只用于"若干部分构成一个整体"的场景（如主机状态分布）。
 * 段与段之间留 2px 表面色缝隙，避免相邻同调色块糊在一起。
 * 每段 hover 显示数值与占比；数字用文本色，颜色只由色块承载身份。
 */
import { computed, ref } from 'vue'

interface Slice {
  label: string
  value: number
  /** CSS 颜色（传 var(--...)），由调用方按语义指定 */
  color: string
}

const props = withDefaults(
  defineProps<{
    slices: Slice[]
    /** 圆心上方的说明文字 */
    centerLabel?: string
    size?: number
    thickness?: number
  }>(),
  { size: 168, thickness: 18 },
)

const hovered = ref<number | null>(null)

const total = computed(() => props.slices.reduce((sum, s) => sum + s.value, 0))

const radius = computed(() => (props.size - props.thickness) / 2)
const center = computed(() => props.size / 2)
const circumference = computed(() => 2 * Math.PI * radius.value)

/** 2px 缝隙换算成弧长；总量为 0 时不画任何弧 */
const GAP = 2

const arcs = computed(() => {
  if (total.value <= 0) return []
  const visible = props.slices.filter((s) => s.value > 0)
  let offset = 0
  return visible.map((slice, index) => {
    const fraction = slice.value / total.value
    const full = circumference.value * fraction
    // 只有多于一段时才留缝隙，单段时留缝会出现一道突兀的裂口
    const gap = visible.length > 1 ? GAP : 0
    const length = Math.max(full - gap, 0.5)
    const arc = {
      ...slice,
      index,
      dasharray: `${length} ${circumference.value - length}`,
      dashoffset: -offset,
      percent: fraction * 100,
    }
    offset += full
    return arc
  })
})

const centerValue = computed(() => {
  if (hovered.value === null) return total.value
  return arcs.value[hovered.value]?.value ?? total.value
})

const centerCaption = computed(() => {
  if (hovered.value === null) return props.centerLabel ?? '总计'
  return arcs.value[hovered.value]?.label ?? props.centerLabel ?? '总计'
})
</script>

<template>
  <div class="donut">
    <svg
      :width="size"
      :height="size"
      :viewBox="`0 0 ${size} ${size}`"
      role="img"
      :aria-label="`环形图，总计 ${total}`"
    >
      <!-- 空态：画一圈极淡的轨道，避免出现空白方块 -->
      <circle
        v-if="total <= 0"
        :cx="center"
        :cy="center"
        :r="radius"
        fill="none"
        stroke="var(--surface-2)"
        :stroke-width="thickness"
      />
      <g :transform="`rotate(-90 ${center} ${center})`">
        <circle
          v-for="arc in arcs"
          :key="arc.label"
          :cx="center"
          :cy="center"
          :r="radius"
          fill="none"
          :stroke="arc.color"
          :stroke-width="hovered === arc.index ? thickness + 3 : thickness"
          :stroke-dasharray="arc.dasharray"
          :stroke-dashoffset="arc.dashoffset"
          stroke-linecap="butt"
          class="donut__arc"
          :class="{ 'donut__arc--dim': hovered !== null && hovered !== arc.index }"
          @mouseenter="hovered = arc.index"
          @mouseleave="hovered = null"
        />
      </g>
    </svg>

    <div class="donut__center">
      <div class="donut__value num">{{ centerValue }}</div>
      <div class="donut__caption">{{ centerCaption }}</div>
    </div>
  </div>
</template>

<style scoped>
.donut {
  position: relative;
  display: grid;
  place-items: center;
}
.donut svg {
  display: block;
}
.donut__arc {
  transition: stroke-width .16s ease, opacity .16s ease;
  cursor: pointer;
}
.donut__arc--dim {
  opacity: 0.38;
}
.donut__center {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  text-align: center;
  pointer-events: none;
}
.donut__value {
  font-size: 26px;
  font-weight: 620;
  line-height: 1;
  letter-spacing: -0.02em;
  color: var(--text);
}
.donut__caption {
  margin-top: 5px;
  font-size: 12px;
  color: var(--text-3);
}
</style>
