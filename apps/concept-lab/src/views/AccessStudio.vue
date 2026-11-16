<script setup>
import { computed, ref } from 'vue'
import Icon from '../components/Icon.vue'

const principals = [
  { id: 'ops-a', name: '运维工程师 A', role: 'ops-engineer', org: '基础平台部', level: 'L2', mfa: true },
  { id: 'sec-b', name: '安全负责人 B', role: 'security-owner', org: '安全中心', level: 'L3', mfa: true },
  { id: 'intern', name: '值班实习生', role: 'observer', org: '基础平台部', level: 'L1', mfa: false },
  { id: 'robot', name: 'CI 发布身份', role: 'service-account', org: '研发效能部', level: 'L2', mfa: false },
]
const groups = [
  { id: 'prod-core', name: '生产 · 核心业务', tags: ['env:prod','criticality:tier-1','os:kylin'], nodes: 12, owner: '核心平台组' },
  { id: 'prod-data', name: '生产 · 数据服务', tags: ['env:prod','data:sensitive','os:kylin'], nodes: 6, owner: '数据平台组' },
  { id: 'security', name: '安全基础设施', tags: ['env:prod','zone:security','os:kylin'], nodes: 6, owner: '安全中心' },
  { id: 'staging', name: '预发验证区', tags: ['env:staging','criticality:tier-2'], nodes: 9, owner: '研发效能部' },
]
const actions = [
  { id: 'read-status', name: '读取安全状态', risk: 'R1', type: 'read', description: '查询 KYSEC / SELinux / auditd 归一状态' },
  { id: 'restart-service', name: '重启业务服务', risk: 'R2', type: 'write', description: '白名单 systemd 服务可逆操作' },
  { id: 'patch-config', name: '修改 SSH 基线', risk: 'R3', type: 'write', description: '写入配置并重载，必须创建回滚点' },
  { id: 'disable-guard', name: '关闭 Native Guard', risk: 'R5', type: 'critical', description: '破坏端侧最终防线，平台硬拒绝' },
]

const principalId = ref('ops-a')
const groupId = ref('prod-core')
const actionId = ref('patch-config')
const changeWindow = ref(false)
const ticket = ref(true)
const justInTime = ref(true)
const evaluated = ref(true)

const principal = computed(() => principals.find(p => p.id === principalId.value))
const group = computed(() => groups.find(g => g.id === groupId.value))
const action = computed(() => actions.find(a => a.id === actionId.value))
const decision = computed(() => {
  if (action.value.risk === 'R5') return { state: 'deny', title: '硬拒绝', reason: 'R5 动作不允许通过授权放行；Host 仍会再次校验。', policy: 'hard-deny-native-guard' }
  if (principal.value.role === 'observer') return { state: 'deny', title: '拒绝', reason: 'observer 仅拥有只读权限，不能执行写操作。', policy: 'role-capability-boundary' }
  if (action.value.type === 'read') return { state: 'permit', title: '允许', reason: '只读工具且主机组在组织范围内。', policy: 'read-within-org-scope' }
  if (!principal.value.mfa || !ticket.value) return { state: 'deny', title: '拒绝', reason: '写操作缺少 MFA 或有效变更单上下文。', policy: 'write-context-required' }
  if (group.value.id === 'security' && principal.value.role !== 'security-owner') return { state: 'deny', title: '拒绝', reason: '安全基础设施组要求 security-owner 属性。', policy: 'security-zone-owner-only' }
  if (action.value.risk === 'R3' && principal.value.role !== 'security-owner') return { state: 'approval', title: '需职责分离审批', reason: 'R3 生产写操作允许申请，但发起人不能自批。', policy: 'r3-four-eyes' }
  if (!changeWindow.value && group.value.id.startsWith('prod')) return { state: 'approval', title: '需窗口例外审批', reason: '当前不在生产变更窗口，必须记录例外理由。', policy: 'prod-change-window' }
  return { state: 'permit', title: '限时允许', reason: '属性、环境与上下文全部满足，签发一次性 JIT 权限。', policy: 'jit-scoped-permit' }
})

