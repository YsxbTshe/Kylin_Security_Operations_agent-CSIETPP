<script setup lang="ts" generic="T extends Record<string, any>">
import { computed } from 'vue'
import Empty from './Empty.vue'
import Spinner from './Spinner.vue'
import type { DataTableColumn } from './data-table'

const props = defineProps<{
  columns: DataTableColumn[]
  data: T[]
  rowKey?: string
  loading?: boolean
  empty?: string
}>()

const rows = computed(() => props.data ?? [])
</script>

<template>
  <div class="dt-wrap">
    <table class="dt">
      <thead>
        <tr>
          <th
            v-for="c in columns"
            :key="c.key"
            :style="{ width: c.width, minWidth: c.minWidth, textAlign: c.align }"
          >
            {{ c.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in rows" :key="rowKey ? row[rowKey] : i">
          <td v-for="c in columns" :key="c.key" :style="{ textAlign: c.align }">
            <slot :name="c.key" :row="row" :value="row[c.key]">{{ row[c.key] }}</slot>
          </td>
        </tr>
      </tbody>
    </table>

    <Empty v-if="!loading && rows.length === 0" :description="empty" />

    <div v-if="loading" class="dt-loading"><Spinner :size="22" /></div>
  </div>
</template>

<style scoped>
.dt-wrap {
  position: relative;
  min-height: 80px;
  overflow-x: auto;
}
.dt {
  width: 100%;
  border-collapse: collapse;
}
.dt th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  padding: 12px 18px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in oklch, var(--surface-2), var(--surface) 48%);
  white-space: nowrap;
}
.dt td {
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  vertical-align: middle;
}
.dt tbody tr:last-child td {
  border-bottom: none;
}
.dt tbody tr {
  transition: background 0.1s;
}
.dt tbody tr:hover {
  background: color-mix(in oklch, var(--accent-soft), var(--surface) 58%);
}
.dt-loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: color-mix(in oklch, var(--surface), transparent 25%);
  color: var(--accent);
}
</style>
