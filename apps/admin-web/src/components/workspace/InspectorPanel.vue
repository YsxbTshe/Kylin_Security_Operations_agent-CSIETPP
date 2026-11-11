<script setup lang="ts">
/**
 * 详情侧栏：中心区任何可点对象的落点。
 *
 * 一次只讲一件事——选中的事件、工具调用或子 Agent。没有选中时列出本次运行的
 * 工具调用清单（运维最常问的「它到底动了什么」）。
 */
import { computed } from 'vue'
import { X } from 'lucide-vue-next'
import Tag from '../../ui/Tag.vue'
import Empty from '../../ui/Empty.vue'
import AgentBadgeStack from './AgentBadgeStack.vue'
import ContextInjectionPanel from './ContextInjectionPanel.vue'
import type { RuntimeToolInvocationItem } from '../../api/agent-runtime'
import type { InspectorTarget } from './inspector'
import { eventMetrics, eventTitle, eventTone, isPolicyHit, policyHitLabel } from '../../utils/run-events'
import { formatDurationMs, formatInstant } from '../../utils/format'
import { riskCodeTone, toolInvocationStatusLabel, toolInvocationStatusTone } from '../../utils/labels'

const props = defineProps<{
  target: InspectorTarget
  tools: RuntimeToolInvocationItem[]
  contextFrameId: string
}>()

defineEmits<{
  (e: 'close'): void
  (e: 'pick-tool', tool: RuntimeToolInvocationItem): void
}>()

const heading = computed(() => {
  if (!props.target) return '本次运行的工具调用'
  if (props.target.kind === 'event') return eventTitle(props.target.event)
  if (props.target.kind === 'tool') return props.target.tool.toolId
  return props.target.badge.identity?.name || '子 Agent'
})

/** payload 里的键值对，按字母序（后端写的是 JCS 规范化 JSON，本来就有序）。 */
const payloadRows = computed(() => {
  if (props.target?.kind !== 'event') return []
  return Object.entries(props.target.event.payload).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : JSON.stringify(value, null, 2),
    long: typeof value !== 'string' || value.length > 48,
  }))
})
</script>

<template>
  <aside class="inspector">
    <header class="ins-hd">
      <h3>{{ heading }}</h3>
      <button v-if="target" type="button" class="ins-x" aria-label="返回工具调用列表" @click="$emit('close')">
        <X :size="15" />
      </button>
    </header>

    <div class="ins-body">
      <!-- 事件详情 -->
      <template v-if="target?.kind === 'event'">
        <div class="chips">
          <Tag :tone="eventTone(target.event.eventType)">{{ target.event.eventType }}</Tag>
          <Tag tone="neutral">#{{ target.event.sequence }}</Tag>
          <Tag tone="neutral">{{ target.event.actorType }}</Tag>
        </div>
        <p class="stamp num">{{ formatInstant(target.event.createdAt) }}</p>
        <div v-if="eventMetrics(target.event).length" class="metrics">
          <span v-for="metric in eventMetrics(target.event)" :key="metric.label" class="metric">
            <em>{{ metric.label }}</em><b class="num">{{ metric.value }}</b>
          </span>
        </div>
        <dl class="kv">
          <template v-for="row in payloadRows" :key="row.key">
            <dt>{{ row.key }}</dt>
            <dd :class="{ block: row.long }">{{ row.value }}</dd>
          </template>
        </dl>
        <p class="hash num">{{ target.event.eventHash }}</p>
        <ContextInjectionPanel :context-frame-id="contextFrameId" />
      </template>

      <!-- 工具调用详情 -->
      <template v-else-if="target?.kind === 'tool'">
        <div class="chips">
          <Tag :tone="toolInvocationStatusTone(target.tool.status)">
            {{ toolInvocationStatusLabel(target.tool.status) }}
          </Tag>
          <Tag :tone="riskCodeTone(target.tool.riskCode)">{{ target.tool.riskCode }}</Tag>
          <Tag tone="neutral">{{ target.tool.readOnly ? '只读' : '写操作' }}</Tag>
        </div>
        <dl class="kv">
          <dt>版本</dt><dd>{{ target.tool.toolVersion }}</dd>
          <template v-if="target.tool.targetNodeId">
            <dt>目标节点</dt><dd>{{ target.tool.targetNodeId }}</dd>
          </template>
          <dt>排队</dt><dd class="num">{{ formatInstant(target.tool.queuedAt) }}</dd>
          <dt>开始</dt><dd class="num">{{ formatInstant(target.tool.startedAt) }}</dd>
          <dt>完成</dt><dd class="num">{{ formatInstant(target.tool.completedAt) }}</dd>
          <dt>耗时</dt><dd class="num">{{ formatDurationMs(target.tool.durationMs) }}</dd>
          <template v-if="target.tool.errorCode">
            <dt>错误码</dt>
            <dd :class="{ bad: true }">
              {{ target.tool.errorCode }}
              <small v-if="isPolicyHit(target.tool.errorCode)">{{ policyHitLabel(target.tool.errorCode) }}</small>
            </dd>
          </template>
        </dl>
      </template>

      <!-- 子 Agent 工牌 -->
      <template v-else-if="target?.kind === 'agent'">
        <AgentBadgeStack :badges="[target.badge]" expanded />
      </template>

      <!-- 空态：工具调用清单 -->
      <template v-else>
        <Empty v-if="!tools.length" description="该任务还没有工具调用" />
        <ul v-else class="tool-list">
          <li v-for="tool in tools" :key="tool.id">
            <button type="button" class="tool-row" @click="$emit('pick-tool', tool)">
              <span class="tool-dot" :class="`st-${tool.status.toLowerCase()}`" />
              <code>{{ tool.toolId }}</code>
              <span class="tool-meta num">{{ tool.riskCode }}</span>
              <span class="tool-meta num">{{ formatDurationMs(tool.durationMs) }}</span>
            </button>
          </li>
        </ul>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.inspector {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  border-left: 1px solid var(--border);
  background: var(--ws-panel);
}
.ins-hd {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  height: 42px;
  padding: 0 10px 0 14px;
  border-bottom: 1px solid var(--border);
}
.ins-hd h3 {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ins-x {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text-3);
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease), color var(--ws-fast) var(--ws-ease);
}
.ins-x:hover {
  background: var(--surface-2);
  color: var(--text);
}
.ins-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px 20px;
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.stamp {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.metric {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
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
}
.kv {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 5px 12px;
  margin: 0;
  align-items: baseline;
}
.kv dt {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}
.kv dd {
  margin: 0;
  font-size: 12px;
  color: var(--text-2);
  min-width: 0;
  overflow-wrap: anywhere;
}
.kv dd.block {
  grid-column: 1 / -1;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  padding: 7px 9px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  border: 1px solid var(--border);
}
.kv dd.bad {
  color: var(--ws-danger);
}
.kv dd.bad small {
  display: block;
  color: var(--text-3);
  font-size: 11px;
  margin-top: 2px;
}
.hash {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-3);
  overflow-wrap: anywhere;
}

.tool-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tool-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  text-align: left;
  cursor: pointer;
  transition: background var(--ws-fast) var(--ws-ease);
}
.tool-row:hover {
  background: var(--surface-2);
}
.tool-row code {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tool-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ws-neutral);
}
.tool-dot.st-succeeded {
  background: var(--ws-ok);
}
.tool-dot.st-failed,
.tool-dot.st-rejected {
  background: var(--ws-danger);
}
.tool-dot.st-running,
.tool-dot.st-queued {
  background: var(--ws-running);
}
.tool-meta {
  flex: none;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
}
</style>
