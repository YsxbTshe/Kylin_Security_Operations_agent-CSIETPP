<script setup lang="ts">
import {
  DialogRoot,
  DialogPortal,
  DialogOverlay,
  DialogContent,
  DialogTitle,
  DialogClose,
} from 'reka-ui'
import { X } from 'lucide-vue-next'

withDefaults(
  defineProps<{
    open: boolean
    title?: string
    width?: string
    fullscreen?: boolean
  }>(),
  { width: '420px', fullscreen: false },
)

defineEmits<{ (e: 'update:open', value: boolean): void }>()
</script>

<template>
  <DialogRoot :open="open" @update:open="$emit('update:open', $event)">
    <DialogPortal>
      <DialogOverlay as-child>
        <div class="dw-overlay" />
      </DialogOverlay>
      <DialogContent as-child>
        <div class="dw" :class="{ 'dw--full': fullscreen }" :style="fullscreen ? undefined : { width }">
          <header class="dw-head">
            <DialogTitle class="dw-title">{{ title }}</DialogTitle>
            <DialogClose class="dw-x" aria-label="关闭"><X /></DialogClose>
          </header>
          <div class="dw-body"><slot /></div>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.dw-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  background: oklch(0.18 0.02 264 / 0.42);
  backdrop-filter: blur(2px);
  animation: ov-in 0.16s ease;
}
.dw {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: var(--z-modal);
  max-width: calc(100vw - 48px);
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow-pop);
  animation: dw-in 0.2s cubic-bezier(0.22, 1, 0.36, 1);
}
.dw--full {
  left: 0;
  right: 0;
  width: 100%;
  max-width: none;
  border-left: none;
  animation: dw-up 0.2s cubic-bezier(0.22, 1, 0.36, 1);
}
.dw-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  border-bottom: 1px solid var(--border);
  flex: none;
}
.dw--full .dw-head {
  padding: 16px 24px;
}
.dw-title {
  font-size: 15px;
  font-weight: 600;
}
.dw-x {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text-3);
  cursor: pointer;
}
.dw-x:hover {
  background: var(--surface-2);
  color: var(--text);
}
.dw-x :deep(svg) {
  width: 17px;
  height: 17px;
}
.dw-body {
  flex: 1;
  overflow-y: auto;
}
@keyframes ov-in {
  from {
    opacity: 0;
  }
}
@keyframes dw-in {
  from {
    transform: translateX(100%);
  }
}
@keyframes dw-up {
  from {
    transform: translateY(1.5%);
    opacity: 0;
  }
}
</style>
