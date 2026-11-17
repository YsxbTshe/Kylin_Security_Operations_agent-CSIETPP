<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ScreenDashboardResponse } from '../api/screen'
import { highRiskCount, onlineRate, eventTone } from './shared'

/**
 * 大屏三幕（决赛计划 F 旗舰演示）：态势 → 任务直播 → 证据结论。
 * 全部消费真实聚合数据（含事件闭环板块）；离线基准数字明确标注来源、不与实时混写。
 */
const props = defineProps<{ dashboard: ScreenDashboardResponse | null }>()

type ActKey = 'situation' | 'mission' | 'benchmark'
const ACTS: Array<{ id: ActKey; index: string; name: string; subtitle: string; seconds: number }> = [
  { id: 'situation', index: '01', name: '安全态势', subtitle: '看见边界与风险', seconds: 50 },
  { id: 'mission', index: '02', name: '任务直播', subtitle: '看见受控执行', seconds: 80 },
  { id: 'benchmark', index: '03', name: '证据结论', subtitle: '看见真实增益', seconds: 50 },
]
const act = ref<ActKey>('situation')
const autoPlay = ref(false)
let autoTimer: ReturnType<typeof window.setInterval> | undefined

function advance() {
  const i = ACTS.findIndex((a) => a.id === act.value)
  act.value = ACTS[(i + 1) % ACTS.length].id
}
function toggleAuto() {
  autoPlay.value = !autoPlay.value
  if (autoTimer) window.clearInterval(autoTimer)
  if (autoPlay.value) autoTimer = window.setInterval(advance, 12_000)
}
onMounted(() => void 0)
onBeforeUnmount(() => {
  if (autoTimer) window.clearInterval(autoTimer)
})

const currentAct = computed(() => ACTS.find((a) => a.id === act.value) ?? ACTS[0])
const summary = computed(() => props.dashboard?.summary)
const safeOps = computed(() => props.dashboard?.safeOps)
const incidents = computed(() => props.dashboard?.incidents)
const nativeGuard = computed(() => safeOps.value?.nativeGuardStatus)

// 态势评分：以主机在线率与 Native Guard 健康占比综合，落到 0-100（实时派生，非硬编码）。
const postureScore = computed(() => {
  const online = onlineRate(props.dashboard)
  const ng = nativeGuard.value
  const ngTotal = ng ? ng.healthyHosts + ng.warningHosts + ng.criticalHosts + ng.staleHosts + ng.unknownHosts + ng.unsupportedHosts : 0
  const ngHealthRate = ng && ngTotal > 0 ? (ng.healthyHosts / ngTotal) * 100 : online
  return Math.round(online * 0.5 + ngHealthRate * 0.5)
})
const postureArcOffset = computed(() => {
  const circumference = 302 // 2πr, r=48
  return circumference * (1 - postureScore.value / 100)
})

const runtime = computed(() => safeOps.value?.hostRuntimeStatus)
// 事件流指向后端顶层实时 events（safeOps.hostEvents 是旧执行链契约残留，后端从不返回、恒空）。
const hostEvents = computed(() => props.dashboard?.events ?? [])

function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  if (m < 60) return s ? `${m}m ${s}s` : `${m}m`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}
function dataModeLabel(mode: string): string {
  return mode === 'LIVE' ? '真机 LIVE' : mode === 'REPLAY' ? '回放 REPLAY' : '合成 SYNTHETIC'
}

// 离线基准（明确标注来源，不与实时数据混写）——数字逐条对齐 bench/*/results/results.json 产物，
// 改动这里必须同步核对产物与 docs/05-评估方案 下对应报告，任何不一致都会被现场核对当场戳穿。
const OFFLINE_BASELINE = [
  // bench/adversarial/results/results.json：attacks=21, attacksBlocked=21（另 2 例阳性对照通过）
  { label: '对抗桩拦截', value: '21 / 21', note: 'advbench 对抗电池 · 离线' },
  // bench/rag/results/results.json：vector+keyword recall@10 = 0.7578846
  { label: 'RAG 混合 Recall@10', value: '0.758', note: '向量+关键词 · 离线基准' },
  // bench/memory/results/results.json：时序更新查询 MRR@10 semantic 0.75 → generative-agents/ours 1.0
  { label: 'Memory 时序 MRR', value: '0.75 → 1.0', note: '时近+重要度 · 离线基准' },
]
</script>

