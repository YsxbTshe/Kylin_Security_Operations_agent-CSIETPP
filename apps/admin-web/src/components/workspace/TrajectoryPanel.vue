<script setup lang="ts">
/**
 * Trajectory —— 同一份事件序列的两种读法。
 *
 *   回合（turns）  按 MODEL_INVOCATION_STARTED 聚合成 ReAct 的一圈一圈，
 *                  每圈展示模型路由、上下文指纹、用量与耗时、以及圈内动作。
 *   原始（raw）    agent_run_event 全序列逐条铺开，含完整 payload 与哈希，
 *                  用于对账与取证。
 *
 * 诚实边界：**提示词与模型回复正文不在事件链里**。后端把提示词写在
 * context_frame 表、消息写在 agent_run_message 表，两者当前都没有只读 API。
 * 所以「输入」这一栏给的是能拿到的真实东西——模型路由、reasoning 档位、
 * 上下文摘要指纹 contextFrameId / contextDigest / tokenEstimate / 前缀缓存命中，
 * 并明确标注正文待 API 补齐，不用任何占位文本冒充提示词。
 */
import { computed, ref } from 'vue'
import { Braces, Check, Copy, LayoutList, ScrollText } from 'lucide-vue-next'
import ActivityStream from './ActivityStream.vue'
import ContextInjectionPanel from './ContextInjectionPanel.vue'
import Empty from '../../ui/Empty.vue'
import Tag from '../../ui/Tag.vue'
import {
  eventTitle,
  eventTone,
  turnLatency,
  type RunTurn,
  type WorkspaceEvent,
} from '../../utils/run-events'
import { formatDurationMs, formatInstantShort } from '../../utils/format'

const props = defineProps<{
  turns: RunTurn[]
  events: WorkspaceEvent[]
  selectedSequence?: number | null
}>()

defineEmits<{ (e: 'select', event: WorkspaceEvent): void }>()

const view = ref<'turns' | 'raw'>('turns')
const expandedRaw = ref<Set<number>>(new Set())
const copied = ref(false)

const modelTurns = computed(() => props.turns.filter((turn) => turn.kind === 'turn'))

function toggleRaw(sequence: number) {
  const next = new Set(expandedRaw.value)
  if (next.has(sequence)) next.delete(sequence)
  else next.add(sequence)
  expandedRaw.value = next
}

function pretty(event: WorkspaceEvent): string {
  return JSON.stringify(event.payload, null, 2)
}

async function copyTranscript() {
  const text = props.events
    .map((event) => `#${event.sequence} ${event.createdAt} ${event.eventType} ${event.actorType}/${event.actorId}\n${pretty(event)}`)
    .join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    window.setTimeout(() => (copied.value = false), 1600)
  } catch {
    // 剪贴板被策略禁用时静默——原文本身就在页面上，用户仍可手动选取。
  }
}
</script>

