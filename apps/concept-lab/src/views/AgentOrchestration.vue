<script setup>
import { computed, ref } from 'vue'
import Icon from '../components/Icon.vue'

const scenarios = [
  { id: 'incident', name: '异常登录响应', skill: 'auth-incident-response', version: '1.1.0', risk: 'R3' },
  { id: 'ssh', name: 'SSH 基线强化', skill: 'ssh-baseline-remediation', version: '1.2.0', risk: 'R3' },
  { id: 'diagnosis', name: '服务异常诊断', skill: 'service-failure-diagnosis', version: '0.9.2', risk: 'R1' },
]

const agents = [
  { id: 'sense', name: '状态感知', role: '并行采集日志、连接与安全姿态', tools: '4 个只读工具', lane: 1 },
  { id: 'knowledge', name: '知识检索', role: '查询路由、版本匹配与引用校验', tools: 'RAG / 图检索', lane: 1 },
  { id: 'safety', name: '安全判定', role: '意图、策略、权限和审批路由', tools: '确定性规则', lane: 1 },
  { id: 'planner', name: '计划合成', role: '汇聚 Artifact，生成 SafeOps DAG', tools: '结构化输出', lane: 2 },
  { id: 'execute', name: '受控执行', role: '仅执行已审批的工具与资源范围', tools: 'Host Agent', lane: 3 },
  { id: 'verify', name: '独立验证', role: '用只读探针验证安全与业务结果', tools: '3 个验证器', lane: 4 },
  { id: 'memory', name: '记忆治理', role: '生成带来源和有效期的候选事实', tools: '候选区写入', lane: 4 },
]

const selectedScenario = ref('incident')
const activeStep = ref(3)
const running = ref(false)
const scenario = computed(() => scenarios.find(item => item.id === selectedScenario.value))

const statuses = computed(() => agents.map((agent, index) => ({
  ...agent,
  state: index < activeStep.value ? 'done' : index === activeStep.value ? 'active' : 'waiting',
})))

function advance() {
  if (activeStep.value >= agents.length - 1) {
    activeStep.value = 0
    return
  }
  activeStep.value += 1
}

function run() {
  running.value = !running.value
  if (running.value) advance()
}
</script>