const evaluate = () => { evaluated.value = false; requestAnimationFrame(() => { evaluated.value = true }) }
</script>

<template>
  <div class="access-view">
    <div class="hero-row">
      <div><p class="eyebrow">细粒度权限概念验证</p><h2 class="section-heading">权限不只回答“你是谁”，还要回答“此刻能对什么做什么”</h2><p class="section-copy">当前项目已有 RBAC；这里演示主机组、动作风险、环境与临时上下文如何组成可解释的 ABAC 决策。所有选择都会实时生成策略判定链。</p></div>
      <div class="hero-actions"><span class="tag warn">ABAC 概念验证</span><span class="tag good">RBAC 已有代码实现</span></div>
    </div>

    <div class="access-grid">
      <section class="panel policy-builder">
        <header class="panel-header"><div class="panel-title"><Icon name="access" :size="18"/><div><h3>访问请求构造器</h3><p>主体、资源、动作与上下文</p></div></div></header>
        <div class="builder-body">
          <label class="field"><span><b>主体</b> 谁在发起</span><select v-model="principalId" @change="evaluate"><option v-for="p in principals" :key="p.id" :value="p.id">{{ p.name }}</option></select></label>
          <div class="attribute-chips"><span>role={{ principal.role }}</span><span>org={{ principal.org }}</span><span>clearance={{ principal.level }}</span><span :class="principal.mfa?'ok':'missing'">mfa={{ principal.mfa }}</span></div>
          <div class="relation-line"><span>请求执行</span></div>
          <label class="field"><span><b>动作</b> 做什么</span><select v-model="actionId" @change="evaluate"><option v-for="a in actions" :key="a.id" :value="a.id">{{ a.name }} · {{ a.risk }}</option></select><small>{{ action.description }}</small></label>
          <div class="relation-line"><span>作用于</span></div>
          <label class="field"><span><b>资源</b> 对哪些主机</span><select v-model="groupId" @change="evaluate"><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }} · {{ g.nodes }} 节点</option></select></label>
          <div class="attribute-chips"><span v-for="tag in group.tags" :key="tag">{{ tag }}</span><span>owner={{ group.owner }}</span></div>
          <div class="context-box"><p>临时上下文</p><label><input v-model="ticket" type="checkbox" @change="evaluate"/>有效变更单</label><label><input v-model="changeWindow" type="checkbox" @change="evaluate"/>变更窗口内</label><label><input v-model="justInTime" type="checkbox" @change="evaluate"/>申请 15 分钟 JIT</label></div>
          <button class="button primary evaluate-button" @click="evaluate"><Icon name="play" :size="14"/>运行策略模拟</button>
        </div>
      </section>

      <section class="panel decision-panel">
        <header class="panel-header"><div class="panel-title"><div><h3>策略判定链</h3><p>逐项解释允许或拒绝原因</p></div></div><span class="tag">策略版本 v2026.08</span></header>
        <div class="decision-body" :class="[decision.state,{flash:evaluated}]">
          <div class="decision-hero"><span class="decision-icon"><Icon :name="decision.state==='deny'?'close':decision.state==='approval'?'shield':'check'" :size="28"/></span><div><p>判定结果</p><h3>{{ decision.title }}</h3><span>{{ decision.reason }}</span></div></div>
          <div class="decision-expression mono"><span>permit if</span><b>subject.role ∈ action.allowed_roles</b><b>resource.tags ⊆ subject.scope</b><b>context.ticket.valid = true</b><b>risk &lt; R5</b></div>
          <ol class="trace-list">
            <li class="pass"><span><Icon name="check" :size="12"/></span><div><b>身份属性已验证</b><small>{{ principal.role }} · {{ principal.org }} · {{ principal.mfa ? 'MFA' : 'NO MFA' }}</small></div></li>
            <li :class="action.type==='read'||principal.role!=='observer'?'pass':'fail'"><span><Icon :name="action.type==='read'||principal.role!=='observer'?'check':'close'" :size="12"/></span><div><b>角色能力边界</b><small>{{ action.type }} action · {{ action.risk }}</small></div></li>
            <li :class="group.id!=='security'||principal.role==='security-owner'?'pass':'fail'"><span><Icon :name="group.id!=='security'||principal.role==='security-owner'?'check':'close'" :size="12"/></span><div><b>资源组范围</b><small>{{ group.name }} · {{ group.nodes }} nodes</small></div></li>
            <li :class="ticket?'pass':'fail'"><span><Icon :name="ticket?'check':'close'" :size="12"/></span><div><b>操作上下文</b><small>ticket={{ ticket }} · window={{ changeWindow }} · JIT={{ justInTime }}</small></div></li>
            <li :class="action.risk==='R5'?'fail':action.risk==='R3'&&principal.role!=='security-owner'?'pending':'pass'"><span><Icon :name="action.risk==='R5'?'close':action.risk==='R3'&&principal.role!=='security-owner'?'shield':'check'" :size="12"/></span><div><b>风险与职责分离</b><small>{{ decision.policy }}</small></div></li>
          </ol>
          <div class="decision-output"><p>结构化判定输出</p><pre>{
  "decision": "{{ decision.state }}",
  "policy": "{{ decision.policy }}",
  "scope": "group:{{ group.id }}",
  "ttl": "{{ justInTime && decision.state !== 'deny' ? '15m' : 'none' }}",
  "hostMustRecheck": true
}</pre></div>
        </div>
      </section>

      <section class="panel group-panel">
        <header class="panel-header"><div class="panel-title"><div><h3>主机组与继承边界</h3><p>资源分组及节点范围</p></div></div><button class="button">设计新权限组</button></header>
        <div class="group-list"><button v-for="g in groups" :key="g.id" :class="{active:groupId===g.id}" @click="groupId=g.id;evaluate()"><span class="group-glyph"><Icon name="database" :size="16"/></span><span><b>{{ g.name }}</b><small>{{ g.tags.join(' · ') }}</small></span><strong>{{ g.nodes }}<small>节点</small></strong></button></div>
        <div class="boundary-note"><Icon name="shield" :size="16"/><p><b>不可被授权覆盖的边界</b><span>无通用 shell · sudoers 白名单 · Host 本地最终拒绝。ABAC 只收窄权限，不扩大 Agent 能力边界。</span></p></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.decision-icon{border-radius:6px!important;box-shadow:none!important}
