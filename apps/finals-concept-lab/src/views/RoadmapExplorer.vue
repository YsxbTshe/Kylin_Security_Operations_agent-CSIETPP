<script setup>
import { computed, ref } from 'vue'
import Icon from '../components/Icon.vue'

const modes = {
  robust: { name: '稳健', label: '主线闭环', note: '每周都形成可演示、可回退的交付面', color: 'mint' },
  ambitious: { name: '进取', label: '体验跃迁', note: '在主线之上并行推进高辨识度重构', color: 'amber' },
  extreme: { name: '极限', label: '能力上限', note: '保留所有高上限方向，由团队最终取舍', color: 'purple' },
}

const items = [
  { week: 1, lane: '体验主线', title: 'Task Workspace 信息架构', desc: '统一问答页与控制台详情；九阶段、证据检查器、原始审计。', mode: 'robust', proof: '任务 fixture + 关键路径录屏', owner: 'FE × Agent' },
  { week: 1, lane: '安全主线', title: '决赛口径与证据基线冻结', desc: '全部数字、状态、演示数据、失败降级与 Q&A 绑定证据。', mode: 'robust', proof: '事实索引逐条签收', owner: '全组' },
  { week: 1, lane: 'Agent 架构', title: 'Skill 与委派契约', desc: '定义 Skill Manifest、Agent Profile、类型化 Handoff、Artifact、预算和停止条件。', mode: 'extreme', proof: '2 个 Skill + 契约静态检查', owner: 'Agent × Security' },
  { week: 1, lane: '平台扩展', title: 'DevOps 变更对象接入', desc: '把发布、回滚、配置漂移映射为 SafeOps DAG 与审批上下文。', mode: 'ambitious', proof: 'Git → 计划 → 灰度 → 验证闭环', owner: 'BE × Agent' },
  { week: 1, lane: '安全纵深', title: 'Native Guard A/B 方案', desc: 'KYSEC / SELinux / auditd 状态归一、端侧拒绝与 systemd 沙箱对照。', mode: 'extreme', proof: '麒麟真机拒绝事件', owner: 'Host × Security' },
  { week: 2, lane: '体验主线', title: 'Presenter Mode 三幕编排', desc: '安全态势 → 任务直播 → Benchmark，采集新鲜度与回放水印。', mode: 'robust', proof: '3 分钟定时彩排', owner: 'FE × Demo' },
  { week: 2, lane: '可视化', title: '3D 逻辑拓扑与 2D 同源降级', desc: '点击实例、任务脉冲、来源/时间/可信度；接入资产字段后升级物理拓扑。', mode: 'ambitious', proof: 'WebGL/2D 一致性 + FPS', owner: 'FE × CMDB' },
  { week: 2, lane: '权限模型', title: '主机组 + 结构化 ABAC', desc: '主体、资源、动作、上下文与 JIT；Host 最终边界不可覆盖。', mode: 'extreme', proof: '允许/拒绝/过期测试矩阵', owner: 'IAM × Host' },
  { week: 2, lane: '检索增强', title: '分场景混合检索路由', desc: '语义+关键词默认；图通道仅为多跳/关系问题启用并展示解释路径。', mode: 'extreme', proof: '分桶 Recall/nDCG/延迟', owner: 'RAG × Eval' },
  { week: 2, lane: 'Agent 架构', title: '受限多 Agent 纵切', desc: '感知与知识并行，安全、执行、验证由确定性强制点治理；支持暂停与汇聚。', mode: 'extreme', proof: 'Handoff Trace + 越权拒绝', owner: 'Agent × Host' },
  { week: 3, lane: '真机证据', title: '干净麒麟快照 E2E × 3', desc: '保存版本、commit、run ID、事件包与录屏；专项测断线、回滚、Trace。', mode: 'robust', proof: '三轮证据包可复核', owner: 'QA × Host' },
  { week: 3, lane: '记忆系统', title: 'Memory 候选区与睡眠整理', desc: '验证门、去重、冲突、过期、主动/被动召回和整理预演。', mode: 'ambitious', proof: '时序冲突 case + 消融', owner: 'Agent × RAG' },
  { week: 3, lane: 'Agent 评测', title: '单 Agent / 多 Agent 消融', desc: '比较 Skill、类型化委派和 Checkpoint 对质量、安全、时延与成本的影响。', mode: 'ambitious', proof: '四组固定数据集原始结果', owner: 'Agent × Eval' },
  { week: 3, lane: '安全纵深', title: 'Native 系统 Hook 观测原型', desc: '从 auditd / eBPF / LSM 方案中择实机可证的观测点，明确不做空泛拦截。', mode: 'extreme', proof: '攻击/误报/开销对照', owner: 'Security × Host' },
  { week: 3, lane: '基础合规', title: '等保三级自评证据页', desc: '控制—措施—证据—差距；数据库最小账号/TLS/备份恢复与 SBOM。', mode: 'ambitious', proof: '自评矩阵 + 一次恢复记录', owner: 'Security × DBA' },
  { week: 3, lane: '主动安全', title: '麒麟漏洞与加固复扫闭环', desc: '导入漏洞/弱配置结果，关联麒麟版本与资产上下文，经审批灰度加固、复扫并保留回滚证据。', mode: 'extreme', proof: '风险排序 + canary 修复 + 复扫残留', owner: 'Security × Host' },
  { week: 4, lane: '冻结与演练', title: 'D-8 功能冻结 / 四级降级', desc: 'Live 智能 → Live 确定性 → 真实历史回放 → 离线视频与证据包。', mode: 'robust', proof: '断网/模型故障彩排', owner: '全组' },
  { week: 4, lane: '演示增强', title: '端到端导演与评委分支', desc: '主叙事 3 分钟；准备安全、架构、Benchmark、国产化四条深挖支线。', mode: 'ambitious', proof: 'Q&A 实战评分', owner: 'Demo × 全组' },
  { week: 4, lane: '高限冲刺', title: '候选高限功能择优合流', desc: 'Agent Runtime / DevOps / ABAC / Native Hook / Memory / Graph 路由以证据成熟度决定登台深度。', mode: 'extreme', proof: '红黄绿 readiness gate', owner: '全组' },
]

