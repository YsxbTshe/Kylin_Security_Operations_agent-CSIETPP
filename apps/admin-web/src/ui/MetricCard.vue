<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(
  defineProps<{
    label: string
    value: string | number
    description?: string
    icon?: Component
    tone?: 'accent' | 'ok' | 'warn' | 'danger'
  }>(),
  { tone: 'accent' },
)
</script>

<template>
  <div class="metric">
    <div class="metric-top">
      <span class="metric-label">{{ label }}</span>
      <span v-if="icon" class="metric-ic" :class="`t-${tone}`"><component :is="icon" /></span>
    </div>
    <div class="metric-value num">{{ value }}</div>
    <div v-if="description" class="metric-desc">{{ description }}</div>
  </div>
</template>

<style scoped>
.metric {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 20px;
  transition: border-color .16s, transform .16s;
}
.metric:hover {
  border-color: var(--border-2);
  transform: translateY(-1px);
}
.metric-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.metric-label {
  font-size: 13px;
  color: var(--text-2);
}
.metric-ic {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 7px;
}
.metric-ic :deep(svg) {
  width: 16px;
  height: 16px;
  stroke-width: 2;
}
.metric-value {
  font-size: 32px;
  font-weight: 620;
  line-height: 1;
  letter-spacing: -0.03em;
}
.metric-desc {
  margin-top: 11px;
  font-size: 12.5px;
  color: var(--text-3);
}
.t-accent {
  background: var(--accent-soft);
  color: var(--accent);
}
.t-ok {
  background: var(--ok-soft);
  color: var(--ok);
}
.t-warn {
  background: var(--warn-soft);
  color: var(--warn);
}
.t-danger {
  background: var(--danger-soft);
  color: var(--danger);
}
</style>