<template>
  <div class="traj">
    <header class="traj-bar">
      <div class="seg" role="tablist">
        <button
          type="button"
          role="tab"
          :aria-selected="view === 'turns'"
          :class="{ on: view === 'turns' }"
          @click="view = 'turns'"
        >
          <LayoutList :size="13" /> 回合
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="view === 'raw'"
          :class="{ on: view === 'raw' }"
          @click="view = 'raw'"
        >
          <ScrollText :size="13" /> 原始
        </button>
      </div>
      <span class="traj-count num">{{ events.length }} 条事件 · {{ modelTurns.length }} 回合</span>
      <button v-if="view === 'raw'" type="button" class="ghost" @click="copyTranscript">
        <component :is="copied ? Check : Copy" :size="13" />
        {{ copied ? '已复制' : '复制全文' }}
      </button>
    </header>

    <Empty v-if="!events.length" description="该任务还没有事件" />

    <!-- 回合视图 -->
    <div v-else-if="view === 'turns'" class="turns">
      <article v-for="turn in turns" :key="turn.key" class="turn" :class="`s-${turn.status}`">
        <header class="turn-hd">
          <span class="turn-no num">{{ turn.index || '—' }}</span>
          <b>{{ turn.label }}</b>
          <code v-if="turn.inputs.model" class="turn-model">{{ turn.inputs.model }}</code>
          <span class="turn-time num">{{ formatInstantShort(turn.startedAt) }}</span>
          <span v-if="turnLatency(turn) != null" class="turn-dur num">{{ formatDurationMs(turnLatency(turn)) }}</span>
        </header>

        <div class="turn-io">
          <section class="io">
            <h4>输入</h4>
            <dl v-if="turn.inputs.model || turn.inputs.contextFrameId">
              <template v-if="turn.inputs.provider">
                <dt>提供方</dt><dd>{{ turn.inputs.provider }}</dd>
              </template>
              <template v-if="turn.inputs.reasoningLevel">
                <dt>推理档位</dt><dd>{{ turn.inputs.reasoningLevel }}</dd>
              </template>
              <template v-if="turn.inputs.routeReason">
                <dt>路由原因</dt><dd>{{ turn.inputs.routeReason }}</dd>
              </template>
              <template v-if="turn.inputs.tokenEstimate != null">
                <dt>上下文估算</dt><dd class="num">{{ turn.inputs.tokenEstimate }} tok</dd>
              </template>
              <template v-if="turn.inputs.prefixCacheHit != null">
                <dt>前缀缓存</dt><dd>{{ turn.inputs.prefixCacheHit ? '命中' : '未命中' }}</dd>
              </template>
              <template v-if="turn.inputs.contextFrameId">
                <dt>上下文帧</dt><dd class="mono">{{ turn.inputs.contextFrameId }}</dd>
              </template>
            </dl>
            <p v-else class="io-none">本回合无模型调用记录</p>

            <!-- Context injection 面板挂载点（后端 context_frame API 待补，见组件注释） -->
            <ContextInjectionPanel :context-frame-id="turn.inputs.contextFrameId" />
          </section>

          <section class="io">
            <h4>输出</h4>
            <div v-if="turn.metrics.length" class="metrics">
              <span v-for="metric in turn.metrics" :key="metric.label" class="metric">
                <em>{{ metric.label }}</em><b class="num">{{ metric.value }}</b>
              </span>
            </div>
            <p v-if="turn.text" class="io-text">{{ turn.text }}</p>
            <p v-else-if="!turn.metrics.length" class="io-none">本回合无模型用量</p>
            <p v-else class="io-note">
              模型回复正文不落在事件链上（存于 agent_run_message / context_frame），需后端补只读 API 后展示。
            </p>
          </section>
        </div>

        <div class="turn-acts">
          <ActivityStream
            :groups="turn.groups"
            :selected-sequence="selectedSequence"
            @select="$emit('select', $event)"
          />
        </div>
      </article>
    </div>

    <!-- 原始 transcript -->
    <ol v-else class="raw">
      <li
        v-for="event in events"
        :key="event.eventId || event.sequence"
        class="raw-row"
        :class="[`t-${eventTone(event.eventType)}`, { on: event.sequence === selectedSequence }]"
      >
        <button type="button" class="raw-head" @click="toggleRaw(event.sequence); $emit('select', event)">
          <span class="raw-seq num">#{{ event.sequence }}</span>
          <time class="num">{{ formatInstantShort(event.createdAt) }}</time>
          <code class="raw-type">{{ event.eventType }}</code>
          <span class="raw-title">{{ eventTitle(event) }}</span>
          <Tag tone="neutral">{{ event.actorType }}</Tag>
          <Braces :size="12" class="raw-braces" />
        </button>
        <div class="ws-collapse" :class="{ 'is-open': expandedRaw.has(event.sequence) }">
          <div>
            <pre class="raw-json">{{ pretty(event) }}</pre>
            <p class="raw-hash num">hash {{ event.eventHash }}<span v-if="event.actorId"> · actor {{ event.actorId }}</span></p>
          </div>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.traj {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}
