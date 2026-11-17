<script setup lang="ts">
import { TabsRoot, TabsList, TabsTrigger, TabsContent } from 'reka-ui'

defineProps<{
  modelValue: string
  tabs: { value: string; label: string }[]
}>()

defineEmits<{ (e: 'update:modelValue', value: string): void }>()
</script>

<template>
  <TabsRoot
    class="tabs"
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event as string)"
  >
    <TabsList class="tabs-list">
      <TabsTrigger v-for="t in tabs" :key="t.value" :value="t.value" class="tabs-trigger">
        {{ t.label }}
      </TabsTrigger>
    </TabsList>
    <TabsContent v-for="t in tabs" :key="t.value" :value="t.value" class="tabs-content">
      <slot :name="t.value" />
    </TabsContent>
  </TabsRoot>
</template>

<style scoped>
.tabs-list {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--border);
}
.tabs-trigger {
  position: relative;
  padding: 9px 4px;
  margin-bottom: -1px;
  border: none;
  background: none;
  color: var(--text-2);
  font-size: 13.5px;
  font-weight: 500;
  cursor: pointer;
}
.tabs-trigger + .tabs-trigger {
  margin-left: 14px;
}
.tabs-trigger:hover {
  color: var(--text);
}
.tabs-trigger[data-state='active'] {
  color: var(--accent);
}
.tabs-trigger[data-state='active']::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: var(--accent);
}
.tabs-content {
  padding-top: 16px;
}
.tabs-content:focus-visible {
  outline: none;
}
</style>
