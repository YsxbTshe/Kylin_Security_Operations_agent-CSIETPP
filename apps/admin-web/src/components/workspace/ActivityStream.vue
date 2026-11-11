<script setup lang="ts">
/**
 * 活动流：把一次 run 的事件按「频道」归并后呈现，而不是流水账。
 *
 * 连续同频道的事件折叠成一组（「工具调用 ×3」「策略判定 ×2」），一行一组、
 * 可展开看逐条。频道用图标和左侧轨道区分，颜色只在出问题时才出现。
 */
import { ref } from 'vue'
import {
  Activity,
  ChevronRight,
  Cpu,
  Layers,
  ListChecks,
  Scale,
  ShieldCheck,
  Users,
  Wrench,
} from 'lucide-vue-next'
import type { Component } from 'vue'
import {
  CHANNEL_LABELS,
  eventDetail,
  eventMetrics,
  eventTitle,
  eventTone,
  type ActivityGroup,
  type EventChannel,
  type WorkspaceEvent,
} from '../../utils/run-events'
import { formatDurationMs } from '../../utils/format'

defineProps<{
  groups: ActivityGroup[]
  selectedSequence?: number | null
}>()

defineEmits<{ (e: 'select', event: WorkspaceEvent): void }>()

const CHANNEL_ICONS: Record<EventChannel, Component> = {
  lifecycle: Activity,
  context: Layers,
  model: Cpu,
  tool: Wrench,
  approval: ShieldCheck,
  subagent: Users,
  plan: ListChecks,
  policy: Scale,
}

/** 默认全部收起：组标题本身已带首要标识，展开只在需要逐条核对时才用。 */
const opened = ref<Set<string>>(new Set())

function isOpen(group: ActivityGroup): boolean {
  return opened.value.has(group.key)
}

function toggle(group: ActivityGroup) {
  const next = new Set(opened.value)
  if (next.has(group.key)) next.delete(group.key)
  else next.add(group.key)
  opened.value = next
}

/** 组标题：同类型事件写成「标题 ×N」，混合频道写成「频道 · N 条」。 */
function headline(group: ActivityGroup): string {
  if (group.events.length === 1) return eventTitle(group.events[0])
  const types = new Set(group.events.map((event) => event.eventType))
  if (types.size === 1) return `${eventTitle(group.events[0])} ×${group.events.length}`
  return `${CHANNEL_LABELS[group.channel]} · ${group.events.length} 条`
}

/** 组副标题：优先展示第一条能提取到的标识（工具 id、策略码等）。 */
function subline(group: ActivityGroup): string {
  for (const event of group.events) {
    const detail = eventDetail(event)
    if (detail) return detail
  }
  return ''
}

function stamp(event: WorkspaceEvent): string {
  return event.createdAt ? event.createdAt.slice(11, 19) : ''
}

/** 组跨度：首末事件的时间差，只有真的跨了时间才显示。 */
function groupSpan(group: ActivityGroup): string {
  if (group.events.length < 2) return ''
  const first = Date.parse(group.events[0].createdAt)
  const last = Date.parse(group.events[group.events.length - 1].createdAt)
  if (Number.isNaN(first) || Number.isNaN(last) || last - first < 1) return ''
  return formatDurationMs(last - first)
}
</script>

<template>
  <ol class="stream">
    <li v-for="group in groups" :key="group.key" class="grp" :class="[`t-${group.tone}`, { open: isOpen(group) }]">
      <button type="button" class="grp-head" @click="toggle(group)">
        <span class="grp-ic"><component :is="CHANNEL_ICONS[group.channel]" :size="13" /></span>
        <span class="grp-title">{{ headline(group) }}</span>
        <code v-if="subline(group)" class="grp-sub">{{ subline(group) }}</code>
        <span v-if="groupSpan(group)" class="grp-span num">{{ groupSpan(group) }}</span>
        <ChevronRight :size="13" class="grp-chev" />
      </button>

      <div class="ws-collapse" :class="{ 'is-open': isOpen(group) }">
        <div>
          <ul class="grp-events">
            <li
              v-for="event in group.events"
              :key="event.eventId || event.sequence"
              class="ev"
              :class="[`t-${eventTone(event.eventType)}`, { on: event.sequence === selectedSequence }]"
            >
              <button type="button" class="ev-btn" @click="$emit('select', event)">
                <span class="ev-dot" />
                <time class="num">{{ stamp(event) }}</time>
                <span class="ev-title">{{ eventTitle(event) }}</span>
                <code v-if="eventDetail(event)" class="ev-detail">{{ eventDetail(event) }}</code>
                <span class="ev-metrics num">
                  <em v-for="metric in eventMetrics(event)" :key="metric.label">{{ metric.value }}</em>
                </span>
              </button>
            </li>
          </ul>
        </div>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.stream {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}
