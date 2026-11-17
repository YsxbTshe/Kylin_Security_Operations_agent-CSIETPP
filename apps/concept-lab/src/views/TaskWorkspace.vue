<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import Icon from '../components/Icon.vue'

const stages = [
  { name: '意图理解', short: '01', detail: '识别“修复生产区高危 SSH 配置”意图，抽取范围与约束。', event: 'intent.parsed', latency: '128 ms' },
  { name: '资产圈定', short: '02', detail: '由 CMDB 标签解析生产区麒麟服务器，共命中 24 台。', event: 'scope.resolved', latency: '84 ms' },
  { name: '知识检索', short: '03', detail: '混合召回 KYSA-2026-018、安全基线与历史处置记忆。', event: 'knowledge.retrieved', latency: '241 ms' },
  { name: '风险推演', short: '04', detail: '沙箱预演变更，发现 2 台堡垒机存在会话中断风险。', event: 'risk.simulated', latency: '1.8 s' },
  { name: '人工审批', short: '05', detail: '高风险生产变更触发双人复核，等待安全负责人授权。', event: 'approval.requested', latency: '—' },
  { name: '灰度执行', short: '06', detail: '在 3 台 canary 主机执行，实时观测 SSH 与业务健康。', event: 'canary.executed', latency: '16.4 s' },
  { name: '全量执行', short: '07', detail: '按机架分批并发，失败阈值 5%，自动熔断与回滚。', event: 'fleet.executed', latency: '42.7 s' },
  { name: '结果验证', short: '08', detail: '配置、进程、端口与业务探针交叉验证，无漂移。', event: 'outcome.verified', latency: '8.3 s' },
  { name: '证据封存', short: '09', detail: '以 SM3 哈希链封存全链路证据，后续改写可被校验发现。', event: 'evidence.sealed', latency: '392 ms' },
]

const evidence = [
  { name: '策略通过', note: 'R0–R5 硬策略与意图对齐通过', value: 'R3 · 通过', icon: 'shield' },
  { name: '审批合规', note: '职责分离、HMAC-SM3 上下文绑定', value: '单次有效', icon: 'spark' },
  { name: '端侧执行', note: '非 root Agent 与白名单动作留痕', value: '主机已确认', icon: 'terminal' },
  { name: '结果验证', note: '配置、进程与业务探针交叉验证', value: '演示数据 24/24', icon: 'check' },
  { name: '审计链完整', note: 'SM3 哈希链可验证、篡改可发现', value: '链校验通过', icon: 'database' },
]

const auditLines = [
  ['14:32:08.142', 'intent.parsed', 'principal=demo-operator trace=3f8a…'],
  ['14:32:08.226', 'scope.resolved', 'selector=env:prod,os:kylin count=24'],
  ['14:32:08.467', 'knowledge.retrieved', 'hits=7 fusion=rrf@60 corpus=kysec'],
  ['14:32:10.291', 'risk.simulated', 'risk=HIGH affected=bastion-01,bastion-02'],
  ['14:32:18.004', 'approval.granted', 'approver=demo-role-b separation=valid'],
  ['14:32:34.442', 'canary.verified', 'nodes=3 health=green rollback=false'],
  ['14:33:17.111', 'fleet.completed', 'success=24 failed=0 skipped=0'],
  ['14:33:25.403', 'evidence.sealed', 'algorithm=SM3-chain package=EV-093'],
]

const activeIndex = ref(4)
const approved = ref(false)
const playing = ref(false)
const tab = ref('evidence')
let timer

