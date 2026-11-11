<script setup lang="ts">
import { Inbox } from 'lucide-vue-next'
defineProps<{ description?: string }>()
</script>

<template>
  <div class="empty">
    <Inbox class="empty-ic" />
    <div class="empty-text">{{ description ?? '暂无数据' }}</div>
    <slot />
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 40px 20px;
  color: var(--text-3);
}
.empty-ic {
  width: 32px;
  height: 32px;
  opacity: 0.6;
}
.empty-text {
  font-size: 13px;
}
</style>
