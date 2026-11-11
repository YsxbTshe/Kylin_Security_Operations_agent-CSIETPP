<script setup lang="ts">
import { CircleCheck, CircleAlert, TriangleAlert, Info, X } from 'lucide-vue-next'
import { toastItems, toast, type ToastTone } from './toast'

const icons: Record<ToastTone, typeof Info> = {
  success: CircleCheck,
  error: CircleAlert,
  warning: TriangleAlert,
  info: Info,
}
</script>

<template>
  <Teleport to="body">
    <TransitionGroup name="toast" tag="div" class="toast-host">
      <div v-for="t in toastItems" :key="t.id" class="toast" :class="`t-${t.tone}`">
        <component :is="icons[t.tone]" class="toast-ic" />
        <span class="toast-msg">{{ t.message }}</span>
        <button type="button" class="toast-x" @click="toast.dismiss(t.id)"><X /></button>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 18px;
  right: 18px;
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}
.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 260px;
  max-width: 380px;
  padding: 11px 13px;
  border-radius: var(--radius);
  background: var(--surface);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-pop);
  pointer-events: auto;
}
.toast-ic {
  width: 18px;
  height: 18px;
  flex: none;
}
.toast-msg {
  flex: 1;
  font-size: 13.5px;
  color: var(--text);
}
.toast-x {
  display: grid;
  place-items: center;
  border: none;
  background: none;
  color: var(--text-3);
  cursor: pointer;
}
.toast-x:hover {
  color: var(--text-2);
}
.toast-x :deep(svg) {
  width: 15px;
  height: 15px;
}
.t-success .toast-ic {
  color: var(--ok);
}
.t-error .toast-ic {
  color: var(--danger);
}
.t-warning .toast-ic {
  color: var(--warn);
}
.t-info .toast-ic {
  color: var(--accent);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(16px);
}
</style>
