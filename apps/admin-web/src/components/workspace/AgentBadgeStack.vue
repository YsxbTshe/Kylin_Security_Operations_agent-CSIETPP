<script setup lang="ts">
/**
 * 子 Agent 工牌墙。
 *
 * 每个被分派的子 Agent 一张工牌：身份 / 能力封套 / 风险上限 / 当前状态。
 * 默认堆叠（只露出最上面一张 + 后面几张的边），点击展开成列表。
 *
 * 数据来源与诚实边界：
 *   childRuns   /agent-runtime/runs/{id}/timeline → 目标、状态、结果、起止时间（真实）
 *   handoffs    同上 → handoff 状态与 childAgentInstanceId（真实）
 *   registry    /registry/agents → 名称、角色、能力封套 allowedTools、风险上限 maxRisk
 *
 * ⚠ SUB_AGENT_RESULT 事件里**没有 agentKey**（payload 只有 handoffId /
 * childRunId / childInstanceId / handoffStatus / artifactRefs），所以除非能从
 * 别处解析出 agentKey，注册表身份就是取不到的——此时工牌只显示 run 上的真实
 * 字段，不填任何占位身份。
 */
import { computed, ref } from 'vue'
import { ChevronDown, IdCard, Users } from 'lucide-vue-next'
import Tag from '../../ui/Tag.vue'
import type { AgentBadgeModel } from './agent-badge'
import { formatDurationMs, formatInstantShort } from '../../utils/format'
import { handoffStatusLabel, riskCodeTone, runOutcomeLabel, runStatusLabel, runStatusTone } from '../../utils/labels'

const props = defineProps<{
  badges: AgentBadgeModel[]
  /** 默认展开（详情侧栏里用），对话流里默认堆叠。 */
  expanded?: boolean
}>()

defineEmits<{ (e: 'select', badge: AgentBadgeModel): void }>()

const open = ref(props.expanded ?? false)
/** 堆叠时最多露 3 层边，再多只用计数表示，否则边缘线糊成一片。 */
const MAX_PEEK = 3

const peekCount = computed(() => Math.min(props.badges.length, MAX_PEEK))
const hiddenCount = computed(() => Math.max(0, props.badges.length - MAX_PEEK))

function elapsed(badge: AgentBadgeModel): string {
  const start = Date.parse(badge.createdAt)
  const end = badge.completedAt ? Date.parse(badge.completedAt) : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end)) return ''
  return formatDurationMs(Math.max(0, end - start))
}

/** 头像字母：优先注册表名称首字，否则用序号——不编造姓名。 */
function initial(badge: AgentBadgeModel, index: number): string {
  return badge.identity?.name?.trim().charAt(0) || String(index + 1)
}
</script>

<template>
  <section v-if="badges.length" class="wall">
    <button type="button" class="wall-head" :aria-expanded="open" @click="open = !open">
      <Users :size="13" />
      <span>子 Agent</span>
      <b class="num">{{ badges.length }}</b>
      <ChevronDown :size="13" class="wall-chev" :class="{ up: open }" />
    </button>

    <div class="stack" :class="{ open }">
      <article
        v-for="(badge, index) in badges"
        v-show="open || index < peekCount"
        :key="badge.id"
        class="badge"
        :class="[`s-${badge.status.toLowerCase()}`, { lead: index === 0 }]"
        :style="{ '--depth': Math.min(index, MAX_PEEK), zIndex: badges.length - index }"
        @click="$emit('select', badge)"
      >
        <header class="badge-hd">
          <span class="badge-face" :aria-hidden="true">
            <IdCard v-if="!badge.identity" :size="15" />
            <template v-else>{{ initial(badge, index) }}</template>
          </span>
          <span class="badge-id">
            <b>{{ badge.identity?.name || '未登记身份' }}</b>
            <small>{{ badge.identity?.role || badge.agentInstanceId || badge.id }}</small>
          </span>
          <span class="badge-state">
            <i class="pulse" />
            <Tag :tone="runStatusTone(badge.status)">{{ runStatusLabel(badge.status) }}</Tag>
          </span>
        </header>

        <div class="ws-collapse" :class="{ 'is-open': open }">
          <div>
            <div class="badge-body">
              <p class="badge-objective">{{ badge.objective }}</p>

              <dl class="badge-facts">
                <template v-if="badge.identity">
                  <dt>能力封套</dt>
                  <dd class="caps">
                    <code v-for="tool in badge.identity.allowedTools" :key="tool">{{ tool }}</code>
                    <span v-if="!badge.identity.allowedTools.length" class="none">未配置</span>
                  </dd>
                  <dt>风险上限</dt>
                  <dd><Tag :tone="riskCodeTone(badge.identity.maxRisk)">{{ badge.identity.maxRisk }}</Tag></dd>
                </template>
                <template v-if="badge.handoffStatus">
                  <dt>交接</dt>
                  <dd>{{ handoffStatusLabel(badge.handoffStatus) }}</dd>
                </template>
                <template v-if="badge.outcome">
                  <dt>结果</dt>
                  <dd>{{ runOutcomeLabel(badge.outcome) }}</dd>
                </template>
                <dt>用时</dt>
                <dd class="num">{{ elapsed(badge) }} · 起 {{ formatInstantShort(badge.createdAt) }}</dd>
                <template v-if="badge.artifactCount">
                  <dt>产物</dt>
                  <dd class="num">{{ badge.artifactCount }} 件</dd>
                </template>
              </dl>
            </div>
          </div>
        </div>
      </article>

      <button v-if="!open && hiddenCount" type="button" class="more num" @click="open = true">
        +{{ hiddenCount }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.wall {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.wall-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: flex-start;
  padding: 2px 2px 2px 0;
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 11.5px;
  cursor: pointer;
  transition: color var(--ws-fast) var(--ws-ease);
}
.wall-head:hover {
  color: var(--text-2);
}
.wall-head b {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-2);
}
.wall-chev {
  transition: transform var(--ws-base) var(--ws-ease);
}
.wall-chev.up {
  transform: rotate(180deg);
}

