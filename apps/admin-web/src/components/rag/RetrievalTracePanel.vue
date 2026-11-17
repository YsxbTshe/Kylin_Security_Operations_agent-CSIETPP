<script setup lang="ts">
/**
 * 混合检索预览：把一次真实检索拆成三幕演给人看。
 *
 *   1. 多路召回 —— 向量 / 关键词 / 图谱各自的候选
 *   2. RRF 融合 —— 各路名次换算成贡献分相加，决定谁进 topK
 *   3. 交叉重排 —— 精排模型按真实语义匹配度重新排序
 *
 * 定位是"动画"，不是数据表：每路只露前几条，其余以计数收口，整体高度克制，
 * 不与页面主体争视觉权重。要看完整数据走下方的结果表。
 *
 * 所有数字都来自后端 trace，没有编造：某路没召回就显示未召回及其原因，
 * 重排未启用或降级也如实标注，不会一律画成"已重排"。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RotateCcw } from 'lucide-vue-next'
import type { RagRetrievalTrace } from '../../api/rag'

const props = defineProps<{ trace: RagRetrievalTrace | null }>()

/** 每路/每幕最多露出的条数——刻意小，多余的只报数量 */
const PEEK = 3

const ACTS = [
  { key: 'recall', label: '多路召回' },
  { key: 'fuse', label: 'RRF 融合' },
  { key: 'rerank', label: '交叉重排' },
] as const
type ActKey = (typeof ACTS)[number]['key']

const act = ref<ActKey>('recall')
let timers: number[] = []

function clearTimers() {
  timers.forEach((id) => window.clearTimeout(id))
  timers = []
}
onBeforeUnmount(clearTimers)

function play() {
  clearTimers()
  act.value = 'recall'
  timers.push(window.setTimeout(() => (act.value = 'fuse'), 1700))
  timers.push(window.setTimeout(() => (act.value = 'rerank'), 3600))
}

watch(() => props.trace, (trace) => (trace ? play() : clearTimers()), { immediate: true })

const channels = computed(() => props.trace?.channels ?? [])

const fusedPeek = computed(() => (props.trace?.fused ?? []).slice(0, PEEK + 1))
const fusedRest = computed(() => Math.max(0, (props.trace?.fused.length ?? 0) - fusedPeek.value.length))

const rerank = computed(() => props.trace?.rerank ?? null)
const rerankPeek = computed(() => (rerank.value?.items ?? []).slice(0, PEEK + 1))
const rerankRest = computed(() => Math.max(0, (rerank.value?.items.length ?? 0) - rerankPeek.value.length))

const rerankNotice = computed(() => {
  if (!rerank.value) return ''
  if (rerank.value.status === 'DISABLED') return '未启用交叉重排，最终顺序即融合顺序。'
  if (rerank.value.status === 'FALLBACK') return '重排服务调用失败，已退回融合顺序。'
  return ''
})

/** 精排相对融合的名次变化：正数表示被提上来 */
function rankDelta(fusedRank: number, rank: number) {
  return fusedRank > 0 ? fusedRank - rank : 0
}

function channelLabel(key: string) {
  return channels.value.find((channel) => channel.key === key)?.label ?? key
}

function shortTitle(title: string) {
  return title.length > 34 ? `${title.slice(0, 34)}…` : title
}
</script>

