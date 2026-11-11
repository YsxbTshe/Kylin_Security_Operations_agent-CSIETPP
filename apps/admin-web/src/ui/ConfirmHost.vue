<script setup lang="ts">
import {
  AlertDialogRoot,
  AlertDialogPortal,
  AlertDialogOverlay,
  AlertDialogContent,
  AlertDialogTitle,
  AlertDialogDescription,
} from 'reka-ui'
import Button from './Button.vue'
import { confirmState, settleConfirm } from './confirm'
</script>

<template>
  <AlertDialogRoot :open="confirmState.open">
    <AlertDialogPortal>
      <AlertDialogOverlay as-child>
        <div class="cf-overlay" />
      </AlertDialogOverlay>
      <AlertDialogContent as-child>
        <div class="cf">
          <AlertDialogTitle class="cf-title">{{ confirmState.options.title }}</AlertDialogTitle>
          <AlertDialogDescription class="cf-msg">
            {{ confirmState.options.message }}
          </AlertDialogDescription>
          <div class="cf-actions">
            <Button variant="default" @click="settleConfirm(false)">
              {{ confirmState.options.cancelText }}
            </Button>
            <Button
              :variant="confirmState.options.tone === 'danger' ? 'danger' : 'primary'"
              @click="settleConfirm(true)"
            >
              {{ confirmState.options.confirmText }}
            </Button>
          </div>
        </div>
      </AlertDialogContent>
    </AlertDialogPortal>
  </AlertDialogRoot>
</template>

<style scoped>
.cf-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  background: oklch(0.18 0.02 264 / 0.42);
  backdrop-filter: blur(2px);
  animation: ov-in 0.16s ease;
}
.cf {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: var(--z-modal);
  width: calc(100vw - 40px);
  max-width: 420px;
  padding: 22px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-pop);
  animation: cf-in 0.16s ease;
}
.cf-title {
  font-size: 16px;
  font-weight: 600;
}
.cf-msg {
  margin-top: 8px;
  font-size: 13.5px;
  color: var(--text-2);
  line-height: 1.5;
}
.cf-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 22px;
}
@keyframes ov-in {
  from {
    opacity: 0;
  }
}
@keyframes cf-in {
  from {
    opacity: 0;
    transform: translate(-50%, -48%) scale(0.98);
  }
}
</style>
