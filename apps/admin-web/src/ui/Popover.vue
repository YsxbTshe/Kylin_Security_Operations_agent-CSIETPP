<script setup lang="ts">
import { PopoverRoot, PopoverTrigger, PopoverPortal, PopoverContent, PopoverArrow } from 'reka-ui'

withDefaults(
  defineProps<{
    side?: 'top' | 'right' | 'bottom' | 'left'
    align?: 'start' | 'center' | 'end'
    width?: string
  }>(),
  { side: 'bottom', align: 'center' },
)
</script>

<template>
  <PopoverRoot>
    <PopoverTrigger as-child>
      <slot name="trigger" />
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent :side="side" :align="align" :side-offset="6" as-child>
        <div class="pop" :style="{ width }">
          <slot />
          <PopoverArrow class="pop-arrow" :width="10" :height="5" />
        </div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>

<style scoped>
.pop {
  z-index: var(--z-dropdown);
  max-width: 360px;
  padding: 12px 14px;
  border-radius: var(--radius);
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text);
  font-size: 13px;
  box-shadow: var(--shadow-pop);
}
.pop-arrow {
  fill: var(--surface);
  stroke: var(--border);
}
</style>
