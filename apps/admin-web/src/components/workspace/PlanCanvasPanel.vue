<script setup lang="ts">
/**
 * Plan canvas —— PLAN 模式产出的**结构化计划**。
 *
 * 数据来自 `GET /api/v2/agent-runs/{runId}/plan/canvas`：后端把 plan_version 的
 * Markdown 正文解析成「阶段 → 步骤」，再与该 run 真实的 tool_invocation 对账。
 *
 * ── 两条不能含糊的语义 ───────────────────────────────────────────────
 * 1. **状态只来自执行记录**。步骤上的绿/黄/红不是计划文档里写的，是它对上的那条
 *    工具调用的真实状态；对不上就是灰色 PENDING。所以画面上任何一个「已完成」
 *    都能顺着 toolInvocationId 追到一条真实执行记录——点它就能在右侧看到。
 * 2. **这不是执行时序图**。TimingGraph 画的是已发生的因果与耗时；这里画的是
 *    「原本打算怎么做」以及它兑现到哪一步。两者刻意分开，不要合并。
 *
 * ── 视觉约定（沿用 workspace-tokens.css）─────────────────────────────
 * 色相只表达状态，不表达类别：阶段/步骤/依赖一律中性，只有真的在跑、成功、
 * 失败时才出现颜色。RUNNING 用全局唯一的呼吸环 ws-breathe。
 * 手写 SVG 画依赖折线，与 TimingGraph 同一套画法，不引图库。
 */
import { computed, ref, watch } from 'vue'
import { fetchPlanCanvas, type PlanCanvas, type PlanStep, type PlanNodeStatus } from '../../api/agent-runtime'

const props = defineProps<{
  /** 当前 run id。 */
  runId: string
  /** 已从 PLAN_VERSION_* 事件解析到的最新计划版本 id；变化时重新拉取。 */
  planVersionId: string
}>()

const emit = defineEmits<{ (e: 'inspect', toolInvocationId: string): void }>()

/* 布局常量：阶段横向排布，步骤纵向堆叠。 */
const COL_W = 232
const COL_GAP = 36
const HEAD_H = 34
const STEP_H = 46
const STEP_GAP = 8
const PAD_TOP = 8

const canvas = ref<PlanCanvas | null>(null)
const loading = ref(false)
/** 没有计划（404）与真出错要分开：前者是正常状态，不该弹错误。 */
const noPlan = ref(false)
const error = ref('')
const selectedStepId = ref<string | null>(null)

async function load() {
  if (!props.runId) {
    canvas.value = null
    return
  }
  loading.value = true
  error.value = ''
  noPlan.value = false
  try {
    canvas.value = await fetchPlanCanvas(props.runId)
  } catch (exception) {
    canvas.value = null
    const message = exception instanceof Error ? exception.message : String(exception)
    // 后端在 run 尚无计划版本时返回 404，这是常态而非故障。
    if (/404|不存在|没有计划/.test(message)) noPlan.value = true
    else error.value = message
  } finally {
    loading.value = false
  }
}

watch(() => [props.runId, props.planVersionId], load, { immediate: true })

const phases = computed(() => canvas.value?.phases ?? [])

/** 画布高度取最长的一列，短列留白即可，不强行拉齐。 */
const height = computed(() => {
  const longest = phases.value.reduce((max, phase) => Math.max(max, phase.steps.length), 0)
  return PAD_TOP + HEAD_H + 6 + longest * (STEP_H + STEP_GAP) + PAD_TOP
})
const width = computed(() => Math.max(1, phases.value.length) * (COL_W + COL_GAP) - COL_GAP)

function colX(index: number): number {
  return index * (COL_W + COL_GAP)
}

/** 阶段依赖：从上游列右缘绕到下游列左缘，正交折线，与 TimingGraph 同画法。 */
const edges = computed(() => {
  const indexById = new Map(phases.value.map((phase, index) => [phase.id, index]))
  const list: { key: string; d: string }[] = []
  phases.value.forEach((phase, toIndex) => {
    for (const fromId of phase.dependsOn) {
      const fromIndex = indexById.get(fromId)
      if (fromIndex === undefined || fromIndex === toIndex) continue
      const x1 = colX(fromIndex) + COL_W
      const x2 = colX(toIndex)
      const y = PAD_TOP + HEAD_H / 2
      const mid = x1 + (x2 - x1) / 2
      list.push({ key: `${fromId}->${phase.id}`, d: `M ${x1} ${y} H ${mid} V ${y} H ${x2}` })
    }
  })
  return list
})