<template>
  <div class="agent-runtime-view">
    <div class="hero-row">
      <div><p class="eyebrow">Agent Runtime 概念验证</p><h2 class="section-heading">多 Agent 扩大专业分工，不扩大执行权限</h2><p class="section-copy">Skill 固化运维方法，专职 Agent 只接收必要上下文和工具；并行调查汇聚为 SafeOps 计划，Policy、审批和 Host 本地校验仍是不可绕过的强制点。</p></div>
      <div class="hero-actions"><span class="tag good">7 个受限角色</span><span class="tag">并发上限 3</span></div>
    </div>

    <section class="runtime-toolbar panel">
      <label><span>演示场景</span><select v-model="selectedScenario" @change="activeStep=0"><option v-for="item in scenarios" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <div><span>选中 Skill</span><b>{{ scenario.skill }}@{{ scenario.version }}</b></div>
      <div><span>风险上限</span><b>{{ scenario.risk }}</b></div>
      <div><span>运行预算</span><b>12 秒 · 18 次工具调用</b></div>
      <button class="button primary" @click="run"><Icon :name="running?'pause':'play'" :size="14"/>{{ running ? '暂停编排' : '运行下一步' }}</button>
    </section>

    <div class="runtime-grid">
      <section class="panel orchestration-board">
        <header class="panel-header"><div class="panel-title"><Icon name="agent" :size="18"/><div><h3>受控委派状态图</h3><p>只读任务并行，写入动作串行并等待审批</p></div></div><span class="tag warn">演示状态</span></header>
        <div class="orchestrator-row"><article><span class="agent-index">主</span><div><b>编排 Agent</b><small>选择 Skill · 拆分任务 · 控制预算 · 汇总结论</small></div><em>不持有主机写权限</em></article></div>
        <div class="lane-label"><span>并行调查</span><i></i><span>计划与审批</span><i></i><span>受控执行</span><i></i><span>验证与沉淀</span></div>
        <div class="agent-lanes">
          <article v-for="agent in statuses" :key="agent.id" class="agent-card" :class="agent.state" :style="{gridColumn:agent.lane}">
            <header><span class="agent-index">{{ String(agents.indexOf(agent)+1).padStart(2,'0') }}</span><span class="agent-state">{{ agent.state==='done'?'已完成':agent.state==='active'?'执行中':'等待' }}</span></header>
            <b>{{ agent.name }}</b><p>{{ agent.role }}</p><footer>{{ agent.tools }}</footer>
          </article>
        </div>
        <div class="handoff-strip"><span>类型化委派包</span><b>目标</b><b>作用域</b><b>Artifact URI</b><b>允许工具</b><b>预算</b><b>输出 Schema</b><b>停止条件</b></div>
      </section>

      <aside class="runtime-side">
        <section class="panel skill-card">
          <header class="panel-header"><div class="panel-title"><Icon name="spark" :size="17"/><div><h3>Skill 执行契约</h3><p>版本化 Runbook，不是长 Prompt</p></div></div></header>
          <dl><div><dt>触发场景</dt><dd>{{ scenario.name }}</dd></div><div><dt>允许工具</dt><dd>8 / 41</dd></div><div><dt>前置条件</dt><dd>Agent 在线 · 姿态新鲜</dd></div><div><dt>知识版本</dt><dd>Kylin V10 · SOP 2026.08</dd></div><div><dt>验证器</dt><dd>状态差异 · 业务探针 · 审计</dd></div><div><dt>回滚</dt><dd>已定义且执行前创建</dd></div></dl>
          <div class="skill-signature"><Icon name="shield" :size="15"/><span><b>Skill 完整性已校验</b><small>Owner: 安全中心 · SM3 摘要已绑定</small></span></div>
        </section>

        <section class="panel budget-card">
          <header class="panel-header"><div class="panel-title"><div><h3>预算与停止条件</h3><p>防止死循环和无效协商</p></div></div></header>
          <div class="budget-row"><span>关键路径</span><b>7.4 / 12 秒</b></div><div class="progress-track"><div class="progress-fill" style="width:62%"></div></div>
          <div class="budget-row"><span>工具调用</span><b>11 / 18</b></div><div class="progress-track"><div class="progress-fill" style="width:61%"></div></div>
          <ul><li><Icon name="check" :size="12"/>Policy 拒绝立即终止</li><li><Icon name="check" :size="12"/>连续两轮无新证据转人工</li><li><Icon name="check" :size="12"/>Checkpoint 可从审批处恢复</li></ul>
        </section>
      </aside>
    </div>

    <section class="panel runtime-proof">
      <div><span>为什么选择多 Agent</span><b>日志、连接、资产和知识可独立并行调查</b></div><div><span>安全边界</span><b>子 Agent 继承更小的工具与资源范围</b></div><div><span>汇总依据</span><b>只接收通过 Schema 与引用校验的 Artifact</b></div><div><span>验证方式</span><b>单 Agent / Skill / 多 Agent 四组消融</b></div>
    </section>
  </div>
</template>