const completion = computed(() => Math.round(((activeIndex.value + 1) / stages.length) * 100))
const statusFor = (index) => index < activeIndex.value || (approved.value && index === 4) ? 'done' : index === activeIndex.value ? 'active' : 'pending'
const selectStage = (index) => { activeIndex.value = index; playing.value = false; clearInterval(timer) }
const approve = () => { approved.value = true; activeIndex.value = Math.max(activeIndex.value, 5) }
const togglePlayback = () => {
  playing.value = !playing.value
  clearInterval(timer)
  if (!playing.value) return
  if (activeIndex.value >= stages.length - 1) activeIndex.value = 0
  timer = setInterval(() => {
    if (activeIndex.value === 4 && !approved.value) {
      playing.value = false
      clearInterval(timer)
      return
    }
    if (activeIndex.value >= stages.length - 1) {
      playing.value = false
      clearInterval(timer)
    } else activeIndex.value += 1
  }, 1200)
}
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div class="task-workspace">
    <div class="hero-row">
      <div>
        <p class="eyebrow">模拟任务回放</p>
        <h2 class="section-heading">一次任务，不只是“执行成功”</h2>
        <p class="section-copy">把意图、授权、执行和验证收束为可回放的证据链。点击任一阶段，查看当时系统知道什么、做了什么、凭什么可信。</p>
      </div>
      <div class="hero-actions">
        <span class="tag warn">模拟数据 · 24 台逻辑实例</span>
        <button class="button" @click="togglePlayback"><Icon :name="playing ? 'pause' : 'play'" :size="15" />{{ playing ? '暂停回放' : '阶段回放' }}</button>
      </div>
    </div>

    <div class="workspace-grid">
      <section class="panel timeline-panel">
        <header class="panel-header">
          <div class="panel-title"><div><h3>执行阶段</h3><p>九阶段受控执行链</p></div></div>
          <span class="mono progress-label">{{ completion }}%</span>
        </header>
        <div class="timeline-progress"><div class="progress-track"><div class="progress-fill" :style="{ width: completion + '%' }"></div></div></div>
        <div class="stage-list">
          <button v-for="(stage, index) in stages" :key="stage.name" class="stage-row" :class="statusFor(index)" @click="selectStage(index)">
            <span class="stage-index">{{ stage.short }}</span>
            <span class="stage-node"><Icon v-if="statusFor(index) === 'done'" name="check" :size="13" /><span v-else></span></span>
            <span class="stage-copy"><strong>{{ stage.name }}</strong><small>{{ stage.event }}</small></span>
            <span class="stage-time mono">{{ stage.latency }}</span>
            <Icon name="chevron" :size="14" class="row-arrow" />
          </button>
        </div>
      </section>

      <section class="panel evidence-panel">
        <header class="panel-header inspector-head">
          <div class="panel-title"><div><h3>证据检查器</h3><p>查看当前阶段的决策依据</p></div></div>
          <span class="tag" :class="activeIndex >= 8 ? 'good' : 'warn'">{{ activeIndex >= 8 ? '证据已封存' : '链路生成中' }}</span>
        </header>
        <div class="current-event">
          <div class="event-kicker"><span>当前阶段</span><span class="mono">{{ stages[activeIndex].event }}</span></div>
          <h3>{{ stages[activeIndex].name }}</h3>
          <p>{{ stages[activeIndex].detail }}</p>
          <div class="event-meta"><span>TRACE <b class="mono">3f8a-72bc</b></span><span>POLICY <b class="mono">prod-sec-04</b></span></div>
        </div>

        <div v-if="activeIndex === 4 && !approved" class="approval-card">
          <div class="approval-icon"><Icon name="shield" :size="21" /></div>
          <div><p class="eyebrow">人工审批</p><h3>高风险变更等待审批</h3><p>2 台堡垒机有活跃会话。系统建议从变更范围排除并单独安排窗口。</p></div>
          <div class="approval-facts"><span>风险等级 <b>R3</b></span><span>模拟影响面 <b>2 / 24</b></span><span>回滚点 <b>已创建</b></span></div>
          <div class="button-row"><button class="button primary" @click="approve"><Icon name="check" :size="15" />通过并继续</button><button class="button danger">退回修订</button></div>
        </div>
        <div v-else-if="approved" class="approved-strip"><Icon name="check" :size="17" /><span><b>审批角色 B</b> 已完成职责分离审批</span><small class="mono">14:32:18 · HMAC-SM3 已绑定</small></div>

        <div class="inspector-tabs" role="tablist">
          <button :class="{ active: tab === 'evidence' }" @click="tab = 'evidence'">五证可信完成</button>
          <button :class="{ active: tab === 'raw' }" @click="tab = 'raw'">原始审计</button>
        </div>
        <div v-if="tab === 'evidence'" class="evidence-list">
          <div v-for="(item, index) in evidence" :key="item.name" class="evidence-item" :class="{ verified: activeIndex >= Math.min(index * 2, 8) }">
            <span class="evidence-icon"><Icon :name="item.icon" :size="17" /></span>
            <span><strong>{{ item.name }}</strong><small>{{ item.note }}</small></span>
            <span class="evidence-value mono">{{ item.value }}</span>
          </div>
        </div>
        <div v-else class="audit-terminal">
          <div class="terminal-top"><span></span><span></span><span></span><b>tamper-evident-audit.log</b></div>
          <div v-for="line in auditLines" :key="line[0]" class="audit-line"><time>{{ line[0] }}</time><b>{{ line[1] }}</b><span>{{ line[2] }}</span></div>
          <div class="audit-hash">链头 · <b>8fd2c7e2…0a19</b> · 校验通过 ✓</div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.workspace-grid { display: grid; grid-template-columns: minmax(330px, .88fr) minmax(440px, 1.45fr); gap: 16px; }