.stack {
  position: relative;
  display: flex;
  flex-direction: column;
}
/* 工牌卡片：左侧一道状态色的窄边，像卡套上的挂绳配色。 */
.badge {
  position: relative;
  border: 1px solid var(--border);
  border-left: 2px solid var(--ws-neutral);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow);
  cursor: pointer;
  transition:
    margin var(--ws-base) var(--ws-ease),
    transform var(--ws-base) var(--ws-ease),
    opacity var(--ws-base) var(--ws-ease),
    border-color var(--ws-fast) var(--ws-ease),
    box-shadow var(--ws-fast) var(--ws-ease);
}
.badge.s-running,
.badge.s-waiting {
  border-left-color: var(--ws-running);
}
.badge.s-completed {
  border-left-color: var(--ws-ok);
}
.badge.s-failed {
  border-left-color: var(--ws-danger);
}
.badge.s-waiting {
  border-left-color: var(--ws-warn);
}
/* 堆叠态：后面的卡片被拉到上一张底下，只露 9px 的边。 */
.stack:not(.open) .badge + .badge {
  margin-top: -47px;
  transform: scale(calc(1 - var(--depth) * 0.014));
  opacity: calc(1 - var(--depth) * 0.22);
  pointer-events: none;
}
.stack.open .badge + .badge {
  margin-top: 8px;
}
.badge:hover {
  border-color: var(--border-2);
  box-shadow: var(--shadow-2);
}
.stack.open .badge.s-running {
  box-shadow: var(--ws-glow);
}

.badge-hd {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 12px;
}
.badge-face {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: var(--radius);
  background: var(--ws-inset);
  border: 1px solid var(--border);
  color: var(--text-2);
  font-size: 13px;
  font-weight: 600;
}
.badge-id {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}
.badge-id b {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge-id small {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.pulse {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ws-neutral);
}
.badge.s-running .pulse,
.badge.s-waiting .pulse {
  background: var(--ws-running);
  animation: ws-breathe 1.8s ease-in-out infinite;
}

.badge-body {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 0 12px 12px;
  border-top: 1px solid var(--border);
  margin-top: 2px;
  padding-top: 10px;
}
.badge-objective {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--text-2);
}
.badge-facts {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 12px;
  align-items: baseline;
  margin: 0;
}
.badge-facts dt {
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}
.badge-facts dd {
  margin: 0;
  font-size: 12px;
  color: var(--text-2);
  min-width: 0;
}
.caps {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.caps code {
  font-family: var(--font-mono);
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--ws-inset);
  border: 1px solid var(--border);
  color: var(--text-2);
}
.caps .none {
  font-size: 11.5px;
  color: var(--text-3);
}

.more {
  position: absolute;
  right: 10px;
  bottom: -9px;
  z-index: 20;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-3);
  font-family: var(--font-mono);
  font-size: 10.5px;
  cursor: pointer;
  box-shadow: var(--shadow);
  transition: color var(--ws-fast) var(--ws-ease), border-color var(--ws-fast) var(--ws-ease);
}
.more:hover {
  color: var(--text);
  border-color: var(--border-2);
}
</style>
