<script setup lang="ts">
import { SwitchRoot, SwitchThumb } from 'reka-ui'

defineProps<{ modelValue?: boolean; disabled?: boolean }>()
defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()
</script>

<template>
  <SwitchRoot
    class="sw"
    :model-value="modelValue"
    :disabled="disabled"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <SwitchThumb class="sw-thumb" />
  </SwitchRoot>
</template>

<style scoped>
.sw {
  width: 40px;
  height: 22px;
  flex: none;
  padding: 2px;
  border: none;
  border-radius: 999px;
  background: var(--border-2);
  cursor: pointer;
  transition: background 0.15s;
}
.sw[data-state='checked'] {
  background: var(--accent);
}
.sw:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.sw-thumb {
  display: block;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px oklch(0 0 0 / 0.25);
  transition: transform 0.15s;
}
.sw[data-state='checked'] .sw-thumb {
  transform: translateX(18px);
}
</style>