<style scoped>
.runtime-toolbar{display:grid;grid-template-columns:220px 1.3fr 100px 180px auto;align-items:end;gap:16px;padding:14px 16px;margin-bottom:14px}.runtime-toolbar label,.runtime-toolbar>div{display:flex;flex-direction:column;gap:6px}.runtime-toolbar span{color:var(--faint);font-size:9px}.runtime-toolbar b{font:10px 'DM Mono';font-weight:500}.runtime-toolbar select{height:36px;padding:0 10px;border:1px solid var(--line);border-radius:6px;color:var(--text);background:var(--panel-strong);font-size:11px}.runtime-grid{display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:14px}.orchestration-board{overflow:hidden}.orchestrator-row{display:flex;justify-content:center;padding:17px 20px 12px}.orchestrator-row article{width:430px;display:grid;grid-template-columns:32px 1fr auto;align-items:center;gap:10px;padding:12px 14px;border:1px solid var(--line-strong);border-left:3px solid var(--mint);border-radius:7px;background:var(--panel-strong)}.orchestrator-row b,.orchestrator-row small{display:block}.orchestrator-row b{font-size:11px}.orchestrator-row small{margin-top:3px;color:var(--faint);font-size:8px}.orchestrator-row em{color:var(--faint);font-size:8px;font-style:normal}.agent-index{width:27px;height:27px;display:grid;place-items:center;border:1px solid var(--line);border-radius:5px;color:var(--muted);font:8px 'DM Mono'}.lane-label{display:grid;grid-template-columns:auto 1fr auto 1fr auto 1fr auto;align-items:center;gap:8px;padding:4px 18px 9px;color:var(--faint);font-size:8px}.lane-label i{height:1px;background:var(--line)}.agent-lanes{display:grid;grid-template-columns:repeat(4,1fr);grid-auto-flow:dense;align-items:start;gap:9px;padding:0 16px 16px}.agent-card{min-height:128px;padding:10px;border:1px solid var(--line);border-radius:7px;background:var(--panel-strong);opacity:.48}.agent-card.done{opacity:1}.agent-card.active{opacity:1;border-color:var(--mint);border-left-width:3px}.agent-card header{display:flex;justify-content:space-between;align-items:center}.agent-state{color:var(--faint);font-size:7px}.agent-card.done .agent-state,.agent-card.active .agent-state{color:var(--mint)}.agent-card>b{display:block;margin:10px 0 5px;font-size:10px}.agent-card p{min-height:32px;margin:0;color:var(--muted);font-size:8px;line-height:1.5}.agent-card footer{margin-top:9px;padding-top:7px;border-top:1px solid var(--line);color:var(--faint);font:7px 'DM Mono'}.handoff-strip{display:flex;align-items:center;gap:6px;padding:11px 16px;border-top:1px solid var(--line);background:var(--panel-strong)}.handoff-strip span{margin-right:auto;color:var(--muted);font-size:9px}.handoff-strip b{padding:4px 6px;border:1px solid var(--line);border-radius:4px;color:var(--faint);font-size:7px;font-weight:400}.runtime-side{display:flex;flex-direction:column;gap:14px}.skill-card dl{margin:0;padding:7px 14px}.skill-card dl>div{display:flex;justify-content:space-between;gap:14px;padding:8px 0;border-bottom:1px solid var(--line)}.skill-card dt{color:var(--faint);font-size:8px}.skill-card dd{margin:0;color:var(--muted);font-size:8px;text-align:right}.skill-signature{display:flex;gap:9px;margin:5px 14px 14px;padding:10px;border:1px solid rgba(103,181,139,.25);border-radius:6px;color:var(--mint);background:var(--panel-strong)}.skill-signature b,.skill-signature small{display:block}.skill-signature b{font-size:9px}.skill-signature small{margin-top:3px;color:var(--faint);font-size:7px}.budget-card{padding-bottom:13px}.budget-row{display:flex;justify-content:space-between;margin:12px 14px 6px;color:var(--faint);font-size:8px}.budget-row b{color:var(--muted);font:8px 'DM Mono'}.budget-card .progress-track{margin:0 14px}.budget-card ul{display:grid;gap:7px;margin:13px 14px 0;padding:0;list-style:none}.budget-card li{display:flex;align-items:center;gap:6px;color:var(--muted);font-size:8px}.budget-card li svg{color:var(--mint)}.runtime-proof{display:grid;grid-template-columns:repeat(4,1fr);margin-top:14px}.runtime-proof>div{padding:13px 15px;border-right:1px solid var(--line)}.runtime-proof>div:last-child{border-right:0}.runtime-proof span,.runtime-proof b{display:block}.runtime-proof span{margin-bottom:6px;color:var(--faint);font-size:8px}.runtime-proof b{font-size:9px;line-height:1.5}
@media(max-width:1150px){.runtime-toolbar{grid-template-columns:200px 1fr 100px 170px}.runtime-toolbar .button{grid-column:1/-1;width:180px}.runtime-grid{grid-template-columns:1fr}.runtime-side{display:grid;grid-template-columns:1fr 1fr}}
</style>