/* 左侧连续轨道：把散落的组串成一条执行线。 */
.grp {
  position: relative;
  padding-left: 15px;
}
.grp::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--ws-rail);
}
.grp:first-child::before {
  top: 13px;
}
.grp:last-child::before {
  bottom: calc(100% - 13px);
}
.grp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 4px 8px 4px 0;
  border: none;
  background: none;
  text-align: left;
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background var(--ws-fast) var(--ws-ease);
}
.grp-head:hover {
  background: var(--ws-inset);
}
/* 图标压在轨道上并用背景色打断轨道线；top 与 ::before 的 13px 端点对齐
   （head 上内边距 4px + (行高 19px − 图标 11px) / 2 = 8px，中心恰好 13.5px）。 */
.grp-ic {
  position: absolute;
  left: 0;
  top: 8px;
  display: grid;
  place-items: center;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  color: var(--text-3);
  background: var(--surface);
  box-shadow: 0 0 0 3px var(--surface);
  transition: color var(--ws-fast) var(--ws-ease);
}
.grp-ic :deep(svg) {
  width: 11px;
  height: 11px;
}
.grp.t-ok .grp-ic {
  color: var(--ws-ok);
}
.grp.t-warn .grp-ic {
  color: var(--ws-warn);
}
.grp.t-danger .grp-ic {
  color: var(--ws-danger);
}
.grp-title {
  font-size: 12.5px;
  color: var(--text-2);
  white-space: nowrap;
}
.grp.t-danger .grp-title {
  color: var(--ws-danger);
}
/* 标识与耗时紧跟标题，不用 margin-auto 甩到行尾——宽屏下右对齐的数字
   会离它描述的东西太远，读起来要来回扫。 */
.grp-sub {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 0 1 auto;
}
.grp-span {
  flex: none;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.grp-chev {
  flex: none;
  margin-left: auto;
  color: var(--text-3);
  opacity: 0;
  transition: opacity var(--ws-fast) var(--ws-ease), transform var(--ws-fast) var(--ws-ease);
}
.grp-head:hover .grp-chev {
  opacity: 1;
}
.grp.open .grp-chev {
  opacity: 1;
  transform: rotate(90deg);
}
.grp-events {
  list-style: none;
  margin: 0;
  padding: 2px 0 6px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.ev-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 3px 8px 3px 4px;
  border: none;
  background: none;
  text-align: left;
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background var(--ws-fast) var(--ws-ease);
}
.ev-btn:hover {
  background: var(--ws-inset);
}
.ev.on .ev-btn {
  background: var(--accent-soft);
}
.ev-dot {
  flex: none;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--ws-tick);
}
.ev.t-ok .ev-dot {
  background: var(--ws-ok);
}
.ev.t-warn .ev-dot {
  background: var(--ws-warn);
}
.ev.t-danger .ev-dot {
  background: var(--ws-danger);
}
.ev time {
  flex: none;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
}
.ev-title {
  flex: none;
  font-size: 12px;
  color: var(--text-2);
}
.ev.t-danger .ev-title {
  color: var(--ws-danger);
}
.ev-detail {
  flex: 0 1 auto;
  min-width: 0;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ev-metrics {
  flex: none;
  display: flex;
  gap: 6px;
}
.ev-metrics em {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
}
</style>