<template>
  <div class="acts">
    <div class="acts__strip">
      <button
        v-for="item in ACTS"
        :key="item.id"
        class="acts__tab"
        :class="{ 'acts__tab--active': act === item.id }"
        @click="act = item.id"
      >
        <span class="acts__idx">{{ item.index }}</span>
        <span class="acts__meta"><b>{{ item.name }}</b><small>{{ item.subtitle }} · {{ item.seconds }}s</small></span>
      </button>
      <button class="acts__auto" :class="{ 'acts__auto--on': autoPlay }" @click="toggleAuto">
        {{ autoPlay ? '⏸ 自动切幕' : '▶ 自动切幕' }}
      </button>
    </div>

    <!-- 幕 1 · 安全态势 -->
    <section v-if="act === 'situation'" class="canvas">
      <header class="canvas__hd"><div><p>麒麟智能安全运维中心</p><h3>全域安全态势</h3></div>
        <span class="pill pill--info">实时聚合</span></header>
      <div class="situation">
        <div class="posture">
          <div class="orbit">
            <svg viewBox="0 0 120 120"><circle cx="60" cy="60" r="48" /><circle class="arc" cx="60" cy="60" r="48" :style="{ strokeDashoffset: postureArcOffset }" /></svg>
            <div><b>{{ postureScore }}</b><small>安全态势</small></div>
          </div>
          <p>在线率 {{ onlineRate(dashboard) }}% · 高危告警 <span class="warn">{{ highRiskCount(dashboard) }}</span></p>
        </div>
        <div class="metrics-col">
          <article><p>主机在线</p><b>{{ summary?.onlineCount ?? 0 }} / {{ summary?.hostCount ?? 0 }}</b><small>gRPC 心跳在线节点</small></article>
          <article><p>Native Guard 健康</p><b>{{ nativeGuard?.healthyHosts ?? 0 }}</b><small>告警 {{ nativeGuard?.warningHosts ?? 0 }} · 严重 {{ nativeGuard?.criticalHosts ?? 0 }} · 探针 C{{ nativeGuard?.criticalProbes ?? 0 }}/W{{ nativeGuard?.warningProbes ?? 0 }} · 过期/未知 {{ (nativeGuard?.staleHosts ?? 0) + (nativeGuard?.unknownHosts ?? 0) }}</small></article>
          <article><p>待审批</p><b>{{ safeOps?.pendingApprovals ?? 0 }}</b><small>R3+ 写操作等待人工审批</small></article>
          <article><p>待处置事件</p><b class="warn">{{ incidents?.open ?? 0 }}</b><small>安全事件闭环未完成</small></article>
        </div>
      </div>
    </section>

    <!-- 幕 2 · 任务直播 -->
    <section v-else-if="act === 'mission'" class="canvas">
      <header class="canvas__hd"><div><p>受控执行 · SafeOps</p><h3>任务与事件直播</h3></div>
        <span class="pill pill--info">运行中 {{ runtime?.runningTasks ?? 0 }} · 待审批 {{ safeOps?.pendingApprovals ?? 0 }}</span></header>
      <div class="mission">
        <div class="mission__events">
          <div class="mission__label">主机侧事件流</div>
          <div v-if="hostEvents.length" class="events">
            <div v-for="(e, i) in hostEvents.slice(0, 8)" :key="i" class="event" :class="`event--${eventTone(e.type)}`">
              <time>{{ e.time }}</time>
              <b>{{ e.type }}</b>
              <span>{{ e.message }}</span>
            </div>
          </div>
          <div v-else class="empty">暂无主机事件</div>
        </div>
        <div class="mission__side">
          <div class="mission__label">运行态</div>
          <div class="run-grid">
            <div><b>{{ runtime?.runningTasks ?? 0 }}</b><small>运行中</small></div>
            <div><b>{{ runtime?.queuedTasks ?? 0 }}</b><small>排队</small></div>
            <div><b>{{ runtime?.localActiveLocks ?? 0 }}</b><small>资源锁</small></div>
            <div><b>{{ safeOps?.pendingApprovals ?? 0 }}</b><small>待审批</small></div>
          </div>
          <div class="mission__label">安全事件闭环</div>
          <div v-if="incidents?.latest?.length" class="inc-list">
            <div v-for="inc in incidents.latest.slice(0, 4)" :key="inc.id" class="inc">
              <span class="inc__mode" :class="`mode--${inc.dataMode}`">{{ dataModeLabel(inc.dataMode) }}</span>
              <b>{{ inc.title }}</b>
              <small>{{ inc.status }} · {{ inc.sourceIp || '—' }}</small>
            </div>
          </div>
          <div v-else class="empty">暂无事件</div>
        </div>
      </div>
    </section>

    <!-- 幕 3 · 证据结论 -->
    <section v-else class="canvas">
      <header class="canvas__hd"><div><p>可信完成与客观评估</p><h3>用限定结论证明真实价值</h3></div>
        <span class="pill pill--ok">实时 + 离线基准（不混写）</span></header>
      <div class="benchmark">
        <article class="bcard">
          <div class="bcard__t"><span>安全事件闭环</span><b>实时</b></div>
          <div class="closed-loop">
            <div><b>{{ incidents?.total ?? 0 }}</b><small>事件总数</small></div>
            <div><b class="warn">{{ incidents?.open ?? 0 }}</b><small>待处置</small></div>
            <div><b class="ok">{{ incidents?.resolved ?? 0 }}</b><small>已闭环</small></div>
          </div>
          <div class="mttr">
            <span>遏制 <b>{{ fmtDuration(incidents?.avgContainSeconds) }}</b></span>
            <span>MTTR <b>{{ fmtDuration(incidents?.avgResolveSeconds) }}</b></span>
          </div>
          <p>取证 → 临时阻断 → 验证 → 恢复的完整闭环时效（已解决事件均值）。</p>
        </article>
        <article class="bcard">
          <div class="bcard__t"><span>安全护栏 · 可信执行</span><b>实时</b></div>
          <div class="facts">
            <span>待审批 <b>{{ safeOps?.pendingApprovals ?? 0 }}</b></span>
            <span>活动证书 <b>{{ safeOps?.activeCertificates ?? 0 }}</b></span>
            <span>吊销证书 <b>{{ safeOps?.revokedCertificates ?? 0 }}</b></span>
            <span>到期预警 <b>{{ safeOps?.expiringCertificates ?? 0 }}</b></span>
          </div>
          <p>人工审批门 + mTLS 设备证书全生命周期（签发 / 吊销 / 到期预警）构成受控执行的实时护栏。</p>
        </article>
        <article class="bcard">
          <div class="bcard__t"><span>离线基准</span><b>见证据索引</b></div>
          <div class="baseline">
            <div v-for="b in OFFLINE_BASELINE" :key="b.label">
              <b>{{ b.value }}</b>
              <span>{{ b.label }}</span>
              <small>{{ b.note }}</small>
            </div>
          </div>
          <p>离线可复查基准，原始 JSON 可核对；与上方实时数据分区、不混写。</p>
        </article>
      </div>
    </section>

    <footer class="acts__ft">
      <span>{{ currentAct.index }} / 03 · {{ currentAct.name }}</span>
      <div class="acts__progress"><i :style="{ width: (Number(currentAct.index) / 3 * 100) + '%' }" /></div>
      <span>数据来源：实时聚合 + 离线基准（分区标注）</span>
    </footer>
  </div>
