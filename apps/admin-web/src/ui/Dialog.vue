<script setup lang="ts">
import {
  DialogRoot,
  DialogPortal,
  DialogOverlay,
  DialogContent,
  DialogTitle,
  DialogDescription,
  DialogClose,
} from 'reka-ui'
import { X } from 'lucide-vue-next'

withDefaults(
  defineProps<{
    open: boolean
    title?: string
    description?: string
    width?: string
  }>(),
  { width: '560px' },
)

defineEmits<{ (e: 'update:open', value: boolean): void }>()
</script>

<template>
  <DialogRoot :open="open" @update:open="$emit('update:open', $event)">
    <DialogPortal>
      <DialogOverlay as-child>
        <div class="dlg-overlay" />
      </DialogOverlay>
      <DialogContent as-child>
        <div class="dlg" :style="{ maxWidth: width }">
          <header class="dlg-head">
            <DialogTitle class="dlg-title">{{ title }}</DialogTitle>
            <DialogClose class="dlg-x" aria-label="关闭"><X /></DialogClose>
          </header>
          <DialogDescription v-if="description" class="dlg-desc">{{ description }}</DialogDescription>
          <div class="dlg-body"><slot /></div>
          <footer v-if="$slots.footer" class="dlg-foot"><slot name="footer" /></footer>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style scoped>
.dlg-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  background: oklch(0.18 0.02 264 / 0.42);
  backdrop-filter: blur(2px);
  animation: ov-in 0.16s ease;
}
.dlg {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: var(--z-modal);
  width: calc(100vw - 40px);
  max-height: calc(100vh - 64px);
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-pop);
  animation: dlg-in 0.16s ease;
}
.dlg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 0;
}
.dlg-title {
  font-size: 16px;
  font-weight: 600;
}
.dlg-x {
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
.dlg-x:hover {
  background: var(--surface-2);
  color: var(--text);
}
.dlg-x :deep(svg) {
  width: 17px;
  height: 17px;
}
.dlg-desc {
  padding: 4px 20px 0;
  font-size: 13px;
  color: var(--text-3);
}
.dlg-body {
  padding: 18px 20px;
  overflow-y: auto;
}
.dlg-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 0 20px 18px;
}
@keyframes ov-in {
  from {
    opacity: 0;
  }
}
@keyframes dlg-in {
  from {
    opacity: 0;
    transform: translate(-50%, -48%) scale(0.98);
  }
}
</style>