const enabled = ref({ robust: true, ambitious: true, extreme: true })
const selected = ref(items[0])
const visibleItems = computed(() => items.filter(item => enabled.value[item.mode]))
const total = computed(() => visibleItems.value.length)
const toggle = (mode) => { enabled.value[mode] = !enabled.value[mode] }
</script>

<template>
  <div class="roadmap-view">
    <div class="hero-row">
      <div><p class="eyebrow">四周决赛交付安排</p><h2 class="section-heading">不是替你做取舍，而是把每种上限和证据门槛摊开</h2><p class="section-copy">三个开关是叠加层，不是三选一。默认展示全部 {{ items.length }} 个工作包；关闭某层只是查看不同负荷，不代表放弃该方向。</p></div>
      <div class="hero-actions roadmap-switches"><button v-for="(config,id) in modes" :key="id" :class="[id,{active:enabled[id]}]" @click="toggle(id)"><span></span><div><b>{{ config.name }} · {{ config.label }}</b><small>{{ enabled[id]?'已纳入视图':'暂时隐藏' }}</small></div></button></div>
    </div>

    <div class="roadmap-summary panel"><div><span>当前可见工作包</span><b>{{ total }}<small>/{{ items.length }}</small></b></div><div><span>稳健主线</span><b>{{ visibleItems.filter(i=>i.mode==='robust').length }}</b></div><div><span>进取增强</span><b>{{ visibleItems.filter(i=>i.mode==='ambitious').length }}</b></div><div><span>极限上限</span><b>{{ visibleItems.filter(i=>i.mode==='extreme').length }}</b></div><p><Icon name="shield" :size="15"/>任何功能登台都经过：真实场景 → 不可替代性 → 可验证便利 → 证据与降级。</p></div>

    <div class="roadmap-layout">
      <section class="panel timeline-board">
        <header class="week-head"><div v-for="week in 4" :key="week"><span>第 {{ week }} 周</span><b>{{ ['重构地基','并行跃迁','真机收证','冻结演练'][week-1] }}</b><small>{{ ['D-30 → D-24','D-23 → D-17','D-16 → D-9','D-8 → 决赛'][week-1] }}</small></div></header>
        <div class="timeline-grid">
          <div v-for="week in 4" :key="week" class="week-column">
            <button v-for="item in visibleItems.filter(i=>i.week===week)" :key="item.title" class="roadmap-card" :class="[item.mode,{active:selected.title===item.title}]" @click="selected=item"><span class="mode-mark"></span><small>{{ item.lane }}</small><b>{{ item.title }}</b><p>{{ item.desc }}</p><footer><span>{{ item.owner }}</span><Icon name="chevron" :size="12"/></footer></button>
            <div v-if="!visibleItems.some(i=>i.week===week)" class="empty-week">此周的层级已被隐藏</div>
          </div>
        </div>
      </section>

      <aside class="panel roadmap-inspector">
        <header class="panel-header"><div class="panel-title"><div><h3>实施卡片</h3><p>工作包验收要求</p></div></div><span class="tag" :class="selected.mode==='robust'?'good':selected.mode==='ambitious'?'warn':''">{{ modes[selected.mode].name }}</span></header>
        <div class="inspector-body"><p class="eyebrow">第 {{ selected.week }} 周 · {{ selected.lane }}</p><h3>{{ selected.title }}</h3><p class="inspector-desc">{{ selected.desc }}</p><div class="delivery-facts"><div><span>负责人</span><b>{{ selected.owner }}</b></div><div><span>验收证据</span><b>{{ selected.proof }}</b></div><div><span>场景检验</span><b>真人会在哪个故障/变更/审计时刻使用？</b></div><div><span>替代检验</span><b>现有页面、脚本或人工流程为何不能更好替代？</b></div><div><span>降级路径</span><b>失败时保留证据包 / 2D / 确定性路径</b></div></div><div class="readiness"><p>登台就绪门</p><span class="ready"><Icon name="check" :size="12"/>正常路径</span><span class="ready"><Icon name="check" :size="12"/>拒绝路径</span><span><Icon name="shield" :size="12"/>真机证据待绑定</span></div></div>
      </aside>
    </div>

    <div class="horizon panel"><header><Icon name="roadmap" :size="18"/><div><b>高上限能力入口全部保留</b><span>以下不是“中长期产品化”，而是一个月内可并行冲刺、由证据成熟度决定演示深度的方向。</span></div></header><div class="horizon-items"><article><Icon name="agent" :size="18"/><b>Skills + 多 Agent</b><span>受限委派、状态图、预算和独立验证</span></article><article><Icon name="screen" :size="18"/><b>DevOps 变更闭环</b><span>提交、发布、灰度、回滚进入安全执行闭环</span></article><article><Icon name="spark" :size="18"/><b>RAG 查询路由</b><span>按问题类型选择语义、关键词、图通道</span></article><article><Icon name="memory" :size="18"/><b>Memory 夜间整理</b><span>候选、冲突、过期与主动/被动召回</span></article><article><Icon name="shield" :size="18"/><b>Native Guard / Hook</b><span>KYSEC / LSM / auditd / eBPF 的端侧观测与拒绝</span></article><article><Icon name="access" :size="18"/><b>ABAC + JIT</b><span>主机组、动作风险、上下文和限时授权</span></article><article><Icon name="database" :size="18"/><b>等保 / 数据库 / SBOM</b><span>自评证据、恢复演练与依赖可替换性</span></article><article><Icon name="shield" :size="18"/><b>漏洞与加固复扫</b><span>漏洞与弱配置、审批灰度加固、复扫和回滚证据</span></article></div></div>
  </div>