function statusClass(status: PlanNodeStatus): string {
  return `is-${status.toLowerCase()}`
}

/**
 * 全画布共用的目标节点：绝大多数计划里每一步都打在同一台机器上，
 * 逐条重复 `@node-kylin-01` 只会挤掉工具名。相同就提到标题栏说一次，
 * 只有与之不同的步骤才在自己那行标出节点。
 */
const commonNodeId = computed(() => {
  const ids = new Set<string>()
  for (const phase of phases.value) {
    for (const step of phase.steps) if (step.nodeId) ids.add(step.nodeId)
  }
  return ids.size === 1 ? [...ids][0] : ''
})

/** 步骤副标题：工具名为主；节点与共用节点不同才显示。风险与审批走独立的 chip。 */
function meta(step: PlanStep): string {
  const parts: string[] = []
  if (step.toolId) parts.push(step.toolId)
  if (step.nodeId && step.nodeId !== commonNodeId.value) parts.push(`@${step.nodeId}`)
  return parts.join(' · ')
}

function onSelect(step: PlanStep) {
  selectedStepId.value = step.id
  // 只有落地过的步骤才有东西可查；没落地的点了不该跳到空面板。
  if (step.toolInvocationId) emit('inspect', step.toolInvocationId)
}

const coverage = computed(() => {
  const view = canvas.value
  if (!view || view.parsedStepCount === 0) return ''
  return `${view.matchedStepCount}/${view.parsedStepCount} 步已落地`
})
</script>

<template>
  <section v-if="!noPlan" class="plan">
    <div class="hd">
      <h3>计划画布</h3>
      <p v-if="canvas">
        v{{ canvas.version }}<span v-if="canvas.baseVersion"> · 基于 v{{ canvas.baseVersion }}</span>
        · {{ coverage }}。状态取自真实工具调用，点击已落地的步骤可在右侧查看那次执行。
      </p>
      <p v-else>PLAN 模式产出的结构化计划，以及它兑现到哪一步。</p>
      <p v-if="commonNodeId" class="node-note">
        全部步骤目标节点：<code>{{ commonNodeId }}</code>
      </p>
    </div>

    <div v-if="loading" class="blank">正在加载计划…</div>
    <div v-else-if="error" class="blank err">计划加载失败：{{ error }}</div>
    <div v-else-if="!phases.length" class="blank">这份计划没有可结构化的阶段与步骤</div>

    <div v-else class="scroll">
      <svg :width="width" :height="height" class="wire" aria-hidden="true">
        <path v-for="edge in edges" :key="edge.key" class="edge" :d="edge.d" />
      </svg>

      <div class="cols" :style="{ width: `${width}px` }">
        <div v-for="phase in phases" :key="phase.id" class="col" :style="{ width: `${COL_W}px` }">
          <div class="col-hd" :class="statusClass(phase.status)">
            <span class="dot" />
            <span class="col-title">{{ phase.title }}</span>
            <span class="col-count">{{ phase.steps.length }}</span>
          </div>

          <button
            v-for="step in phase.steps"
            :key="step.id"
            type="button"
            class="step"
            :class="[statusClass(step.status), {
              sel: selectedStepId === step.id,
              landed: !!step.toolInvocationId,
            }]"
            @click="onSelect(step)"
          >
            <!-- 左侧状态条：步骤状态的主要读出口。边框微调差异太弱，眯眼分不出
                 DONE / SKIPPED / FAILED，实测截图里这三种几乎同色。 -->
            <span class="rail" />
            <span class="step-body">
              <span class="step-top">
                <span class="step-title">{{ step.title }}</span>
                <span v-if="step.riskCode" class="chip risk">{{ step.riskCode }}</span>
                <span v-if="step.requiresApproval" class="chip appr">需审批</span>
              </span>
              <span class="step-meta">{{ meta(step) || '未指定工具' }}</span>
              <!-- 拒绝码独占一行：它是「计划为什么没走通」的答案，绝不能被省略号吃掉 -->
              <span v-if="step.rejectionCode" class="rej">{{ step.rejectionCode }}</span>
            </span>
          </button>

          <div v-if="!phase.steps.length" class="col-empty">无步骤</div>
        </div>
      </div>
    </div>

    <p v-if="canvas && canvas.unparsedLineCount > 0" class="foot">
      计划正文另有 {{ canvas.unparsedLineCount }} 行说明文字未进入画布——画布只呈现符合
      「<code>## 阶段</code> / <code>- 步骤</code>」约定的部分，其余内容请看计划原文。
    </p>
  </section>
