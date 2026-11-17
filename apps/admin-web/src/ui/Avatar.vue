<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string
    size?: number
    alt?: string
  }>(),
  { size: 36 },
)

const dim = computed(() => `${props.size}px`)
const radius = computed(() => `${Math.round(props.size * 0.28)}px`)
const fontSize = computed(() => `${Math.round(props.size * 0.42)}px`)
</script>

<template>
  <span class="avatar" :style="{ width: dim, height: dim, borderRadius: radius, fontSize }">
    <img v-if="src" :src="src" :alt="alt" />
    <slot v-else />
  </span>
</template>

<style scoped>
.avatar {
  display: inline-grid;
  place-items: center;
  flex: none;
  overflow: hidden;
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
  line-height: 1;
}
.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
