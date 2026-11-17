<script setup>
import { computed, ref } from 'vue'
import Icon from '../components/Icon.vue'

const phases = [
  { id: 'candidate', name: '候选', verb: '采集', note: '低置信信息先进入隔离区' },
  { id: 'consolidated', name: '巩固', verb: '验证', note: '来源、时间与实体关系补齐' },
  { id: 'conflict', name: '冲突', verb: '裁决', note: '新旧事实显式竞争' },
  { id: 'expired', name: '过期', verb: '清理', note: '失效内容退出默认召回' },
]

const memories = ref([
  { id: 1, phase: 'candidate', title: 'api-gateway 峰值时 CPU 偏高', fact: '扩容到 6 副本可能缓解', source: '任务 #0818 对话', confidence: .61, age: '3 小时', expiry: '7 天', selected: false },
  { id: 2, phase: 'candidate', title: 'prod-db-02 维护窗口', fact: '周日 02:00–04:00', source: '值班备注', confidence: .52, age: '1 天', expiry: '待验证', selected: true },
  { id: 3, phase: 'consolidated', title: 'billing-service 回滚手册', fact: '回滚前必须冻结异步消费者', source: 'Runbook v7 + 3 次任务', confidence: .94, age: '12 天', expiry: '90 天', selected: true },
  { id: 4, phase: 'consolidated', title: 'SSH 安全端口', fact: '生产区目标端口为 2222', source: 'CMDB · 2026-08-05', confidence: .98, age: '刚刚', expiry: '30 天', selected: true },
  { id: 5, phase: 'conflict', title: 'SSH 安全端口（旧）', fact: '生产区目标端口为 22', source: '工单 · 2026-07-28', confidence: .90, age: '8 天', expiry: '冲突中', selected: true },
  { id: 6, phase: 'expired', title: '临时代理地址', fact: 'proxy-old.internal:8080', source: '故障会话 · 2026-05-11', confidence: .83, age: '86 天', expiry: '已失效', selected: false },
])

const sleeping = ref(false)
const sleepStep = ref(0)
const activeMemory = ref(memories.value[4])
const recallMode = ref('active')
const sleepSteps = ['扫描候选与到期时间', '按实体聚类并检测重复', '发现 1 组时序冲突', '以新鲜度和权威来源裁决', '更新召回索引并生成整理报告']
let sleepTimer

const counts = computed(() => Object.fromEntries(phases.map(p => [p.id, memories.value.filter(m => m.phase === p.id).length])))
const currentSleepText = computed(() => sleepSteps[Math.min(sleepStep.value, sleepSteps.length - 1)])
const recallResults = computed(() => recallMode.value === 'active'
  ? [memories.value[3], memories.value[2], memories.value[0]]
  : [memories.value[3], memories.value[4], memories.value[2]])

function startSleep() {
  if (sleeping.value) return
  sleeping.value = true; sleepStep.value = 0
  clearInterval(sleepTimer)
  sleepTimer = setInterval(() => {
    sleepStep.value += 1
    if (sleepStep.value >= sleepSteps.length) {
      clearInterval(sleepTimer); sleeping.value = false
      const old = memories.value.find(m => m.id === 5)
      old.phase = 'expired'; old.expiry = '被新事实覆盖'; old.confidence = .42
      const candidate = memories.value.find(m => m.id === 2)
      candidate.phase = 'consolidated'; candidate.confidence = .86; candidate.source = '值班备注 + 变更日历'
    }
  }, 850)
}
function resetLab() {
  clearInterval(sleepTimer); sleeping.value = false; sleepStep.value = 0
  const old = memories.value.find(m => m.id === 5); old.phase='conflict';old.expiry='冲突中';old.confidence=.90
  const candidate=memories.value.find(m=>m.id===2);candidate.phase='candidate';candidate.confidence=.52;candidate.source='值班备注'
}
</script>

