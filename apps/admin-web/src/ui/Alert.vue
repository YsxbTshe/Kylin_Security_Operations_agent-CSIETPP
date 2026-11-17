<script setup lang="ts">
import { computed, ref } from 'vue'
import { CircleCheck, CircleAlert, TriangleAlert, Info, X } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    title?: string
    tone?: 'info' | 'ok' | 'warn' | 'danger'
    closable?: boolean
    showIcon?: boolean
  }>(),
  { tone: 'info', showIcon: true },
)

const icons = { info: Info, ok: CircleCheck, warn: TriangleAlert, danger: CircleAlert }
const icon = computed(() => icons[props.tone])
const closed = ref(false)
</script>

<template>
  <div v-if="!closed" class="alert" :class="`t-${tone}`">
    <component :is="icon" v-if="showIcon" class="alert-ic" />
    <div class="alert-body">
      <div v-if="title" class="alert-title">{{ title }}</div>
      <div v-if="$slots.default" class="alert-text"><slot /></div>
    </div>
    <button v-if="closable" type="button" class="alert-x" @click="closed = true"><X /></button>
  </div>
</template>

<style scoped>
.alert {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 13px;
  border-radius: var(--radius);
  box-shadow: inset 0 0 0 1px var(--ring-c);
  background: var(--soft);
  color: var(--text-2);
}
.alert-ic {
  width: 17px;
  height: 17px;
  flex: none;
  margin-top: 1px;
  color: var(--c);
}
.alert-body {
  flex: 1;
  min-width: 0;
}
.alert-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text);
}
.alert-text {
  font-size: 13px;
  margin-top: 2px;
}
.alert-x {
  border: none;
  background: none;
  color: var(--text-3);
  cursor: pointer;
  display: grid;
  place-items: center;
}
.alert-x:hover {
  color: var(--text-2);
}
.alert-x :deep(svg) {
  width: 15px;
  height: 15px;
}
.t-info {
  --c: var(--accent);
  --soft: var(--accent-soft);
  --ring-c: var(--accent-ring);
}
.t-ok {
  --c: var(--ok);
  --soft: var(--ok-soft);
  --ring-c: var(--ok-ring);
}
.t-warn {
  --c: var(--warn);
  --soft: var(--warn-soft);
  --ring-c: var(--warn-ring);
}
.t-danger {
  --c: var(--danger);
  --soft: var(--danger-soft);
  --ring-c: var(--danger-ring);
}
</style>
