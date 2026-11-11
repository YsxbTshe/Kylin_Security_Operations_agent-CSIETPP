<script setup lang="ts">
/**
 * 图表统一外框：标题 + 图例 + 「图/表」切换。
 *
 * 「表」不是附赠功能：颜色永远不能是唯一的信息载体，任何一张图都必须能退化成
 * 一份可读的数字表（色觉障碍、强制配色、打印、截图追问口径时都要用到）。
 */
import { ref } from 'vue'
import { Table2, ChartNoAxesColumn } from 'lucide-vue-next'

defineProps<{
  title: string
  description?: string
  /** 表视图的表头，顺序与 rows 内每行一致 */
  tableHead?: string[]
  /** 表视图的数据行 */
  tableRows?: (string | number)[][]
}>()

const mode = ref<'chart' | 'table'>('chart')
</script>

<template>
  <section class="cf">
    <header class="cf__head">
      <div class="cf__titles">
        <h3 class="cf__title">{{ title }}</h3>
        <p v-if="description" class="cf__desc">{{ description }}</p>
      </div>
      <button
        v-if="tableRows?.length"
        type="button"
        class="cf__toggle"
        :title="mode === 'chart' ? '切换为数据表' : '切换为图表'"
        :aria-label="mode === 'chart' ? '切换为数据表' : '切换为图表'"
        @click="mode = mode === 'chart' ? 'table' : 'chart'"
      >
        <component :is="mode === 'chart' ? Table2 : ChartNoAxesColumn" />
      </button>
    </header>

    <div v-show="mode === 'chart'" class="cf__body">
      <slot />
    </div>

    <div v-if="mode === 'table'" class="cf__body">
      <table class="cf__table">
        <thead>
          <tr>
            <th v-for="h in tableHead" :key="h">{{ h }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, i) in tableRows" :key="i">
            <td v-for="(cell, j) in row" :key="j" :class="{ num: j > 0 }">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer v-if="$slots.legend" class="cf__legend">
      <slot name="legend" />
    </footer>
  </section>
</template>

<style scoped>
.cf {
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 18px 20px 16px;
}
.cf__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.cf__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}
.cf__desc {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
.cf__toggle {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text-3);
  cursor: pointer;
  transition: color .15s, border-color .15s, background .15s;
}
.cf__toggle:hover {
  color: var(--text);
  border-color: var(--border-2);
  background: var(--surface-2);
}
.cf__toggle:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}
.cf__toggle :deep(svg) {
  width: 14px;
  height: 14px;
  stroke-width: 2;
}
.cf__body {
  flex: 1;
  min-height: 0;
}
.cf__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.cf__table th {
  text-align: left;
  font-weight: 500;
  color: var(--text-3);
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
}
.cf__table td {
  padding: 6px 8px;
  color: var(--text-2);
  border-bottom: 1px solid var(--border);
}
.cf__table td.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--text);
}
.cf__table tr:last-child td {
  border-bottom: none;
}
.cf__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
</style>
