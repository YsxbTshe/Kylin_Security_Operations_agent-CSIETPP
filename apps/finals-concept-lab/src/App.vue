<script setup>
import { computed, defineAsyncComponent, ref } from 'vue'
import Icon from './components/Icon.vue'

const TaskWorkspace = defineAsyncComponent(() => import('./views/TaskWorkspace.vue'))
const TopologyLab = defineAsyncComponent(() => import('./views/TopologyLab.vue'))
const BigScreen = defineAsyncComponent(() => import('./views/BigScreen.vue'))
const AccessStudio = defineAsyncComponent(() => import('./views/AccessStudio.vue'))
const MemoryLab = defineAsyncComponent(() => import('./views/MemoryLab.vue'))
const AgentOrchestration = defineAsyncComponent(() => import('./views/AgentOrchestration.vue'))
const RoadmapExplorer = defineAsyncComponent(() => import('./views/RoadmapExplorer.vue'))

const scenes = [
  { id: 'task', label: '任务工作台', eyebrow: '任务执行与证据', icon: 'task', component: TaskWorkspace },
  { id: 'topology', label: '实例拓扑', eyebrow: '逻辑拓扑与影响范围', icon: 'topology', component: TopologyLab },
  { id: 'screen', label: '演示大屏', eyebrow: '态势、任务与证据', icon: 'screen', component: BigScreen },
  { id: 'access', label: '权限策略', eyebrow: '主机组与细粒度授权', icon: 'access', component: AccessStudio },
  { id: 'memory', label: '记忆管理', eyebrow: '记忆生命周期与召回', icon: 'memory', component: MemoryLab },
  { id: 'agents', label: 'Agent 编排', eyebrow: 'Skills 与多 Agent 调度', icon: 'agent', component: AgentOrchestration },
  { id: 'roadmap', label: '四周路线图', eyebrow: '决赛交付安排', icon: 'roadmap', component: RoadmapExplorer },
]

const activeId = ref('task')
const active = computed(() => scenes.find((item) => item.id === activeId.value))
const choose = (id) => {
  activeId.value = id
}
</script>

<template>
  <div class="app-shell">
    <aside class="side-rail">
      <div class="brand-mark" aria-label="麒麟智安">
        <span class="brand-core">K</span><span class="brand-name">麒麟智安</span>
      </div>
      <nav class="scene-nav" aria-label="概念场景">
        <button v-for="scene in scenes" :key="scene.id" class="scene-link" :class="{ active: activeId === scene.id }" :aria-current="activeId === scene.id ? 'page' : undefined" @click="choose(scene.id)">
          <Icon :name="scene.icon" :size="19" />
          <span>{{ scene.label }}</span>
        </button>
      </nav>
      <div class="rail-foot"><small>决赛交互原型 · v0.1</small></div>
    </aside>

    <main class="main-stage">
      <header class="topbar">
        <div class="topbar-title">
          <div><p>{{ active.eyebrow }}</p><h1>{{ active.label }}</h1></div>
        </div>
        <div class="topbar-meta">
          <span class="status-pill">演示系统在线</span>
          <span class="clock-chip">决赛交互原型</span>
        </div>
      </header>
      <section class="view-host"><component :is="active.component" /></section>
    </main>
  </div>
</template>
