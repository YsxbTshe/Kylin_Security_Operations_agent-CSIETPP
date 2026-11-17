<script setup lang="ts">
defineProps<{
  title: string
  hint?: string
}>()
</script>

<template>
  <section class="hex-panel">
    <div class="hex-panel__frame">
      <div class="hex-panel__inner">
        <header class="hex-panel__header">
          <div class="hex-panel__title">
            <span class="hex-panel__title-bar" />
            <span>{{ title }}</span>
          </div>
          <div v-if="hint || $slots.hint" class="hex-panel__hint">
            <slot name="hint">{{ hint }}</slot>
          </div>
        </header>
        <div class="hex-panel__body">
          <slot />
        </div>
      </div>
    </div>
    <span class="hex-panel__corner hex-panel__corner--tl" />
    <span class="hex-panel__corner hex-panel__corner--tr" />
    <span class="hex-panel__corner hex-panel__corner--bl" />
    <span class="hex-panel__corner hex-panel__corner--br" />
  </section>
</template>
