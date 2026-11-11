<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>()
</script>

<template>
  <div class="ph">
    <div class="ph-main">
      <div v-if="eyebrow" class="ph-eyebrow">{{ eyebrow }}</div>
      <h1 class="ph-title">{{ title }}</h1>
      <p v-if="description" class="ph-desc">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="ph-actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped>
.ph {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 4px 0 8px;
}
.ph-eyebrow {
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--accent);
  font-weight: 500;
  margin-bottom: 8px;
}
.ph-title {
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.025em;
}
.ph-desc {
  max-width: 70ch;
  color: var(--text-2);
  font-size: 13.5px;
  margin-top: 7px;
}
.ph-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}
</style>