<template>
  <section v-if="trace" class="rt">
    <header class="rt__bar">
      <button
        v-for="(item, index) in ACTS"
        :key="item.key"
        type="button"
        class="rt__act"
        :class="{ 'rt__act--on': act === item.key }"
        @click="clearTimers(); act = item.key"
      >
        <span class="rt__act-no">{{ index + 1 }}</span>{{ item.label }}
      </button>
      <span class="rt__spacer" />
      <span class="rt__meta">RRF k={{ trace.rrfK }} · topK={{ trace.topK }}</span>
      <button type="button" class="rt__replay" title="重放" aria-label="重放" @click="play">
        <RotateCcw />
      </button>
    </header>

    <div class="rt__stage">
      <!-- 幕一：多路召回 -->
      <div v-if="act === 'recall'" class="rt__cols">
        <div v-for="(channel, ci) in channels" :key="channel.key" class="rt__col" :style="{ '--i': ci }">
          <div class="rt__col-head">
            <span class="rt__dot" :class="`rt__dot--${channel.key}`" />
            <b>{{ channel.label }}</b>
            <span class="rt__weight">w {{ channel.weight }}</span>
          </div>
          <p class="rt__detail" :title="channel.detail">{{ channel.detail }}</p>
          <template v-if="channel.candidates.length">
            <div
              v-for="(cand, index) in channel.candidates.slice(0, PEEK)"
              :key="cand.chunkId"
              class="rt__row"
              :style="{ '--j': index }"
            >
              <span class="rt__rank">{{ cand.rank }}</span>
              <span class="rt__title">{{ shortTitle(cand.title) }}</span>
              <span class="rt__score num">{{ cand.score.toFixed(3) }}</span>
            </div>
            <p v-if="channel.candidates.length > PEEK" class="rt__rest">
              还有 {{ channel.candidates.length - PEEK }} 条候选
            </p>
          </template>
          <p v-else class="rt__empty">本轮未召回</p>
        </div>
      </div>

      <!-- 幕二：RRF 融合 -->
      <div v-else-if="act === 'fuse'" class="rt__list">
        <div
          v-for="(item, index) in fusedPeek"
          :key="item.chunkId"
          class="rt__frow"
          :class="{ 'rt__frow--out': !item.selected }"
          :style="{ '--j': index }"
        >
          <span class="rt__rank">{{ item.rank }}</span>
          <span class="rt__title">{{ shortTitle(item.title) }}</span>
          <span class="rt__chips">
            <i
              v-for="contribution in item.contributions"
              :key="contribution.channel"
              class="rt__chip"
              :class="`rt__chip--${contribution.channel}`"
              :title="`${channelLabel(contribution.channel)} 第 ${contribution.rank} 名，贡献 ${contribution.points.toFixed(5)}`"
            >{{ channelLabel(contribution.channel) }}#{{ contribution.rank }}</i>
          </span>
          <span class="rt__score num">{{ item.score.toFixed(4) }}</span>
        </div>
        <p v-if="fusedRest" class="rt__rest">还有 {{ fusedRest }} 条参与融合但未进入 topK</p>
      </div>

      <!-- 幕三：交叉重排 -->
      <div v-else class="rt__list">
        <template v-if="rerank?.status === 'APPLIED'">
          <div
            v-for="(item, index) in rerankPeek"
            :key="item.chunkId"
            class="rt__frow"
            :class="{ 'rt__frow--out': !item.selected }"
            :style="{ '--j': index }"
          >
            <span class="rt__rank">{{ item.rank }}</span>
            <span class="rt__title">{{ shortTitle(item.title) }}</span>
            <span
              class="rt__delta"
              :class="{
                'rt__delta--up': rankDelta(item.fusedRank, item.rank) > 0,
                'rt__delta--down': rankDelta(item.fusedRank, item.rank) < 0,
              }"
            >
              <template v-if="rankDelta(item.fusedRank, item.rank) > 0">↑{{ rankDelta(item.fusedRank, item.rank) }}</template>
              <template v-else-if="rankDelta(item.fusedRank, item.rank) < 0">↓{{ -rankDelta(item.fusedRank, item.rank) }}</template>
              <template v-else>–</template>
            </span>
            <span class="rt__score num">{{ item.relevance.toFixed(3) }}</span>
          </div>
          <p v-if="rerankRest" class="rt__rest">
            还有 {{ rerankRest }} 条参与重排 · 模型 {{ rerank.model }}
          </p>
        </template>
        <p v-else class="rt__notice">{{ rerankNotice }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.rt {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  overflow: hidden;
}

.rt__bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 7px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
}
.rt__act {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border: none;
  border-radius: 999px;
  background: transparent;
  font-size: 12px;
  color: var(--text-3);
  cursor: pointer;
  transition: background .18s, color .18s;
}
.rt__act:hover {
  color: var(--text-2);
}
.rt__act--on {
  background: var(--accent-soft);
  color: var(--accent);
}
.rt__act-no {
  display: grid;
  place-items: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: currentColor;
  color: var(--surface);
  font-size: 9px;
  font-weight: 700;
}
.rt__spacer {
  flex: 1;
}
.rt__meta {
  font-size: 11px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.rt__replay {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  margin-left: 6px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
}
.rt__replay:hover {
  color: var(--text);
  background: var(--surface-3);
}
.rt__replay :deep(svg) {
  width: 12px;
  height: 12px;
}

/* 高度刻意压住：这是预览动画，不是数据表 */
.rt__stage {
  min-height: 168px;
  padding: 10px 12px;
}

/* ── 幕一 ─────────────────────────────────────────────── */
.rt__cols {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.rt__col {
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  animation: rt-in .34s ease-out backwards;
  animation-delay: calc(var(--i) * 90ms);
}
.rt__col-head {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text);
}
.rt__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}
.rt__dot--vector { background: var(--chart-cat-1); }
.rt__dot--keyword { background: var(--chart-cat-2); }
.rt__dot--graph { background: var(--chart-sev-2); }
.rt__weight {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.rt__detail {
  margin: 3px 0 7px;
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--text-3);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}