</template>

<style scoped>
.week-column,.roadmap-card{background:var(--panel)!important;background-image:none!important}
.roadmap-card:hover,.roadmap-card.active{background:var(--panel-strong)!important}
.roadmap-switches button>span{border-radius:2px!important;box-shadow:none!important}
.week-column{background:transparent!important}
.roadmap-switches{align-items:stretch}.roadmap-switches button{display:flex;align-items:center;gap:8px;min-width:115px;padding:8px;border:1px solid var(--line);border-radius:9px;background:var(--panel-soft);text-align:left;cursor:pointer;opacity:.45}.roadmap-switches button.active{opacity:1}.roadmap-switches button>span{width:9px;height:9px;border:1px solid currentColor;border-radius:50%}.roadmap-switches button.active>span{background:currentColor;box-shadow:0 0 9px currentColor}.roadmap-switches button.robust{color:var(--mint)}.roadmap-switches button.ambitious{color:var(--amber)}.roadmap-switches button.extreme{color:var(--purple)}.roadmap-switches b,.roadmap-switches small{display:block}.roadmap-switches b{font-size:8px}.roadmap-switches small{margin-top:2px;color:var(--faint);font-size:7px}.roadmap-summary{display:grid;grid-template-columns:repeat(4,120px) 1fr;align-items:center;gap:10px;margin-bottom:14px;padding:12px 16px}.roadmap-summary>div{border-right:1px solid var(--line)}.roadmap-summary span{display:block;color:var(--faint);font-size:8px}.roadmap-summary b{font:500 19px 'DM Mono'}.roadmap-summary small{color:var(--faint);font-size:8px}.roadmap-summary p{display:flex;align-items:center;justify-content:flex-end;gap:7px;margin:0;color:var(--muted);font-size:8px}.roadmap-layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:14px}.timeline-board{overflow:hidden}.week-head{display:grid;grid-template-columns:repeat(4,1fr);border-bottom:1px solid var(--line)}.week-head>div{padding:12px 14px;border-right:1px solid var(--line)}.week-head>div:last-child{border-right:0}.week-head span,.week-head b,.week-head small{display:block}.week-head span{color:var(--mint);font:7px 'DM Mono';letter-spacing:.1em}.week-head b{margin-top:4px;font-size:11px}.week-head small{margin-top:3px;color:var(--faint);font:7px 'DM Mono'}.timeline-grid{display:grid;grid-template-columns:repeat(4,1fr);min-height:560px}.week-column{padding:9px;border-right:1px solid var(--line);background:linear-gradient(180deg,rgba(119,242,182,.018),transparent)}.week-column:last-child{border-right:0}.roadmap-card{position:relative;width:100%;padding:11px 10px 9px;margin-bottom:8px;border:1px solid var(--line);border-radius:9px;background:rgba(7,19,16,.74);text-align:left;cursor:pointer;overflow:hidden}.roadmap-card:hover,.roadmap-card.active{transform:translateY(-1px);border-color:var(--line-strong);background:rgba(21,45,36,.78)}.mode-mark{position:absolute;left:0;top:0;bottom:0;width:2px}.roadmap-card.robust .mode-mark{background:var(--mint)}.roadmap-card.ambitious .mode-mark{background:var(--amber)}.roadmap-card.extreme .mode-mark{background:var(--purple)}.roadmap-card>small{color:var(--faint);font:7px 'DM Mono'}.roadmap-card>b{display:block;margin:5px 0;font-size:9px}.roadmap-card>p{margin:0;color:var(--muted);font-size:7px;line-height:1.55}.roadmap-card footer{display:flex;justify-content:space-between;align-items:center;margin-top:9px;padding-top:7px;border-top:1px solid var(--line);color:var(--faint);font:6px 'DM Mono'}.empty-week{padding:30px 8px;color:var(--faint);font-size:8px;text-align:center}.inspector-body{padding:17px}.inspector-body>h3{margin:0 0 9px;font-size:16px}.inspector-desc{margin:0 0 15px;color:var(--muted);font-size:9px;line-height:1.7}.delivery-facts{border-top:1px solid var(--line)}.delivery-facts>div{padding:10px 0;border-bottom:1px solid var(--line)}.delivery-facts span,.delivery-facts b{display:block}.delivery-facts span{margin-bottom:4px;color:var(--faint);font-size:7px}.delivery-facts b{color:var(--muted);font-size:8px;font-weight:500;line-height:1.5}.readiness{display:flex;flex-wrap:wrap;gap:5px;margin-top:14px}.readiness p{width:100%;margin:0 0 4px;font-size:8px}.readiness span{display:flex;align-items:center;gap:4px;padding:5px 6px;border:1px solid var(--line);border-radius:6px;color:var(--faint);font-size:7px}.readiness span.ready{color:var(--mint);border-color:rgba(119,242,182,.22)}.horizon{margin-top:14px;padding:15px}.horizon>header{display:flex;align-items:center;gap:9px;margin-bottom:12px;color:var(--mint)}.horizon header b,.horizon header span{display:block}.horizon header b{font-size:11px}.horizon header span{margin-top:3px;color:var(--faint);font-size:8px}.horizon-items{display:grid;grid-template-columns:repeat(6,1fr);gap:7px}.horizon-items article{padding:10px;border:1px solid var(--line);border-radius:8px;background:rgba(255,255,255,.012)}.horizon-items svg{color:var(--mint)}.horizon-items b,.horizon-items span{display:block}.horizon-items b{margin:7px 0 4px;font:8px 'DM Mono'}.horizon-items span{color:var(--faint);font-size:7px;line-height:1.5}
@media(max-width:1150px){.roadmap-summary{grid-template-columns:repeat(4,1fr)}.roadmap-summary p{grid-column:1/-1;justify-content:flex-start}.roadmap-layout{grid-template-columns:1fr}.horizon-items{grid-template-columns:repeat(3,1fr)}}
</style>
