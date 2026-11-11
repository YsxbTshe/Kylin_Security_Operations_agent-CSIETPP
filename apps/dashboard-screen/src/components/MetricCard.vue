<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  value: string | number
  suffix?: string
  tone?: 'primary' | 'success' | 'warning' | 'danger'
  footer?: string
  footerTone?: 'success' | 'warning' | 'danger' | 'muted'
}>(), {
  tone: 'primary',
  footerTone: 'muted',
})
</script>

<template>
  <div class="metric-card" :data-tone="tone">
    <div class="metric-card__inner">
      <div class="metric-card__label">{{ label }}</div>
      <div class="metric-card__value">
        {{ value }}<span v-if="suffix">{{ suffix }}</span>
      </div>
      <div
        v-if="footer"
        class="metric-card__foot"
        :class="footerTone !== 'muted' ? `metric-card__foot--${footerTone}` : ''"
      >
        {{ footer }}
      </div>
    </div>
  </div>
</template>