.progress-label { color: var(--mint); font-size: 12px; }
.timeline-progress { padding: 14px 18px 6px; }
.stage-list { padding: 6px 9px 10px; }
.stage-row { position: relative; width: 100%; min-height: 56px; display: grid; grid-template-columns: 32px 24px minmax(0,1fr) 55px 16px; align-items: center; gap: 9px; padding: 6px 10px; border: 0; border-radius: 10px; background: transparent; color: var(--faint); text-align: left; cursor: pointer; }
.stage-row:hover { background: rgba(119,242,182,.045); color: var(--muted); }
.stage-row.active { color: var(--text); background: var(--panel-soft); }
.stage-row.active::before { content: ''; position: absolute; inset: 7px auto 7px 0; width: 2px; background: var(--mint); }
.stage-index { font: 10px 'DM Mono'; }
.stage-node { position: relative; width: 20px; height: 20px; display: grid; place-items: center; border: 1px solid var(--line); border-radius: 50%; }
.stage-row:not(:last-child) .stage-node::after { content: ''; position: absolute; top: 20px; width: 1px; height: 36px; background: var(--line); }
.stage-row.done .stage-node { color: #07110e; border-color: var(--mint); background: var(--mint); }
.stage-row.done .stage-node::after { background: rgba(119,242,182,.4); }
.stage-row.active .stage-node { border-color: var(--mint); }
.stage-row.active .stage-node span { width: 6px; height: 6px; border-radius: 50%; background: var(--mint); }
.stage-copy { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.stage-copy strong { font-size: 12px; font-weight: 500; }
.stage-copy small { overflow: hidden; color: var(--faint); font: 9px 'DM Mono'; text-overflow: ellipsis; white-space: nowrap; }
.stage-time { font-size: 9px; text-align: right; }
.row-arrow { opacity: .35; }
.current-event { padding: 18px 18px 16px; border-bottom: 1px solid var(--line); background: var(--panel-strong); }
.event-kicker { display: flex; justify-content: space-between; color: var(--mint); font-size: 10px; letter-spacing: .06em; }
.current-event h3 { margin: 12px 0 7px; font-size: 20px; }
.current-event p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.event-meta { display: flex; gap: 18px; margin-top: 14px; color: var(--faint); font: 9px 'DM Mono'; }
.event-meta b { color: var(--muted); font-weight: 400; }
.approval-card { margin: 14px; padding: 15px; display: grid; grid-template-columns: 40px minmax(0,1fr); gap: 11px; border: 1px solid rgba(210,163,92,.45); border-radius: 8px; background: var(--panel-strong); }
.approval-icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 9px; color: var(--amber); background: rgba(241,192,106,.1); }
.approval-card h3 { margin: 0 0 5px; font-size: 14px; }
.approval-card p:not(.eyebrow) { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.6; }
.approval-facts, .approval-card .button-row { grid-column: 2; }
.approval-facts { display: flex; flex-wrap: wrap; gap: 8px 18px; color: var(--faint); font-size: 10px; }
.approval-facts b { margin-left: 4px; color: var(--amber); }
.approved-strip { margin: 14px; padding: 10px 12px; display: flex; align-items: center; gap: 9px; border: 1px solid rgba(119,242,182,.22); border-radius: 10px; color: var(--mint); background: rgba(119,242,182,.06); font-size: 11px; }
.approved-strip small { margin-left: auto; color: var(--faint); }
.inspector-tabs { display: flex; gap: 18px; padding: 0 18px; border-bottom: 1px solid var(--line); }
.inspector-tabs button { position: relative; padding: 13px 0 11px; border: 0; background: transparent; color: var(--faint); cursor: pointer; font-size: 11px; }
.inspector-tabs button.active { color: var(--text); }
.inspector-tabs button.active::after { content: ''; position: absolute; inset: auto 0 -1px; height: 2px; background: var(--mint); }
.evidence-list { padding: 8px 14px 14px; }
.evidence-item { display: grid; grid-template-columns: 38px minmax(0,1fr) auto; align-items: center; gap: 10px; padding: 10px 5px; border-bottom: 1px solid var(--line); opacity: .42; }
.evidence-item:last-child { border: 0; }
.evidence-item.verified { opacity: 1; }
.evidence-icon { width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid var(--line); border-radius: 9px; color: var(--faint); }
.verified .evidence-icon { color: var(--mint); border-color: rgba(119,242,182,.25); background: rgba(119,242,182,.06); }
.evidence-item strong, .evidence-item small { display: block; }
.evidence-item strong { font-size: 11px; }
.evidence-item small { margin-top: 2px; color: var(--faint); font-size: 9px; }
.evidence-value { color: var(--muted); font-size: 9px; }
.audit-terminal { margin: 14px; overflow: hidden; border: 1px solid var(--line); border-radius: 8px; background: #0b0c0e; font: 9px 'DM Mono'; }
.terminal-top { display: flex; align-items: center; gap: 5px; padding: 9px 11px; border-bottom: 1px solid var(--line); background: rgba(255,255,255,.02); }
.terminal-top span { width: 6px; height: 6px; border-radius: 50%; background: var(--faint); }
.terminal-top b { margin-left: 5px; color: var(--faint); font-weight: 400; }
.audit-line { display: grid; grid-template-columns: 80px 135px minmax(0,1fr); gap: 9px; padding: 7px 11px; border-bottom: 1px solid rgba(141,183,160,.07); }
.audit-line time { color: var(--faint); }
.audit-line b { color: var(--mint); font-weight: 400; }
.audit-line span { overflow: hidden; color: var(--muted); text-overflow: ellipsis; white-space: nowrap; }
.audit-hash { padding: 10px 11px; color: var(--mint); background: rgba(119,242,182,.05); }

@media (max-width: 1100px) { .workspace-grid { grid-template-columns: 1fr; } }
</style>
