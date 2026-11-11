<script setup lang="ts">
withDefaults(
  defineProps<{
    tone?: 'neutral' | 'accent' | 'ok' | 'warn' | 'danger'
    timestamp?: string
    title?: string
    last?: boolean
  }>(),
  { tone: 'neutral' },
)
</script>

<template>
  <div class="tl-item" :class="{ last }">
    <div class="tl-rail">
      <span class="tl-dot" :class="`t-${tone}`" />
    </div>
    <div class="tl-body">
      <div class="tl-head">
        <span class="tl-title"><slot name="title">{{ title }}</slot></span>
        <span v-if="timestamp" class="tl-time num">{{ timestamp }}</span>
      </div>
      <div v-if="$slots.default" class="tl-content"><slot /></div>
    </div>
  </div>
</template>

<style scoped>
.tl-item {
  display: flex;
  gap: 12px;
}
.tl-rail {
  position: relative;
  display: flex;
  justify-content: center;
  width: 12px;
  flex: none;
}
.tl-rail::before {
  content: '';
  position: absolute;
  top: 4px;
  bottom: 0;
  width: 1px;
  background: var(--border);
}
.tl-item.last .tl-rail::before {
  display: none;
}
.tl-dot {
  position: relative;
  z-index: 1;
  width: 9px;
  height: 9px;
  margin-top: 4px;
  border-radius: 50%;
  border: 2px solid var(--surface);
  background: var(--text-3);
}
.tl-body {
  flex: 1;
  padding-bottom: 18px;
}
.tl-item.last .tl-body {
  padding-bottom: 0;
}
.tl-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.tl-title {
  font-size: 13.5px;
  font-weight: 500;
}
.tl-time {
  font-size: 12px;
  color: var(--text-3);
}
.tl-content {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-2);
}
.t-accent {
  background: var(--accent);
}
.t-ok {
  background: var(--ok);
}
.t-warn {
  background: var(--warn);
}
.t-danger {
  background: var(--danger);
}
</style>