<template>
  <div class="memory-view">
    <div class="hero-row">
      <div><p class="eyebrow">记忆生命周期概念验证</p><h2 class="section-heading">Memory 不等于“把聊天存下来”</h2><p class="section-copy">有价值的运维事实需要经历候选、验证、冲突治理和过期。运行一次“睡眠整理”，观察旧端口事实如何被新事实覆盖，并退出默认主动召回。</p></div>
      <div class="hero-actions"><span class="tag good">时序 MRR 0.750 → 1.000</span><button class="button" @click="resetLab"><Icon name="reset" :size="14"/>重置</button><button class="button primary" :disabled="sleeping" @click="startSleep"><Icon name="moon" :size="15"/>{{ sleeping ? '整理中…' : '开始睡眠整理' }}</button></div>
    </div>

    <div class="memory-layout">
      <section class="panel lifecycle-panel">
        <header class="panel-header"><div class="panel-title"><Icon name="memory" :size="18"/><div><h3>记忆生命周期</h3><p>候选、巩固、冲突与过期</p></div></div><span class="tag warn">设计原型</span></header>
        <div class="phase-grid">
          <article v-for="(phase,index) in phases" :key="phase.id" class="phase-column" :class="phase.id">
            <header><span>{{ String(index+1).padStart(2,'0') }}</span><div><b>{{ phase.name }}</b><small>{{ phase.note }}</small></div><em>{{ counts[phase.id] }}</em></header>
            <button v-for="memory in memories.filter(m=>m.phase===phase.id)" :key="memory.id" class="memory-card" :class="{active:activeMemory.id===memory.id}" @click="activeMemory=memory">
              <div class="memory-top"><span>{{ memory.source }}</span><b>{{ Math.round(memory.confidence*100) }}%</b></div><h4>{{ memory.title }}</h4><p>{{ memory.fact }}</p><div class="memory-meta"><span>年龄 {{ memory.age }}</span><span>TTL {{ memory.expiry }}</span></div>
            </button>
            <div class="phase-action"><span>{{ phase.verb }}</span><Icon v-if="index<3" name="chevron" :size="13"/></div>
          </article>
        </div>

        <div class="sleep-console" :class="{running:sleeping,done:!sleeping&&sleepStep>=sleepSteps.length}">
          <div class="sleep-visual"><Icon name="moon" :size="23"/></div>
          <div class="sleep-state"><p>{{ sleeping ? `整理步骤 ${sleepStep+1} / ${sleepSteps.length}` : sleepStep>=sleepSteps.length ? '整理完成' : '等待夜间整理' }}</p><h3>{{ sleeping ? currentSleepText : sleepStep>=sleepSteps.length ? '旧事实已降权，候选事实已巩固' : '等待运行睡眠整理' }}</h3><div class="progress-track"><div class="progress-fill" :style="{width:(sleepStep/sleepSteps.length*100)+'%'}"></div></div></div>
          <div class="sleep-stats"><span><b>{{ sleepStep>=3?1:0 }}</b>冲突裁决</span><span><b>{{ sleepStep>=5?1:0 }}</b>候选巩固</span><span><b>{{ sleepStep>=5?2:0 }}</b>索引更新</span></div>
        </div>
      </section>

      <aside class="right-stack">
        <section class="panel memory-inspector">
          <header class="panel-header"><div class="panel-title"><div><h3>记忆检查器</h3><p>来源、时效与有效性</p></div></div></header>
          <div class="memory-detail"><span class="tag" :class="activeMemory.phase==='conflict'?'warn':activeMemory.phase==='expired'?'bad':'good'">{{ phases.find(p=>p.id===activeMemory.phase).name }}</span><h3>{{ activeMemory.title }}</h3><blockquote>{{ activeMemory.fact }}</blockquote><dl><div><dt>来源</dt><dd>{{ activeMemory.source }}</dd></div><div><dt>置信度</dt><dd>{{ Math.round(activeMemory.confidence*100) }}%</dd></div><div><dt>时效</dt><dd>{{ activeMemory.expiry }}</dd></div><div><dt>实体</dt><dd>host-group:prod</dd></div></dl><div class="provenance-chain"><span>原始事件</span><Icon name="chevron" :size="12"/><span>事实抽取</span><Icon name="chevron" :size="12"/><span>验证门</span></div></div>
        </section>
        <section class="panel recall-panel">
          <header class="panel-header"><div class="panel-title"><div><h3>召回模拟</h3><p>主动召回与被动查询</p></div></div><div class="segmented"><button :class="{active:recallMode==='active'}" @click="recallMode='active'">主动</button><button :class="{active:recallMode==='passive'}" @click="recallMode='passive'">被动</button></div></header>
          <div class="recall-query"><Icon name="spark" :size="15"/><span>“准备修改生产区 SSH 配置”</span></div>
          <div class="recall-mode-note">{{ recallMode==='active'?'系统在规划前主动注入高价值、有效事实':'操作者显式打开历史与冲突证据' }}</div>
          <div class="recall-results"><div v-for="(memory,index) in recallResults" :key="memory.id" :class="{suppressed:memory.phase==='conflict'||memory.phase==='expired'}"><b>#{{ index+1 }}</b><span><strong>{{ memory.title }}</strong><small>{{ memory.fact }}</small></span><em>{{ memory.phase==='conflict'?'冲突标记':Math.round(memory.confidence*100)+'%' }}</em></div></div>
          <p class="benchmark-caveat"><Icon name="shield" :size="13"/>Benchmark 结论：时近/重要度对时序更新有收益；图增强全集 MRR 0.892，需按查询路由，不能无条件开启。</p>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.sleep-console{background:var(--panel-strong)!important}