</template>

<style scoped>
.acts { display: flex; flex-direction: column; gap: 12px; }
.acts__strip { display: grid; grid-template-columns: repeat(3, minmax(150px, 1fr)) minmax(130px, .6fr); gap: 8px; }
.acts__tab { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-md); background: var(--hud-panel); color: var(--hud-text); cursor: pointer; text-align: left; }
.acts__tab--active { background: var(--hud-cyan-soft); border-color: var(--hud-cyan-strong); }
.acts__idx { font-family: 'DM Mono', monospace; font-size: 12px; color: var(--hud-cyan); }
.acts__meta { display: flex; flex-direction: column; }
.acts__meta b { font-size: 13px; }
.acts__meta small { margin-top: 2px; color: var(--hud-text-faint); font-size: 10px; }
.acts__auto { border: 1px solid var(--hud-cyan-line); border-radius: var(--r-md); background: var(--hud-panel); color: var(--hud-text-muted); cursor: pointer; font-size: 12px; }
.acts__auto--on { color: var(--hud-cyan); border-color: var(--hud-cyan-strong); }
.canvas { min-height: 520px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-md); background: var(--hud-panel); overflow: hidden; }
.canvas__hd { display: flex; align-items: center; justify-content: space-between; padding: 16px 22px; border-bottom: 1px solid var(--hud-cyan-line); }
.canvas__hd p { margin: 0 0 4px; color: var(--hud-cyan); font-size: 11px; letter-spacing: .1em; }
.canvas__hd h3 { margin: 0; font-size: 20px; font-weight: 500; color: var(--hud-text-strong); }
.pill { padding: 5px 10px; border-radius: 999px; font-size: 11px; border: 1px solid var(--hud-cyan-line); color: var(--hud-text-muted); }
.pill--info { color: var(--hud-info); }
.pill--ok { color: var(--hud-success); }
.warn { color: var(--hud-amber); }
.ok { color: var(--hud-success); }

