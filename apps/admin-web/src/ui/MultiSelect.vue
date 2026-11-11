<script setup lang="ts">
import { computed } from 'vue'
import { PopoverRoot, PopoverTrigger, PopoverPortal, PopoverContent } from 'reka-ui'
import { ChevronDown, Check } from 'lucide-vue-next'
import type { SelectOption } from './option'

const props = defineProps<{
  modelValue: string[]
  options: SelectOption[]
  placeholder?: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
  (e: 'change', value: string[]): void
}>()

const selected = computed(() => props.options.filter((o) => props.modelValue.includes(o.value)))

function toggle(value: string) {
  const next = props.modelValue.includes(value)
    ? props.modelValue.filter((v) => v !== value)
    : [...props.modelValue, value]
  emit('update:modelValue', next)
  emit('change', next)
}
</script>

<template>
  <PopoverRoot>
    <PopoverTrigger as-child>
      <button type="button" class="ms-trigger" :disabled="disabled">
        <span v-if="selected.length === 0" class="ms-ph">{{ placeholder ?? '请选择' }}</span>
        <span v-else class="ms-chips">
          <span v-for="o in selected" :key="o.value" class="ms-chip">{{ o.label }}</span>
        </span>
        <ChevronDown class="ms-ic" />
      </button>
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent :side-offset="6" align="start" as-child>
        <div class="ms-content">
          <button
            v-for="o in options"
            :key="o.value"
            type="button"
            class="ms-item"
            :class="{ on: modelValue.includes(o.value) }"
            :disabled="o.disabled"
            @click="toggle(o.value)"
          >
            <span class="ms-box"><Check v-if="modelValue.includes(o.value)" /></span>
            <span>{{ o.label }}</span>
          </button>
        </div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<style scoped>
.ms-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 36px;
  padding: 4px 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text);
  font-size: 13.5px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.13s, box-shadow 0.13s;
}
.ms-trigger:focus-visible {
  outline: none;
  border-color: var(--accent);
  box-shadow: var(--ring);
}
.ms-trigger:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.ms-ph {
  color: var(--text-3);
}
.ms-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  flex: 1;
}
.ms-chip {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: var(--radius-sm);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}
.ms-ic {
  width: 16px;
  height: 16px;
  color: var(--text-3);
  flex: none;
  margin-left: auto;
}
.ms-content {
  min-width: var(--reka-popover-trigger-width);
  max-height: 280px;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-pop);
  padding: 4px;
  z-index: var(--z-dropdown);
}
.ms-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 9px;
  border: none;
  background: none;
  border-radius: var(--radius-sm);
  font-size: 13.5px;
  color: var(--text);
  cursor: pointer;
  text-align: left;
}
.ms-item:hover {
  background: var(--surface-2);
}
.ms-item:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.ms-box {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border: 1px solid var(--border-2);
  border-radius: 4px;
  color: var(--accent-contrast);
  flex: none;
}
.ms-item.on .ms-box {
  background: var(--accent);
  border-color: var(--accent);
}
.ms-box :deep(svg) {
  width: 12px;
  height: 12px;
}
</style>