.traj-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--radius);
  background: var(--ws-inset);
  border: 1px solid var(--border);
}
.seg button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-3);
  font-size: 12.5px;
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease), color var(--ws-fast) var(--ws-ease);
}
.seg button:hover {
  color: var(--text-2);
}
.seg button.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow);
}
.traj-count {
  font-size: 11.5px;
  color: var(--text-3);
  margin-right: auto;
}
.ghost {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  transition: border-color var(--ws-fast) var(--ws-ease), color var(--ws-fast) var(--ws-ease);
}
.ghost:hover {
  border-color: var(--border-2);
  color: var(--text);
}

/* ── 回合视图 ── */
.turns {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.turn {
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  overflow: hidden;
}
.turn.s-failed {
  border-color: color-mix(in oklch, var(--ws-danger) 40%, var(--border));
}
.turn.s-running {
  box-shadow: var(--ws-glow);
}
.turn-hd {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--ws-inset);
}
.turn-no {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  flex: none;
  border-radius: var(--radius-sm);
  background: var(--surface);
  border: 1px solid var(--border);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-2);
}
.turn.s-running .turn-no {
  border-color: var(--accent);
  color: var(--accent);
}
.turn-hd b {
  font-size: 13px;
}
.turn-model {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--surface);
  border: 1px solid var(--border);
}
.turn-time {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-3);
}
.turn-dur {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-2);
}
.turn-io {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  background: var(--border);
}
.io {
  padding: 11px 14px;
  background: var(--surface);
  min-width: 0;
}
.io h4 {
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-3);
  margin-bottom: 8px;
  font-family: var(--font-mono);
}
.io dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 12px;
  margin: 0;
  align-items: baseline;
}
.io dt {
  font-size: 11.5px;
  color: var(--text-3);
  white-space: nowrap;
}
.io dd {
  margin: 0;
  font-size: 12px;
  color: var(--text-2);
  min-width: 0;
  overflow-wrap: anywhere;
}
.io dd.mono {
  font-family: var(--font-mono);
  font-size: 11px;
}
.io-none,
.io-note {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.6;
}
.io-text {
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text);
  white-space: pre-wrap;
}
.metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.metric {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  background: var(--ws-inset);
  border: 1px solid var(--border);
}
.metric em {
  font-style: normal;
  font-size: 10.5px;
  color: var(--text-3);
}
.metric b {
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 500;
  color: var(--text);
}
.turn-acts {
  padding: 10px 14px 12px;
  border-top: 1px solid var(--border);
}

/* ── 原始 transcript ── */
.raw {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--surface);
}
.raw-row + .raw-row {
  border-top: 1px solid color-mix(in oklch, var(--border) 60%, transparent);
}
.raw-row.on {
  background: var(--accent-soft);
}
.raw-head {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 6px 12px;
  border: none;
  background: none;
  text-align: left;
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease);
}
.raw-head:hover {
  background: var(--ws-inset);
}
.raw-seq {
  flex: none;
  width: 42px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.raw-head time {
  flex: none;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.raw-type {
  flex: none;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.03em;
  color: var(--text-2);
}
.raw-row.t-ok .raw-type {
  color: var(--ws-ok);
}
.raw-row.t-warn .raw-type {
  color: var(--ws-warn);
}
.raw-row.t-danger .raw-type {
  color: var(--ws-danger);
}
.raw-title {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.raw-braces {
  flex: none;
  color: var(--text-3);
  opacity: 0;
  transition: opacity var(--ws-fast) var(--ws-ease);
}
.raw-head:hover .raw-braces {
  opacity: 1;
}
.raw-json {
  margin: 0;
  padding: 8px 12px 4px 54px;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-2);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.raw-hash {
  padding: 0 12px 10px 54px;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .turn-io {
    grid-template-columns: 1fr;
  }
}
</style>