.situation { display: grid; grid-template-columns: 300px 1fr; min-height: 440px; }
.posture { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; border-right: 1px solid var(--hud-cyan-line); padding: 24px; }
.orbit { position: relative; width: 190px; height: 190px; }
.orbit svg { width: 100%; transform: rotate(-90deg); }
.orbit circle { fill: none; stroke: rgba(0, 229, 255, .1); stroke-width: 6; }
.orbit .arc { stroke: var(--hud-cyan); stroke-dasharray: 302; stroke-linecap: round; transition: stroke-dashoffset .6s ease; }
.orbit > div { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; }
.orbit b { font: 500 48px 'DM Mono', monospace; color: var(--hud-text-strong); }
.orbit small { color: var(--hud-text-faint); font-size: 11px; }
.posture p { color: var(--hud-text-muted); font-size: 13px; text-align: center; }
.metrics-col { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 22px; align-content: center; }
.metrics-col article { padding: 16px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-md); background: var(--hud-panel-2); }
.metrics-col p { margin: 0; color: var(--hud-text-faint); font-size: 11px; }
.metrics-col b { display: block; margin: 8px 0 4px; font: 500 30px 'DM Mono', monospace; color: var(--hud-text-strong); }
.metrics-col small { color: var(--hud-text-faint); font-size: 10px; }