.sleep-visual{border-radius:8px!important}
.sleep-orbit{display:none!important}
.memory-card{background:var(--panel-strong)!important}
.memory-layout{display:grid;grid-template-columns:minmax(0,1.55fr) minmax(310px,.72fr);gap:14px}.phase-grid{display:grid;grid-template-columns:repeat(4,1fr);min-height:430px}.phase-column{position:relative;padding:12px;border-right:1px solid var(--line)}.phase-column:last-child{border-right:0}.phase-column>header{display:grid;grid-template-columns:22px 1fr auto;gap:7px;align-items:start;margin-bottom:12px}.phase-column>header>span{color:var(--faint);font:8px 'DM Mono'}.phase-column>header b,.phase-column>header small{display:block}.phase-column>header b{font-size:11px}.phase-column>header small{margin-top:3px;color:var(--faint);font-size:7px;line-height:1.5}.phase-column>header em{width:21px;height:21px;display:grid;place-items:center;border:1px solid var(--line);border-radius:50%;color:var(--muted);font:8px 'DM Mono';font-style:normal}.memory-card{width:100%;padding:10px;margin-bottom:8px;border:1px solid var(--line);border-radius:9px;background:rgba(7,19,16,.62);text-align:left;cursor:pointer}.memory-card:hover,.memory-card.active{border-color:rgba(119,242,182,.38);background:rgba(119,242,182,.05)}.conflict .memory-card{border-color:rgba(241,192,106,.22)}.expired .memory-card{opacity:.5}.memory-top,.memory-meta{display:flex;justify-content:space-between;gap:8px}.memory-top span,.memory-top b,.memory-meta span{font:6px 'DM Mono'}.memory-top span,.memory-meta{color:var(--faint)}.memory-top b{color:var(--mint)}.memory-card h4{margin:8px 0 4px;font-size:9px;font-weight:500}.memory-card p{margin:0;color:var(--muted);font-size:8px;line-height:1.5}.memory-meta{margin-top:9px}.phase-action{position:absolute;left:12px;right:12px;bottom:12px;display:flex;justify-content:center;align-items:center;gap:5px;color:var(--faint);font:7px 'DM Mono'}.sleep-console{display:grid;grid-template-columns:70px 1fr auto;align-items:center;gap:12px;min-height:96px;padding:14px 18px;border-top:1px solid var(--line);background:linear-gradient(90deg,rgba(185,160,255,.05),rgba(119,242,182,.03))}.sleep-visual{position:relative;width:50px;height:50px;display:grid;place-items:center;border:1px solid rgba(185,160,255,.28);border-radius:50%;color:var(--purple)}.sleep-orbit{position:absolute;inset:-7px;border:1px solid rgba(185,160,255,.16);border-radius:50%}.sleep-console.running .o1{animation:pulse-ring 1.5s infinite}.sleep-console.running .o2{animation:pulse-ring 1.5s .75s infinite}.sleep-state p{margin:0 0 4px;color:var(--purple);font:7px 'DM Mono';letter-spacing:.1em}.sleep-state h3{margin:0 0 10px;font-size:11px}.sleep-stats{display:flex;gap:7px}.sleep-stats span{min-width:65px;padding:8px;border:1px solid var(--line);border-radius:7px;color:var(--faint);font-size:7px;text-align:center}.sleep-stats b{display:block;margin-bottom:2px;color:var(--text);font:14px 'DM Mono'}.right-stack{display:flex;flex-direction:column;gap:14px}.memory-detail{padding:16px}.memory-detail h3{margin:12px 0 9px;font-size:15px}.memory-detail blockquote{margin:0;padding:12px;border-left:2px solid var(--mint);background:rgba(119,242,182,.045);color:var(--muted);font-size:10px}.memory-detail dl{margin:12px 0;border-top:1px solid var(--line)}.memory-detail dl>div{display:flex;justify-content:space-between;padding:9px 0;border-bottom:1px solid var(--line)}.memory-detail dt{color:var(--faint);font-size:8px}.memory-detail dd{margin:0;color:var(--muted);font:8px 'DM Mono';text-align:right}.provenance-chain{display:flex;align-items:center;justify-content:space-between;color:var(--faint);font-size:7px}.provenance-chain span{padding:5px;border:1px solid var(--line);border-radius:5px}.recall-panel{flex:1}.recall-query{display:flex;align-items:center;gap:8px;margin:14px 14px 7px;padding:10px;border:1px solid rgba(119,242,182,.25);border-radius:8px;color:var(--mint);background:rgba(119,242,182,.05);font-size:9px}.recall-mode-note{margin:0 14px 10px;color:var(--faint);font-size:7px}.recall-results{padding:0 14px}.recall-results>div{display:grid;grid-template-columns:25px 1fr auto;gap:8px;padding:9px 0;border-bottom:1px solid var(--line)}.recall-results>div>b{color:var(--faint);font:7px 'DM Mono'}.recall-results strong,.recall-results small{display:block}.recall-results strong{font-size:8px}.recall-results small{margin-top:3px;color:var(--faint);font-size:7px}.recall-results em{color:var(--mint);font:7px 'DM Mono';font-style:normal}.recall-results .suppressed{opacity:.55}.recall-results .suppressed em{color:var(--amber)}.benchmark-caveat{display:flex;gap:6px;margin:12px 14px;padding:10px;border:1px solid rgba(241,192,106,.18);border-radius:8px;color:var(--muted);background:rgba(241,192,106,.035);font-size:7px;line-height:1.6}
@media(max-width:1150px){.memory-layout{grid-template-columns:1fr}.right-stack{display:grid;grid-template-columns:1fr 1fr}}
</style>
