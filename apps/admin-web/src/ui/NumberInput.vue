<script setup lang="ts">
import { NumberFieldRoot, NumberFieldInput, NumberFieldDecrement, NumberFieldIncrement } from 'reka-ui'
import { Minus, Plus } from 'lucide-vue-next'

defineProps<{
  modelValue?: number
  min?: number
  max?: number
  step?: number
  disabled?: boolean
}>()

defineEmits<{ (e: 'update:modelValue', value: number): void }>()
</script>

<template>
  <NumberFieldRoot
    class="nf"
    :model-value="modelValue"
    :min="min"
    :max="max"
    :step="step"
    :disabled="disabled"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <NumberFieldDecrement class="nf-btn"><Minus /></NumberFieldDecrement>
    <NumberFieldInput class="nf-input" />
    <NumberFieldIncrement class="nf-btn"><Plus /></NumberFieldIncrement>
  </NumberFieldRoot>
</template>

<style scoped>
.nf {
  display: flex;
  align-items: center;
  height: 36px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  overflow: hidden;
  transition: border-color 0.13s, box-shadow 0.13s;
}
.nf:focus-within {
  border-color: var(--accent);
  box-shadow: var(--ring);
}
.nf-btn {
  display: grid;
  place-items: center;
  width: 34px;
  height: 100%;
  border: none;
  background: var(--surface-2);
  color: var(--text-2);
  cursor: pointer;
}
.nf-btn:hover {
  color: var(--text);
}
.nf-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.nf-btn :deep(svg) {
  width: 15px;
  height: 15px;
}
.nf-input {
  flex: 1;
  width: 100%;
  min-width: 0;
  border: none;
  outline: none;
  background: none;
  text-align: center;
  color: var(--text);
  font-size: 13.5px;
  font-variant-numeric: tabular-nums;
}
</style>
