<script setup lang="ts">
import type { Component } from 'vue'
import Spinner from './Spinner.vue'

withDefaults(
  defineProps<{
    variant?: 'primary' | 'default' | 'danger' | 'subtle'
    size?: 'sm' | 'md'
    link?: boolean
    block?: boolean
    loading?: boolean
    disabled?: boolean
    type?: 'button' | 'submit'
    icon?: Component
  }>(),
  { variant: 'default', size: 'md', type: 'button' },
)
</script>

<template>
  <button
    :type="type"
    class="btn"
    :class="[`v-${variant}`, `s-${size}`, { link, block }]"
    :disabled="disabled || loading"
  >
    <Spinner v-if="loading" class="ic" />
    <component :is="icon" v-else-if="icon" class="ic" />
    <span v-if="$slots.default" class="lb"><slot /></span>
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border-radius: var(--radius);
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
  transition: background .15s, border-color .15s, color .15s, opacity .15s, transform .15s;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn .ic {
  width: 16px;
  height: 16px;
  flex: none;
}
.lb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  line-height: 1;
}
.lb :deep(svg) {
  display: block;
  flex: none;
}
.s-md {
  height: 36px;
  padding: 0 14px;
  font-size: 13.5px;
}
.s-sm {
  height: 30px;
  padding: 0 11px;
  font-size: 13px;
}
.s-sm .ic {
  width: 15px;
  height: 15px;
}
.block {
  width: 100%;
}

.v-primary {
  background: var(--accent);
  color: var(--accent-contrast);
  border: 1px solid transparent;
}
.v-primary:hover:not(:disabled) {
  background: var(--accent-2);
  transform: translateY(-1px);
}
.v-default {
  background: var(--surface);
  color: var(--text);
  border: 1px solid var(--border);
}
.v-default:hover:not(:disabled) {
  border-color: var(--border-2);
  background: var(--surface-2);
}
.v-danger {
  background: var(--danger);
  color: #fff;
  border: 1px solid transparent;
}
.v-danger:hover:not(:disabled) {
  background: color-mix(in oklch, var(--danger), black 8%);
}
.v-subtle {
  background: transparent;
  color: var(--text-2);
  border: 1px solid transparent;
}
.v-subtle:hover:not(:disabled) {
  background: var(--surface-2);
  color: var(--text);
}

/* link mode overrides any variant */
.btn.link {
  height: auto;
  padding: 0 2px;
  background: none;
  border: none;
  color: var(--accent);
}
.btn.link.v-danger {
  color: var(--danger);
}
.btn.link.v-subtle {
  color: var(--text-2);
}
.btn.link:hover:not(:disabled) {
  text-decoration: underline;
  background: none;
}
</style>