.mission { display: grid; grid-template-columns: 1fr 360px; min-height: 440px; }
.mission__events { padding: 20px; border-right: 1px solid var(--hud-cyan-line); }
.mission__side { padding: 20px; display: flex; flex-direction: column; gap: 10px; }
.mission__label { color: var(--hud-text-faint); font-size: 11px; letter-spacing: .08em; margin-bottom: 6px; }
.events { display: flex; flex-direction: column; gap: 8px; }
.event { display: grid; grid-template-columns: 62px 130px 1fr; gap: 10px; align-items: baseline; padding: 8px 10px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-sm); background: var(--hud-panel-2); font-size: 12px; }
.event time { color: var(--hud-text-faint); font-family: 'DM Mono', monospace; font-size: 11px; }
.event b { color: var(--hud-text); }
.event span { color: var(--hud-text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.event--danger b { color: var(--hud-danger); }
.event--warning b { color: var(--hud-warning); }
.event--success b { color: var(--hud-success); }
.run-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 6px; }
.run-grid div { padding: 12px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-sm); background: var(--hud-panel-2); text-align: center; }
.run-grid b { display: block; font: 500 24px 'DM Mono', monospace; color: var(--hud-text-strong); }
.run-grid small { color: var(--hud-text-faint); font-size: 10px; }
.inc-list { display: flex; flex-direction: column; gap: 8px; }
.inc { display: flex; flex-direction: column; gap: 3px; padding: 10px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-sm); background: var(--hud-panel-2); }
.inc b { font-size: 12.5px; color: var(--hud-text); }
.inc small { color: var(--hud-text-faint); font-size: 11px; }
.inc__mode { align-self: flex-start; padding: 1px 7px; border-radius: 999px; font-size: 10px; border: 1px solid var(--hud-cyan-line); color: var(--hud-text-muted); }
.mode--SYNTHETIC { color: var(--hud-text-muted); }
.mode--REPLAY { color: var(--hud-amber); border-color: var(--hud-amber); }
.mode--LIVE { color: var(--hud-cyan); border-color: var(--hud-cyan-strong); }
.empty { color: var(--hud-text-faint); font-size: 12px; padding: 16px 0; }

.benchmark { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; padding: 18px; min-height: 440px; }
.bcard { padding: 18px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-md); background: var(--hud-panel-2); display: flex; flex-direction: column; }
.bcard__t { display: flex; justify-content: space-between; align-items: center; padding-bottom: 12px; margin-bottom: 14px; border-bottom: 1px solid var(--hud-cyan-line); }
.bcard__t span { font-size: 13px; color: var(--hud-text); }
.bcard__t b { font-size: 11px; color: var(--hud-text-faint); font-family: 'DM Mono', monospace; }
.closed-loop { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.closed-loop div { text-align: center; }
.closed-loop b { display: block; font: 500 30px 'DM Mono', monospace; color: var(--hud-text-strong); }
.closed-loop small { color: var(--hud-text-faint); font-size: 10px; }
.mttr { display: flex; gap: 16px; margin: 16px 0; font-size: 13px; color: var(--hud-text-muted); }
.mttr b { color: var(--hud-cyan); font-family: 'DM Mono', monospace; }
.facts { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.facts span { display: flex; justify-content: space-between; padding: 10px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-sm); font-size: 12px; color: var(--hud-text-faint); }
.facts b { color: var(--hud-text-strong); font-family: 'DM Mono', monospace; }
.baseline { display: flex; flex-direction: column; gap: 10px; }
.baseline div { display: flex; flex-direction: column; gap: 2px; padding: 10px; border: 1px solid var(--hud-cyan-line); border-radius: var(--r-sm); }
.baseline b { font: 500 20px 'DM Mono', monospace; color: var(--hud-text-strong); }
.baseline span { font-size: 12px; color: var(--hud-text-muted); }
.baseline small { color: var(--hud-text-faint); font-size: 10px; }
.bcard > p { margin: 16px 0 0; padding-top: 12px; border-top: 1px solid var(--hud-cyan-line); color: var(--hud-text-muted); font-size: 11px; line-height: 1.6; }

.acts__ft { display: flex; align-items: center; gap: 14px; color: var(--hud-text-faint); font-size: 11px; font-family: 'DM Mono', monospace; }
.acts__ft > span:last-child { margin-left: auto; }
.acts__progress { flex: 1; max-width: 240px; height: 3px; background: var(--hud-cyan-line); border-radius: 2px; }
.acts__progress i { display: block; height: 100%; background: var(--hud-cyan); border-radius: 2px; transition: width .3s ease; }

@media (max-width: 1100px) {
  .situation, .mission { grid-template-columns: 1fr; }
  .posture { border-right: 0; border-bottom: 1px solid var(--hud-cyan-line); }
  .mission__events { border-right: 0; border-bottom: 1px solid var(--hud-cyan-line); }
  .benchmark { grid-template-columns: 1fr; }
}
</style>
