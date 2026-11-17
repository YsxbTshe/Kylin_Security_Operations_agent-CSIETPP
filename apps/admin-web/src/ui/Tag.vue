<script setup lang="ts">
import { X } from 'lucide-vue-next'

withDefaults(
  defineProps<{
    tone?: 'neutral' | 'accent' | 'ok' | 'warn' | 'danger'
    closable?: boolean
  }>(),
  { tone: 'neutral' },
)

defineEmits<{ (e: 'close'): void }>()
</script>

<template>
  <span class="tag" :class="`t-${tone}`">
    <slot />
    <button v-if="closable" type="button" class="tag-x" @click="$emit('close')"><X /></button>
  </span>
</template>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 500;
  padding: 2px 9px;
  border-radius: var(--radius-sm);
  box-shadow: inset 0 0 0 1px var(--ring-c, transparent);
}
.tag-x {
  display: grid;
  place-items: center;
  border: none;
  background: none;
  cursor: pointer;
  color: inherit;
  opacity: 0.7;
}
.tag-x:hover {
  opacity: 1;
}
.tag-x :deep(svg) {
  width: 12px;
  height: 12px;
}
.t-neutral {
  background: var(--surface-2);
  color: var(--text-3);
  --ring-c: var(--border);
}
.t-accent {
  background: var(--accent-soft);
  color: var(--accent);
  --ring-c: var(--accent-ring);
}
.t-ok {
  background: var(--ok-soft);
  color: var(--ok);
  --ring-c: var(--ok-ring);
}
.t-warn {
  background: var(--warn-soft);
  color: var(--warn);
  --ring-c: var(--warn-ring);
}
.t-danger {
  background: var(--danger-soft);
  color: var(--danger);
  --ring-c: var(--danger-ring);
}
</style>
