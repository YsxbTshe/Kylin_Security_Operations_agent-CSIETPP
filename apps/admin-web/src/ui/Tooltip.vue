<script setup lang="ts">
import { TooltipRoot, TooltipTrigger, TooltipPortal, TooltipContent, TooltipArrow } from 'reka-ui'

withDefaults(
  defineProps<{
    content?: string
    side?: 'top' | 'right' | 'bottom' | 'left'
  }>(),
  { side: 'top' },
)
</script>

<template>
  <TooltipRoot>
    <TooltipTrigger as-child>
      <slot />
    </TooltipTrigger>
    <TooltipPortal>
      <TooltipContent :side="side" :side-offset="6" as-child>
        <div class="tt">
          {{ content }}
          <TooltipArrow class="tt-arrow" :width="10" :height="5" />
        </div>
      </TooltipContent>
    </TooltipPortal>
  </TooltipRoot>
</template>

<style scoped>
.tt {
  z-index: var(--z-dropdown);
  max-width: 260px;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  background: var(--text);
  color: var(--surface);
  font-size: 12.5px;
  line-height: 1.5;
  box-shadow: var(--shadow-pop);
}
.tt-arrow {
  fill: var(--text);
}
</style>
