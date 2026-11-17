<script setup lang="ts">
/**
 * 运行节拍条 —— 工作台的签名元件。
 *
 * 一条事件 = 一根刻度，按回合分组、按状态着色，整条 run 压缩成一屏可见的
 * 一行。工程师扫一眼就知道：跑了几个回合、哪一段密（工具调用多）、有没有红。
 * 刻度可点，点了就把下方视图定位到那条事件——它同时是概览和游标。
 *
 * 三个 Tab（对话 / 轨迹 / 时序）共用同一条节拍条，切 Tab 时它不动，
 * 于是它也充当「你正在看同一次运行」的锚点。
 */
import { computed } from 'vue'
import { eventTone, type RunTurn, type WorkspaceEvent } from '../../utils/run-events'
import { formatDurationMs } from '../../utils/format'
import { runEventTypeLabel } from '../../utils/labels'

const props = defineProps<{
  turns: RunTurn[]
  selectedSequence?: number | null
}>()

defineEmits<{ (e: 'select', event: WorkspaceEvent): void }>()

const totalEvents = computed(() => props.turns.reduce((sum, turn) => sum + turn.events.length, 0))

function tickTitle(event: WorkspaceEvent): string {
  const time = event.createdAt ? event.createdAt.slice(11, 19) : ''
  return `#${event.sequence} ${runEventTypeLabel(event.eventType) || event.eventType}${time ? ` · ${time}` : ''}`
}

function turnTitle(turn: RunTurn): string {
  const duration = turn.modelDurationMs != null ? ` · 模型 ${formatDurationMs(turn.modelDurationMs)}` : ''
  return `${turn.label} · ${turn.events.length} 条事件${duration}`
}
</script>

<template>
  <div v-if="totalEvents" class="tape" role="group" aria-label="运行节拍">
    <div
      v-for="turn in turns"
      :key="turn.key"
      class="tape-turn"
      :class="`s-${turn.status}`"
      :title="turnTitle(turn)"
    >
      <div class="tape-ticks">
        <button
          v-for="event in turn.events"
          :key="event.eventId || event.sequence"
          type="button"
          class="tick"
          :class="[`t-${eventTone(event.eventType)}`, { on: event.sequence === selectedSequence }]"
          :title="tickTitle(event)"
          :aria-label="tickTitle(event)"
          @click="$emit('select', event)"
        />
      </div>
      <span class="tape-cap num">{{ turn.index || '·' }}</span>
    </div>
  </div>
</template>

<style scoped>
.tape {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  min-width: 0;
}
/* 组宽由刻度数决定（内容驱动），刻度多的回合自然更宽——宽度本身就是信息。
   空间不够时刻度一起收窄到 2px，不用滚动条。 */
.tape-turn {
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 0 1 auto;
  min-width: 0;
}
.tape-ticks {
  display: flex;
  gap: 1.5px;
  height: 16px;
  padding-bottom: 3px;
  border-bottom: 1px solid var(--ws-rail);
  transition: border-color var(--ws-fast) var(--ws-ease);
}
.tape-turn:hover .tape-ticks {
  border-bottom-color: var(--accent);
}
.tape-turn.s-failed .tape-ticks {
  border-bottom-color: color-mix(in oklch, var(--ws-danger) 55%, transparent);
}
.tick {
  flex: 0 1 6px;
  width: 6px;
  min-width: 2px;
  padding: 0;
  border: none;
  border-radius: 1px;
  cursor: pointer;
  background: var(--ws-tick);
  transition: background var(--ws-fast) var(--ws-ease), transform var(--ws-fast) var(--ws-ease);
}
.tick:hover {
  transform: scaleY(1.15);
  background: var(--text-2);
}
.tick.t-ok {
  background: color-mix(in oklch, var(--ws-ok) 72%, transparent);
}
.tick.t-warn {
  background: var(--ws-warn);
}
.tick.t-danger {
  background: var(--ws-danger);
}
.tick.on {
  background: var(--ws-running);
  box-shadow: var(--ws-glow);
}
/* 进行中的回合：最后一根刻度呼吸，标记「机器现在在这里」。 */
.tape-turn.s-running .tick:last-child {
  background: var(--ws-running);
  animation: ws-breathe 1.8s ease-in-out infinite;
}
.tape-cap {
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1;
  color: var(--text-3);
  text-align: center;
  letter-spacing: 0.02em;
}
.tape-turn.s-running .tape-cap {
  color: var(--accent);
}
</style>
