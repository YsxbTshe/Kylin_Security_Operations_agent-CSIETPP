<script setup lang="ts">
/**
 * 图例：≥2 个系列时必须存在，保证身份不是只靠颜色传达。
 * 文案用文本色，颜色只出现在色块上。
 */
defineProps<{
  items: { label: string; color: string; value?: number | string }[]
}>()
</script>

<template>
  <div v-for="item in items" :key="item.label" class="lg">
    <i class="lg__swatch" :style="{ background: item.color }" />
    <span class="lg__label">{{ item.label }}</span>
    <span v-if="item.value !== undefined" class="lg__value num">{{ item.value }}</span>
  </div>
</template>

<style scoped>
.lg {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
}
.lg__swatch {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  flex: none;
}
.lg__label {
  color: var(--text-2);
}
.lg__value {
  font-weight: 600;
  color: var(--text);
}
</style>
