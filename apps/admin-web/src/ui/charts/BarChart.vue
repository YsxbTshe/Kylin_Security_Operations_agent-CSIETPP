<script setup lang="ts">
/**
 * 纵向柱状图 —— 用于"按类别比大小"（任务状态、告警级别）。
 *
 * 规则：
 * - 柱子细、顶端 4px 圆角、底端贴基线（数据从 0 起算，不做腰斩）；
 * - 相邻柱之间留 2px 表面色缝隙；
 * - 数值直接标在柱顶，不依赖 tooltip 才能读数；
 * - 网格与坐标轴保持退让，不与数据争视觉权重。
 */
import { computed, ref } from 'vue'

interface Bar {
  label: string
  value: number
  color: string
}

const props = withDefaults(
  defineProps<{
    bars: Bar[]
    height?: number
    /** 数值单位后缀，如 "条" */
    unit?: string
  }>(),
  { height: 180, unit: '' },
)

const hovered = ref<number | null>(null)

const max = computed(() => Math.max(1, ...props.bars.map((b) => b.value)))

/** 顶部留 22px 给数值标签，底部留 24px 给类别名 */
const PAD_TOP = 22
const PAD_BOTTOM = 24

const plotHeight = computed(() => props.height - PAD_TOP - PAD_BOTTOM)

function barHeight(value: number) {
  if (value <= 0) return 0
  // 非零值至少给 2px，否则"有但很小"会被画成"没有"
  return Math.max(2, (value / max.value) * plotHeight.value)
}

/** 三条退让的参考线：0 / 50% / 100% */
const gridLines = computed(() => [0, 0.5, 1].map((r) => ({
  ratio: r,
  y: PAD_TOP + plotHeight.value * (1 - r),
  value: Math.round(max.value * r),
})))
</script>

<template>
  <div class="bars" :style="{ height: `${height}px` }">
    <div class="bars__grid">
      <div
        v-for="line in gridLines"
        :key="line.ratio"
        class="bars__gridline"
        :style="{ top: `${line.y}px` }"
      >
        <span class="bars__gridvalue num">{{ line.value }}</span>
      </div>
    </div>

    <div class="bars__row">
      <div
        v-for="(bar, index) in bars"
        :key="bar.label"
        class="bars__col"
        @mouseenter="hovered = index"
        @mouseleave="hovered = null"
      >
        <div class="bars__value num" :class="{ 'bars__value--on': hovered === index }">
          {{ bar.value }}<span v-if="unit" class="bars__unit">{{ unit }}</span>
        </div>
        <div class="bars__track" :style="{ height: `${plotHeight}px` }">
          <div
            class="bars__fill"
            :style="{ height: `${barHeight(bar.value)}px`, background: bar.color }"
            :class="{ 'bars__fill--dim': hovered !== null && hovered !== index }"
          />
        </div>
        <div class="bars__label">{{ bar.label }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bars {
  position: relative;
  width: 100%;
}
.bars__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.bars__gridline {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--chart-grid);
  opacity: 0.7;
}
.bars__gridvalue {
  position: absolute;
  right: 0;
  top: -8px;
  font-size: 10.5px;
  color: var(--text-3);
  background: var(--surface);
  padding: 0 3px;
}
.bars__row {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 100%;
}
.bars__col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  cursor: default;
}
.bars__value {
  height: 22px;
  display: flex;
  align-items: center;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-2);
  transition: color .15s;
}
.bars__value--on {
  color: var(--text);
}
.bars__unit {
  margin-left: 1px;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--text-3);
}
.bars__track {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  width: 100%;
}
.bars__fill {
  width: min(42px, 62%);
  border-radius: 4px 4px 0 0;
  transition: opacity .16s ease, filter .16s ease;
}
.bars__fill--dim {
  opacity: 0.4;
}
.bars__col:hover .bars__fill {
  filter: brightness(1.06);
}
.bars__label {
  height: 24px;
  display: flex;
  align-items: center;
  font-size: 12px;
  color: var(--text-3);
  white-space: nowrap;
}
</style>
