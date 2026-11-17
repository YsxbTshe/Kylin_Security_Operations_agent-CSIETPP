<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import Icon from '../components/Icon.vue'

const acts = [
  { id: 'situation', index: '01', name: '安全态势', subtitle: '看见边界与风险', duration: '50 秒' },
  { id: 'mission', index: '02', name: '任务直播', subtitle: '看见受控执行', duration: '80 秒' },
  { id: 'benchmark', index: '03', name: '证据结论', subtitle: '看见真实增益', duration: '50 秒' },
]
const act = ref('situation')
const source = ref('synthetic')
const presenter = ref(false)
const freshness = ref(4)
let clock = setInterval(() => { freshness.value = freshness.value >= 29 ? 1 : freshness.value + 1 }, 1000)
onBeforeUnmount(() => clearInterval(clock))
const current = computed(() => acts.find((item) => item.id === act.value))

const ragBars = [
  { name: '向量', recall: .668, ndcg: .669, p95: 213 },
  { name: '关键词', recall: .368, ndcg: .373, p95: 222 },
  { name: '向量 + 关键词', recall: .758, ndcg: .729, p95: 208, best: true },
  { name: '+ 图通道', recall: .761, ndcg: .725, p95: 288 },
]
</script>

<template>
  <div class="big-screen-view" :class="{ presenter }">
    <div class="hero-row screen-intro">
      <div><p class="eyebrow">三幕演示视图</p><h2 class="section-heading">一块服务讲述的大屏，而不是指标墙</h2><p class="section-copy">三幕依次回答：系统守护什么、任务如何受控、效果如何被证据支持。演示视图聚焦任务状态、处置结果与证据。</p></div>
      <div class="hero-actions">
        <div class="segmented source-toggle"><button :class="{active:source==='synthetic'}" @click="source='synthetic'"><span class="live-dot"></span>合成演示流</button><button :class="{active:source==='replay'}" @click="source='replay'">回放接入预览</button></div>
        <button class="button" :class="{active:presenter}" @click="presenter=!presenter"><Icon name="screen" :size="15" />{{ presenter ? '退出演示模式' : '演示模式' }}</button>
      </div>
    </div>

    <div class="act-strip">
      <button v-for="item in acts" :key="item.id" :class="{active:act===item.id}" @click="act=item.id"><span>{{ item.index }}</span><div><b>{{ item.name }}</b><small>{{ item.subtitle }} · {{ item.duration }}</small></div></button>
      <div class="source-state" :class="source"><b>{{ source === 'synthetic' ? '合成演示流' : '回放接入预览' }}</b><small>{{ source === 'synthetic' ? `模拟聚合刷新 ${freshness}s 前 · 模拟任务事件` : '当前无真实 run · 待接入真机证据包' }}</small></div>
    </div>

    <section v-if="act==='situation'" class="screen-canvas situation-act">
      <header class="screen-header"><div><p>麒麟智能安全运维中心</p><h3>全域安全态势</h3></div><span class="tag warn">概念演示数据</span></header>
      <div class="situation-grid">
        <div class="posture-score"><div class="score-orbit"><svg viewBox="0 0 120 120"><circle cx="60" cy="60" r="48"/><circle class="score-arc" cx="60" cy="60" r="48"/></svg><div><b>86</b><small>安全态势</small></div></div><p>当前演示环境整体可控<br/><span>2 项需要关注</span></p></div>
        <div class="guard-map">
          <div class="map-label">三层护栏与决策路径</div>
          <svg viewBox="0 0 680 280" role="img" aria-label="平台策略、人工审批和端侧校验三层护栏路径">
            <defs><linearGradient id="flow-grad" x1="0" x2="1"><stop stop-color="#77f2b6"/><stop offset="1" stop-color="#d7fb65"/></linearGradient></defs>
            <path class="flow-path" d="M72 140 C150 55 215 55 290 140 S430 225 510 140 S595 72 650 140" />
            <g class="guard-node" transform="translate(65 140)"><circle r="34"/><text y="4">意图</text></g>
            <g class="guard-node primary" transform="translate(230 98)"><circle r="44"/><text y="-5">平台策略</text><text class="small" y="13">R0–R5</text></g>
            <g class="guard-node warning" transform="translate(420 182)"><circle r="44"/><text y="-5">职责分离</text><text class="small" y="13">人工审批</text></g>
            <g class="guard-node primary" transform="translate(590 112)"><circle r="44"/><text y="-5">Host</text><text class="small" y="13">最终拒绝</text></g>
          </svg>
          <div class="guard-note"><span><Icon name="shield" :size="14" />LLM 无权越过硬策略</span><span><Icon name="terminal" :size="14" />端侧无通用 Shell</span><span><Icon name="database" :size="14" />SM3 审计链</span></div>
        </div>
        <div class="situation-metrics">
          <article><p>安全门禁</p><b>20 / 20</b><span>固定恶意样例拦截</span><small>backend-only 测试集</small></article>
          <article><p>良性放行</p><b>6 / 6</b><span>当前样例正确放行</span><small>不代表开放世界 100%</small></article>
          <article><p>工具边界</p><b>41</b><span>31 只读 · 10 写</span><small>manifest 口径</small></article>
        </div>
      </div>
    </section>

    <section v-else-if="act==='mission'" class="screen-canvas mission-act">
      <header class="screen-header"><div><p>模拟任务执行</p><h3>SSH 安全基线强化</h3></div><div class="mission-status">任务事件推进中</div></header>
      <div class="mission-grid">
        <div class="fleet-visual">
          <div class="pulse-core"><Icon name="shield" :size="30" /><b>安全执行</b></div>
          <svg viewBox="0 0 720 340" aria-hidden="true"><g class="links"><path v-for="i in 12" :key="i" :d="`M360 170 L${60 + ((i-1)%6)*120} ${i>6?285:55}`" /></g></svg>
          <div v-for="i in 12" :key="i" class="fleet-node" :class="{running:[3,5,8,10].includes(i)}" :style="{left:`${5+((i-1)%6)*17}%`,top:i>6?'78%':'10%'}"><Icon name="database" :size="15"/><span>node-{{ String(i).padStart(2,'0') }}</span></div>
        </div>
        <div class="mission-rail">
          <div class="live-step done"><span><Icon name="check" :size="13"/></span><div><b>策略校验通过</b><small>R3 · 意图对齐 · 可回滚</small></div><time>12 ms</time></div>
          <div class="live-step done"><span><Icon name="check" :size="13"/></span><div><b>职责分离审批</b><small>HMAC-SM3 上下文绑定</small></div><time>8.1 s</time></div>
          <div class="live-step active"><span></span><div><b>灰度执行与验证</b><small>模拟批次 02 / 06</small></div><time>模拟</time></div>
          <div class="live-step"><span></span><div><b>全量执行</b><small>按故障域分批</small></div><time>—</time></div>
          <div class="live-step"><span></span><div><b>证据封存</b><small>SM3 hash chain</small></div><time>—</time></div>
          <div class="mission-proof"><p>可信完成五证</p><div><span class="done">策略</span><span class="done">审批</span><span class="active">端侧</span><span>验证</span><span>审计</span></div></div>
        </div>
      </div>
    </section>

    <section v-else class="screen-canvas benchmark-act">
      <header class="screen-header"><div><p>可重复基准 · 2026-08-05</p><h3>用限定结论证明真实价值</h3></div><span class="tag good">原始 JSON 可复查</span></header>
      <div class="benchmark-grid">
        <article class="benchmark-card rag-card">
          <div class="benchmark-title"><span>RAG 混合检索</span><b>Recall@10</b></div>
          <div v-for="bar in ragBars" :key="bar.name" class="bench-row" :class="{best:bar.best}"><label>{{ bar.name }}</label><div class="bar-track"><span :style="{width:(bar.recall*100)+'%'}"></span></div><b>{{ bar.recall.toFixed(3) }}</b><small>nDCG {{ bar.ndcg.toFixed(3) }} · P95 {{ bar.p95 }}ms</small></div>
          <p><Icon name="spark" :size="14" /> 当前数据上“向量 + 关键词”明显优于单路；图通道用于关系解释与多跳，不声称全面提准。</p>
        </article>
        <article class="benchmark-card memory-card">
          <div class="benchmark-title"><span>Memory 时序更新</span><b>MRR@10</b></div>
          <div class="memory-compare"><div><small>纯语义</small><b>0.750</b><i style="height:75%"></i></div><span>→</span><div class="best"><small>时近 + 重要度</small><b>1.000</b><i style="height:100%"></i></div></div>
          <p><Icon name="memory" :size="14" /> 真正有区分力的是“新事实覆盖旧事实”；图增强全集 MRR 0.892，不回避噪声。</p>
        </article>
        <article class="benchmark-card safety-card">
          <div class="benchmark-title"><span>Agent 安全门禁</span><b>32 次评估</b></div>
          <div class="safety-number"><b>0.32<small>ms</small></b><span>平均决策延迟</span></div>
          <div class="safety-facts"><span>P95 <b>0.97 ms</b></span><span>风险升级 <b>3/3</b></span><span>伪令牌 <b>5/5</b></span></div>
          <p><Icon name="shield" :size="14" /> 结果仅对应当前固定 backend-only 测试集，不外推为开放世界绝对安全。</p>
        </article>
      </div>
    </section>

    <footer class="presenter-footer"><span>{{ current.index }} / 03 · {{ current.name }}</span><div class="presenter-progress"><i :style="{width:(Number(current.index)/3*100)+'%'}"></i></div><span>事实基线版本 · 2026-08-05</span></footer>
  </div>