.decision-body.flash{animation:none!important}
.field select,.decision-expression,.decision-output pre{background:#151719!important}
.context-box{background:var(--panel-strong)!important}
.access-grid{display:grid;grid-template-columns:minmax(330px,.9fr) minmax(420px,1.2fr) minmax(260px,.72fr);gap:14px}.builder-body,.decision-body{padding:16px}.field{display:flex;flex-direction:column;gap:7px}.field>span{color:var(--faint);font-size:9px}.field>span b{margin-right:6px;color:var(--mint);font:10px 'DM Mono'}.field select{width:100%;padding:10px;border:1px solid var(--line);border-radius:8px;color:var(--text);background:#0c211a;font-size:11px}.field small{color:var(--faint);font-size:8px}.attribute-chips{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}.attribute-chips span{padding:4px 6px;border:1px solid var(--line);border-radius:5px;color:var(--muted);background:rgba(255,255,255,.015);font:7px 'DM Mono'}.attribute-chips span.ok{color:var(--mint)}.attribute-chips span.missing{color:var(--red)}.relation-line{height:36px;display:grid;place-items:center}.relation-line::before{content:'';position:absolute;width:1px;height:24px;background:var(--line)}.relation-line span{z-index:1;padding:2px 5px;color:var(--faint);background:var(--panel-strong);font:7px 'DM Mono'}.context-box{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:15px;padding:11px;border:1px solid var(--line);border-radius:9px;background:rgba(119,242,182,.025)}.context-box p{grid-column:1/-1;margin:0;color:var(--faint);font:8px 'DM Mono'}.context-box label{display:flex;align-items:center;gap:6px;color:var(--muted);font-size:9px}.context-box input{accent-color:var(--mint)}.evaluate-button{width:100%;margin-top:12px}.decision-body{transition:.25s}.decision-body.flash{animation:decision-flash .4s}.decision-hero{display:flex;align-items:center;gap:13px;padding:14px;border:1px solid var(--line);border-radius:11px;background:rgba(255,255,255,.015)}.decision-icon{width:55px;height:55px;display:grid;place-items:center;border-radius:50%;color:var(--mint);background:rgba(119,242,182,.08);box-shadow:0 0 30px rgba(119,242,182,.05)}.deny .decision-icon{color:var(--red);background:rgba(255,125,115,.08)}.approval .decision-icon{color:var(--amber);background:rgba(241,192,106,.08)}.decision-hero p{margin:0;color:var(--faint);font:7px 'DM Mono';letter-spacing:.12em}.decision-hero h3{margin:3px 0;font-size:17px}.permit .decision-hero h3{color:var(--mint)}.deny .decision-hero h3{color:var(--red)}.approval .decision-hero h3{color:var(--amber)}.decision-hero div>span{color:var(--muted);font-size:9px}.decision-expression{display:flex;flex-direction:column;gap:5px;margin:12px 0;padding:11px;border:1px solid var(--line);border-radius:8px;background:#050e0b;font-size:8px}.decision-expression span{margin-bottom:2px;color:var(--purple)}.decision-expression b{color:var(--muted);font-weight:400}.trace-list{padding:0;margin:0;list-style:none}.trace-list li{position:relative;display:grid;grid-template-columns:26px 1fr;gap:9px;align-items:center;min-height:47px}.trace-list li:not(:last-child)::after{content:'';position:absolute;left:12px;top:36px;width:1px;height:23px;background:var(--line)}.trace-list li>span{width:25px;height:25px;display:grid;place-items:center;border:1px solid var(--line);border-radius:50%;color:var(--faint)}.trace-list li.pass>span{color:var(--mint);border-color:rgba(119,242,182,.3)}.trace-list li.fail>span{color:var(--red);border-color:rgba(255,125,115,.3)}.trace-list li.pending>span{color:var(--amber);border-color:rgba(241,192,106,.3)}.trace-list b,.trace-list small{display:block}.trace-list b{font-size:9px}.trace-list small{margin-top:2px;color:var(--faint);font:7px 'DM Mono'}.decision-output{margin-top:12px;overflow:hidden;border:1px solid var(--line);border-radius:8px}.decision-output p{margin:0;padding:7px 9px;border-bottom:1px solid var(--line);color:var(--faint);font-size:8px}.decision-output pre{margin:0;padding:10px;color:var(--muted);background:#040d0a;font:8px/1.65 'DM Mono';white-space:pre-wrap}.group-list{padding:8px}.group-list button{width:100%;display:grid;grid-template-columns:34px 1fr auto;align-items:center;gap:8px;padding:10px 8px;border:0;border-bottom:1px solid var(--line);background:transparent;text-align:left;cursor:pointer}.group-list button.active{border-radius:8px;background:rgba(119,242,182,.07)}.group-glyph{width:31px;height:31px;display:grid;place-items:center;border:1px solid var(--line);border-radius:8px;color:var(--faint)}.group-list .active .group-glyph{color:var(--mint);border-color:rgba(119,242,182,.25)}.group-list b,.group-list small{display:block}.group-list b{font-size:9px}.group-list span small{margin-top:3px;color:var(--faint);font:6px 'DM Mono'}.group-list strong{font:500 16px 'DM Mono';text-align:right}.group-list strong small{color:var(--faint);font-size:7px;font-weight:400}.boundary-note{display:flex;gap:8px;margin:8px 12px 14px;padding:11px;border:1px solid rgba(241,192,106,.2);border-radius:9px;color:var(--amber);background:rgba(241,192,106,.045)}.boundary-note p{margin:0}.boundary-note b,.boundary-note span{display:block}.boundary-note b{font-size:9px}.boundary-note span{margin-top:4px;color:var(--muted);font-size:8px;line-height:1.6}@keyframes decision-flash{50%{filter:brightness(1.2)}}
@media(max-width:1180px){.access-grid{grid-template-columns:1fr 1.25fr}.group-panel{grid-column:1/-1}.group-list{display:grid;grid-template-columns:1fr 1fr}}
</style>
