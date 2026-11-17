<script setup lang="ts">
defineProps<{
  title?: string
  description?: string
  flush?: boolean
  fill?: boolean
}>()
</script>

<template>
  <section class="section" :class="{ fill }">
    <header v-if="title || $slots.actions" class="section-h">
      <div class="section-titles">
        <div v-if="title" class="section-title">{{ title }}</div>
        <div v-if="description" class="section-desc">{{ description }}</div>
      </div>
      <div v-if="$slots.actions" class="section-actions"><slot name="actions" /></div>
    </header>
    <div class="section-body" :class="{ flush, fill }">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.section {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: border-color .16s ease;
}
.section:hover { border-color: color-mix(in oklch, var(--border-2), var(--border) 50%); }
.section.fill {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.section-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 15px 19px;
  border-bottom: 1px solid var(--border);
  flex: none;
}
.section-title {
  font-size: 13.5px;
  font-weight: 650;
}
.section-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 3px;
}
.section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-body {
  padding: 20px;
}
.section-body.flush {
  padding: 0;
}
.section-body.fill {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
