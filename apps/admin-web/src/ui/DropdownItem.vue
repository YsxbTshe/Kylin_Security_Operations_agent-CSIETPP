<script setup lang="ts">
import { DropdownMenuItem, DropdownMenuSeparator } from 'reka-ui'

defineProps<{
  disabled?: boolean
  divided?: boolean
  danger?: boolean
}>()

defineEmits<{ (e: 'select'): void }>()
</script>

<template>
  <DropdownMenuSeparator v-if="divided" class="dd-sep" />
  <DropdownMenuItem
    class="dd-item"
    :class="{ danger }"
    :disabled="disabled"
    @select="$emit('select')"
  >
    <slot />
  </DropdownMenuItem>
</template>

<style scoped>
.dd-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  font-size: 13.5px;
  color: var(--text-2);
  cursor: pointer;
  user-select: none;
}
.dd-item[data-highlighted] {
  outline: none;
  background: var(--surface-2);
  color: var(--text);
}
.dd-item.danger {
  color: var(--danger);
}
.dd-item[data-disabled] {
  opacity: 0.45;
  pointer-events: none;
}
.dd-item :deep(svg) {
  width: 16px;
  height: 16px;
}
.dd-sep {
  height: 1px;
  margin: 5px 0;
  background: var(--border);
}
</style>