/* ── 行 ───────────────────────────────────────────────── */
.rt__row,
.rt__frow {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 3px 0;
  font-size: 11.5px;
  min-width: 0;
  animation: rt-in .3s ease-out backwards;
  animation-delay: calc(140ms + var(--j) * 70ms);
}
.rt__frow {
  padding: 5px 0;
  border-bottom: 1px solid var(--border);
}
.rt__frow:last-of-type {
  border-bottom: none;
}
/* 未进 topK 的整行压暗，一眼看出筛选边界 */
.rt__frow--out {
  opacity: 0.42;
}
.rt__rank {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  flex: none;
  border-radius: var(--radius-sm);
  background: var(--surface-3);
  color: var(--text-2);
  font-size: 10px;
  font-weight: 600;
}
.rt__title {
  flex: 1;
  min-width: 0;
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rt__score {
  flex: none;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
  font-size: 10.5px;
}
.rt__chips {
  display: flex;
  gap: 3px;
  flex: none;
}
.rt__chip {
  padding: 1px 5px;
  border-radius: 999px;
  font-size: 9.5px;
  font-style: normal;
  white-space: nowrap;
}
.rt__chip--vector { background: var(--chart-cat-1-soft); color: var(--chart-cat-1); }
.rt__chip--keyword { background: var(--chart-cat-2-soft); color: var(--chart-cat-2); }
.rt__chip--graph { background: var(--warn-soft); color: var(--warn); }

.rt__delta {
  flex: none;
  width: 26px;
  text-align: right;
  font-size: 10.5px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.rt__delta--up { color: var(--ok); }
.rt__delta--down { color: var(--text-3); }

.rt__rest,
.rt__empty,
.rt__notice {
  margin: 6px 0 0;
  font-size: 10.5px;
  color: var(--text-3);
}
/* "还有 N 条"用轻微模糊表达"省略了内容"，而不是假装没有 */
.rt__rest {
  filter: blur(.2px);
  opacity: .72;
}
.rt__empty {
  margin-top: 4px;
  font-style: normal;
}
.rt__notice {
  margin: 0;
  padding: 6px 0;
  color: var(--warn);
  font-size: 12px;
}

@keyframes rt-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 860px) {
  .rt__cols {
    grid-template-columns: 1fr;
  }
}
@media (prefers-reduced-motion: reduce) {
  .rt__col,
  .rt__row,
  .rt__frow {
    animation: none;
  }
}
</style>
