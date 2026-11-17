<script setup lang="ts">
defineProps<{
  label?: string
  hint?: string
  error?: string
  required?: boolean
}>()
</script>

<template>
  <div class="ff">
    <label v-if="label" class="ff-label">
      {{ label }}
      <span v-if="required" class="ff-req">*</span>
    </label>
    <div class="ff-control"><slot /></div>
    <div v-if="error" class="ff-error">{{ error }}</div>
    <div v-else-if="hint" class="ff-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.ff {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ff-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
}
.ff-req {
  color: var(--danger);
  margin-left: 2px;
}
.ff-hint {
  font-size: 12px;
  color: var(--text-3);
}
.ff-error {
  font-size: 12px;
  color: var(--danger);
}
</style>
