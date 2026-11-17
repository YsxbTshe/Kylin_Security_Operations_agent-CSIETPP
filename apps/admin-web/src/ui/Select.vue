<script setup lang="ts">
import {
  SelectRoot,
  SelectTrigger,
  SelectValue,
  SelectIcon,
  SelectPortal,
  SelectContent,
  SelectViewport,
  SelectItem,
  SelectItemText,
  SelectItemIndicator,
} from 'reka-ui'
import { ChevronDown, Check } from 'lucide-vue-next'
import type { SelectOption } from './option'

defineProps<{
  modelValue?: string
  options: SelectOption[]
  placeholder?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'change', value: string): void
}>()

function onChange(value: string) {
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <SelectRoot :model-value="modelValue" :disabled="disabled" @update:model-value="onChange">
    <SelectTrigger class="sel-trigger">
      <SelectValue :placeholder="placeholder ?? '请选择'" />
      <SelectIcon class="sel-ic"><ChevronDown /></SelectIcon>
    </SelectTrigger>
    <SelectPortal>
      <SelectContent position="popper" :side-offset="6" as-child>
        <div class="sel-content">
          <SelectViewport class="sel-viewport">
            <SelectItem
              v-for="o in options"
              :key="o.value"
              :value="o.value"
              :disabled="o.disabled"
              class="sel-item"
            >
              <SelectItemText>{{ o.label }}</SelectItemText>
              <SelectItemIndicator class="sel-check"><Check /></SelectItemIndicator>
            </SelectItem>
          </SelectViewport>
        </div>
      </SelectContent>
    </SelectPortal>
  </SelectRoot>
</template>

<style scoped>
.sel-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  height: 36px;
  padding: 0 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text);
  font-size: 13.5px;
  cursor: pointer;
  transition: border-color 0.13s, box-shadow 0.13s;
}
.sel-trigger:focus-visible {
  outline: none;
  border-color: var(--accent);
  box-shadow: var(--ring);
}
.sel-trigger[data-placeholder] {
  color: var(--text-3);
}
.sel-trigger[data-disabled] {
  opacity: 0.6;
  cursor: not-allowed;
}
.sel-ic {
  color: var(--text-3);
}
.sel-ic :deep(svg) {
  width: 16px;
  height: 16px;
}
.sel-content {
  min-width: var(--reka-select-trigger-width);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-pop);
  padding: 4px;
  z-index: var(--z-dropdown);
}
.sel-viewport {
  max-height: 280px;
}
.sel-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 9px;
  border-radius: var(--radius-sm);
  font-size: 13.5px;
  color: var(--text);
  cursor: pointer;
  user-select: none;
}
.sel-item[data-highlighted] {
  outline: none;
  background: var(--surface-2);
}
.sel-item[data-state='checked'] {
  color: var(--accent);
}
.sel-item[data-disabled] {
  opacity: 0.45;
  pointer-events: none;
}
.sel-check {
  color: var(--accent);
}
.sel-check :deep(svg) {
  width: 15px;
  height: 15px;
}
</style>