</template>

<style scoped>
.plan {
  margin-top: 18px;
  border-top: 1px solid var(--ws-rail);
  padding-top: 14px;
}
.hd h3 {
  margin: 0 0 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
}
.hd p {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-3);
}
.blank {
  padding: 20px 0;
  font-size: 12px;
  color: var(--text-3);
}
.blank.err {
  color: var(--ws-danger);
}

.scroll {
  position: relative;
  overflow-x: auto;
  padding-bottom: 4px;
}
.wire {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.edge {
  fill: none;
  stroke: var(--ws-rail);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.cols {
  position: relative;
  display: flex;
  gap: 36px;
}
.col {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 0 0 auto;
}

.col-hd {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--radius);
  background: var(--ws-inset);
  box-shadow: inset 0 0 0 1px var(--ws-rail);
}
.col-title {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.col-count {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-3);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ws-neutral);
  flex: 0 0 auto;
}

.step {
  display: flex;
  align-items: stretch;
  gap: 0;
  padding: 0;
  min-height: 46px;
  overflow: hidden;
  text-align: left;
  border: 0;
  border-radius: var(--radius);
  background: var(--ws-panel);
  box-shadow: inset 0 0 0 1px var(--ws-rail);
  cursor: default;
  transition: box-shadow var(--ws-fast) var(--ws-ease), transform var(--ws-fast) var(--ws-ease);
}
/* 左侧状态条：3px 实色，是步骤状态唯一需要眯眼也能读出的信号。 */
.rail {
  flex: 0 0 3px;
  background: var(--ws-neutral);
}
.step-body {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  min-width: 0;
  padding: 7px 10px;
  flex: 1;
}
/* 只有落地过的步骤可点——没落地的点了也没有执行记录可看。 */
.step.landed {
  cursor: pointer;
}
.step.landed:hover {
  transform: translateY(-1px);
  box-shadow: inset 0 0 0 1px var(--border-2), var(--shadow);
}
.step.sel {
  box-shadow: var(--ws-glow);
}
.step-top {
  display: flex;
  align-items: center;
  gap: 6px;
}
.step-title {
  flex: 1;
  font-size: 12px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.step-meta {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rej {
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1.4;
  color: var(--ws-danger);
  overflow-wrap: anywhere;
}
.chip {
  flex: 0 0 auto;
  padding: 1px 5px;
  border-radius: 999px;
  font-size: 10px;
  line-height: 1.5;
}
.chip.risk {
  font-family: var(--font-mono);
  color: var(--text-3);
  background: color-mix(in oklch, var(--text-3) 10%, transparent);
}
.chip.appr {
  color: var(--ws-warn);
  background: color-mix(in oklch, var(--warn) 12%, transparent);
}
.col-empty {
  padding: 10px;
  font-size: 11px;
  color: var(--text-3);
}

/* ── 状态：色相只在这里出现 ───────────────────────────────────────── */
.is-pending .dot {
  background: var(--ws-neutral);
}
.is-running .dot {
  background: var(--ws-running);
  animation: ws-breathe 1.8s ease-in-out infinite;
}
.is-done .dot {
  background: var(--ws-ok);
}
.is-skipped .dot {
  background: var(--ws-warn);
}
.is-failed .dot {
  background: var(--ws-danger);
}

/* 状态条取色：与阶段圆点同一套五色出口 */
.step.is-pending .rail {
  background: var(--ws-rail);
}
.step.is-running .rail {
  background: var(--ws-running);
  animation: ws-breathe 1.8s ease-in-out infinite;
}
.step.is-done .rail {
  background: var(--ws-ok);
}
.step.is-skipped .rail {
  background: var(--ws-warn);
}
.step.is-failed .rail {
  background: var(--ws-danger);
}

.step.is-running {
  box-shadow: var(--ws-glow);
}
/* 未落地的步骤压低存在感：计划里有，但还没发生。 */
.step.is-pending .step-title,
.step.is-pending .step-meta {
  opacity: 0.62;
}

.node-note {
  margin: -6px 0 10px !important;
  font-size: 11px;
  color: var(--text-3);
}
.node-note code {
  font-family: var(--font-mono);
  font-size: 10px;
}

.foot {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-3);
}
.foot code {
  font-family: var(--font-mono);
  font-size: 10px;
}
</style>