</template>

<style scoped>
.source-state{grid-template-columns:1fr!important}
.big-screen-view.presenter{background:#08090a!important}
.screen-canvas{background:#0b0c0e!important;border-radius:8px!important}
.act-strip>button,.source-state,.situation-metrics article,.benchmark-card,.fleet-node,.pulse-core{background:#151719!important}
.guard-node circle{fill:#17191c!important}
.pulse-core{border-color:var(--line-strong)!important}
.live-step>span{border-radius:5px!important}
.live-step.active>span::before{width:8px!important;height:3px!important;border-radius:1px!important;background:var(--mint)!important}
.act-strip>button.active{background:var(--panel-soft)!important}
.score-arc,.guard-node.primary circle{filter:none!important}
.flow-path,.links path{animation:none!important}
.pulse-core{width:96px!important;height:72px!important;border-radius:8px!important;box-shadow:none!important}
.pulse-core .ring{display:none!important}
.fleet-node.running{box-shadow:none!important}
.live-step.active>span{box-shadow:none!important}
.bench-row.best .bar-track span,.memory-compare .best i{background:var(--mint-2)!important;box-shadow:none!important}
.big-screen-view.presenter{position:relative;margin:-24px -28px -34px -32px;padding:22px 28px 30px 32px;min-height:calc(100vh - 84px);background:#04100d}.presenter .screen-intro{display:none}.presenter .act-strip{margin-top:0}.act-strip{display:grid;grid-template-columns:repeat(3,minmax(150px,1fr)) minmax(210px,.8fr);gap:7px;margin-bottom:12px}.act-strip>button,.source-state{min-height:60px;display:flex;align-items:center;gap:10px;padding:9px 12px;border:1px solid var(--line);border-radius:10px;background:rgba(13,30,25,.64);text-align:left}.act-strip>button{cursor:pointer}.act-strip>button>span{font:10px 'DM Mono';color:var(--faint)}.act-strip>button div{display:flex;flex-direction:column}.act-strip b{font-size:11px}.act-strip small{margin-top:3px;color:var(--faint);font-size:8px}.act-strip>button.active{border-color:rgba(119,242,182,.42);background:linear-gradient(120deg,rgba(119,242,182,.13),rgba(119,242,182,.03))}.act-strip>button.active>span{color:var(--mint)}.source-state{display:grid;grid-template-columns:12px 1fr}.source-state .live-dot{grid-row:1/3}.source-state.live b{color:var(--mint)}.source-state.replay b{color:var(--amber)}.source-state.replay .live-dot{background:var(--amber);box-shadow:0 0 12px rgba(241,192,106,.5);animation:none}.screen-canvas{min-height:590px;border:1px solid var(--line);border-radius:16px;background:radial-gradient(circle at 50% 20%,rgba(45,115,84,.11),transparent 36%),linear-gradient(145deg,#0a1d18,#06110e);overflow:hidden}.screen-header{height:78px;display:flex;align-items:center;justify-content:space-between;padding:0 22px;border-bottom:1px solid var(--line)}.screen-header p{margin:0 0 5px;color:var(--mint);font:9px 'DM Mono';letter-spacing:.13em}.screen-header h3{margin:0;font-size:19px;font-weight:500}.situation-grid{display:grid;grid-template-columns:230px 1fr 240px;min-height:510px}.posture-score,.guard-map,.situation-metrics{padding:22px}.posture-score{display:flex;flex-direction:column;justify-content:center;align-items:center;border-right:1px solid var(--line);text-align:center}.score-orbit{position:relative;width:160px;height:160px}.score-orbit svg{width:100%;transform:rotate(-90deg)}.score-orbit circle{fill:none;stroke:rgba(141,183,160,.12);stroke-width:6}.score-orbit .score-arc{stroke:var(--mint);stroke-dasharray:302;stroke-dashoffset:42;stroke-linecap:round;filter:drop-shadow(0 0 6px rgba(119,242,182,.4))}.score-orbit>div{position:absolute;inset:0;display:grid;place-content:center}.score-orbit b{font:500 46px 'DM Mono'}.score-orbit small{color:var(--faint);font-size:10px}.posture-score p{color:var(--muted);font-size:12px;line-height:1.7}.posture-score p span{color:var(--amber)}.guard-map{display:flex;flex-direction:column;justify-content:center}.map-label{color:var(--faint);font:9px 'DM Mono';letter-spacing:.1em}.guard-map svg{width:100%}.flow-path{fill:none;stroke:url(#flow-grad);stroke-width:2;stroke-dasharray:8 8;animation:flow 1.5s linear infinite}.guard-node circle{fill:#0d211b;stroke:rgba(141,183,160,.3)}.guard-node text{fill:var(--muted);font:11px 'Noto Sans SC',sans-serif;text-anchor:middle}.guard-node .small{fill:var(--faint);font-size:8px}.guard-node.primary circle{stroke:var(--mint);filter:drop-shadow(0 0 8px rgba(119,242,182,.2))}.guard-node.warning circle{stroke:var(--amber)}.guard-note{display:flex;justify-content:center;flex-wrap:wrap;gap:9px}.guard-note span{display:flex;align-items:center;gap:5px;padding:5px 7px;border:1px solid var(--line);border-radius:7px;color:var(--muted);font-size:8px}.situation-metrics{display:flex;flex-direction:column;gap:8px;border-left:1px solid var(--line)}.situation-metrics article{flex:1;padding:15px;border:1px solid var(--line);border-radius:11px;background:rgba(8,24,19,.68)}.situation-metrics p{margin:0;color:var(--faint);font-size:9px}.situation-metrics b{display:block;margin:7px 0 3px;font:500 24px 'DM Mono'}.situation-metrics span,.situation-metrics small{display:block}.situation-metrics span{color:var(--muted);font-size:9px}.situation-metrics small{margin-top:7px;color:var(--faint);font-size:7px}.mission-status{color:var(--mint);font-size:10px}.mission-grid{display:grid;grid-template-columns:minmax(0,1.4fr) 340px;min-height:510px}.fleet-visual{position:relative;border-right:1px solid var(--line);overflow:hidden}.fleet-visual>svg{position:absolute;inset:0;width:100%;height:100%}.links path{stroke:rgba(119,242,182,.22);stroke-width:1;stroke-dasharray:5 8;animation:flow 2s linear infinite}.pulse-core{position:absolute;z-index:2;left:50%;top:50%;width:110px;height:110px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:5px;transform:translate(-50%,-50%);border:1px solid rgba(119,242,182,.5);border-radius:50%;color:var(--mint);background:#0b211a;box-shadow:0 0 70px rgba(119,242,182,.1)}.pulse-core b{font:9px 'DM Mono';letter-spacing:.1em}.pulse-core .ring{position:absolute;inset:-25px;border:1px solid rgba(119,242,182,.2);border-radius:50%;animation:pulse-ring 2.5s infinite}.pulse-core .r2{animation-delay:1.25s}.fleet-node{position:absolute;z-index:3;transform:translateX(-50%);width:72px;padding:7px 4px;display:flex;flex-direction:column;align-items:center;gap:4px;border:1px solid var(--line);border-radius:8px;color:var(--faint);background:#0b1e18;font:7px 'DM Mono'}.fleet-node.running{color:var(--acid);border-color:rgba(215,251,101,.5);box-shadow:0 0 18px rgba(215,251,101,.08)}.mission-rail{padding:22px}.live-step{display:grid;grid-template-columns:28px 1fr auto;gap:10px;align-items:center;min-height:65px;position:relative}.live-step:not(:last-of-type)::after{content:'';position:absolute;left:13px;top:47px;width:1px;height:36px;background:var(--line)}.live-step>span{width:27px;height:27px;display:grid;place-items:center;border:1px solid var(--line);border-radius:50%}.live-step.done>span{color:#07110e;border-color:var(--mint);background:var(--mint)}.live-step.active>span{border-color:var(--acid);box-shadow:0 0 0 5px rgba(215,251,101,.07)}.live-step.active>span::before{content:'';width:6px;height:6px;border-radius:50%;background:var(--acid)}.live-step b,.live-step small{display:block}.live-step b{font-size:10px}.live-step small{margin-top:3px;color:var(--faint);font-size:8px}.live-step time{color:var(--faint);font:8px 'DM Mono'}.live-step.active time{color:var(--acid)}.mission-proof{margin-top:15px;padding:13px;border:1px solid var(--line);border-radius:10px;background:rgba(119,242,182,.035)}.mission-proof p{margin:0 0 9px;font-size:9px}.mission-proof div{display:grid;grid-template-columns:repeat(5,1fr);gap:4px}.mission-proof span{padding:5px 2px;border:1px solid var(--line);border-radius:5px;color:var(--faint);font-size:7px;text-align:center}.mission-proof span.done{color:var(--mint);border-color:rgba(119,242,182,.3)}.mission-proof span.active{color:var(--acid);border-color:var(--acid)}.benchmark-grid{display:grid;grid-template-columns:1.25fr .9fr .9fr;gap:10px;padding:18px;min-height:510px}.benchmark-card{padding:18px;border:1px solid var(--line);border-radius:12px;background:rgba(10,27,22,.72)}.benchmark-title{display:flex;justify-content:space-between;align-items:center;padding-bottom:14px;margin-bottom:15px;border-bottom:1px solid var(--line)}.benchmark-title span{font-size:11px}.benchmark-title b{color:var(--faint);font:8px 'DM Mono'}.bench-row{display:grid;grid-template-columns:88px 1fr 42px;gap:7px;align-items:center;margin:14px 0}.bench-row label{color:var(--muted);font-size:8px}.bar-track{height:8px;border-radius:99px;background:rgba(141,183,160,.1);overflow:hidden}.bar-track span{display:block;height:100%;border-radius:inherit;background:var(--faint)}.bench-row.best .bar-track span{background:linear-gradient(90deg,var(--mint-2),var(--acid));box-shadow:0 0 12px rgba(119,242,182,.25)}.bench-row>b{font:9px 'DM Mono';text-align:right}.bench-row small{grid-column:2/4;color:var(--faint);font:7px 'DM Mono'}.benchmark-card>p{display:flex;align-items:flex-start;gap:7px;margin:20px 0 0;padding-top:14px;border-top:1px solid var(--line);color:var(--muted);font-size:9px;line-height:1.65}.memory-compare{height:240px;display:flex;align-items:end;justify-content:center;gap:22px;padding:20px 0 0}.memory-compare>div{position:relative;width:75px;height:100%;display:flex;flex-direction:column;justify-content:end;text-align:center}.memory-compare i{display:block;width:42px;margin:8px auto 0;border-radius:6px 6px 0 0;background:rgba(138,167,154,.25)}.memory-compare .best i{background:linear-gradient(var(--mint),var(--mint-2))}.memory-compare b{font:500 18px 'DM Mono'}.memory-compare small{color:var(--faint);font-size:7px}.memory-compare>span{align-self:center;color:var(--mint)}.safety-number{height:190px;display:flex;flex-direction:column;justify-content:center;align-items:center}.safety-number>b{font:500 43px 'DM Mono';color:var(--mint)}.safety-number small{font-size:14px}.safety-number span{color:var(--faint);font-size:9px}.safety-facts{display:grid;gap:6px}.safety-facts span{display:flex;justify-content:space-between;padding:7px;border:1px solid var(--line);border-radius:6px;color:var(--faint);font-size:8px}.safety-facts b{color:var(--muted);font-family:'DM Mono'}.presenter-footer{display:flex;align-items:center;gap:12px;margin-top:10px;color:var(--faint);font:8px 'DM Mono'}.presenter-footer>span:last-child{margin-left:auto}.presenter-progress{flex:1;max-width:220px;height:2px;background:var(--line)}.presenter-progress i{display:block;height:100%;background:var(--mint);transition:.3s}.source-toggle .live-dot{width:5px;height:5px}
@media(max-width:1100px){.situation-grid{grid-template-columns:200px 1fr}.situation-metrics{grid-column:1/-1;flex-direction:row;border-left:0;border-top:1px solid var(--line)}.mission-grid{grid-template-columns:1fr}.fleet-visual{min-height:420px;border-right:0;border-bottom:1px solid var(--line)}.benchmark-grid{grid-template-columns:1fr}.benchmark-card{min-height:340px}.screen-canvas{min-height:auto}}
.presenter .screen-intro{display:flex;margin:0 0 12px;justify-content:flex-end}
.presenter .screen-intro>div:first-child{display:none}
</style>
