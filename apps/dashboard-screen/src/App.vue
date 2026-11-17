<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, type Component } from 'vue'
import { fetchScreenDashboard, type ScreenDashboardResponse } from './api/screen'
import OverviewPage from './pages/OverviewPage.vue'
import HostsPage from './pages/HostsPage.vue'
import AlertsPage from './pages/AlertsPage.vue'
import AgentsPage from './pages/AgentsPage.vue'
import BigScreenActsPage from './pages/BigScreenActsPage.vue'

type PageKey = 'overview' | 'hosts' | 'alerts' | 'agents' | 'acts'

const TABS: Array<{ key: PageKey; label: string; component: Component }> = [
  { key: 'overview', label: '综合态势', component: OverviewPage },
  { key: 'hosts',    label: '主机资源', component: HostsPage },
  { key: 'alerts',   label: '告警事件', component: AlertsPage },
  { key: 'agents',   label: 'Agent 运行', component: AgentsPage },
  { key: 'acts',     label: '演示三幕', component: BigScreenActsPage },
]

const currentPage = ref<PageKey>('overview')
const currentComponent = computed(() => TABS.find((tab) => tab.key === currentPage.value)?.component ?? OverviewPage)

const loading = ref(true)
const error = ref('')
const dashboard = ref<ScreenDashboardResponse | null>(null)
const lastUpdatedAt = ref<Date | null>(null)
const refreshIntervalMs = 30_000
let refreshTimer: ReturnType<typeof window.setInterval> | undefined

const now = ref(new Date())
let clockTimer: ReturnType<typeof window.setInterval> | undefined

function pad(v: number) {
  return String(v).padStart(2, '0')
}

const dateLabel = computed(() => {
  const d = now.value
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
})

const timeLabel = computed(() => {
  const d = now.value
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

const lastUpdatedLabel = computed(() => {
  const d = lastUpdatedAt.value
  if (!d) return '--:--:--'
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
})

async function loadDashboard(showLoading = true) {
  if (showLoading) loading.value = true
  error.value = ''
  try {
    dashboard.value = await fetchScreenDashboard()
    lastUpdatedAt.value = new Date()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '大屏数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadDashboard()
  refreshTimer = window.setInterval(() => {
    void loadDashboard(false)
  }, refreshIntervalMs)
  clockTimer = window.setInterval(() => {
    now.value = new Date()
  }, 1000)
})

onBeforeUnmount(() => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
  if (clockTimer !== undefined) window.clearInterval(clockTimer)
})
</script>

<template>
  <div class="screen">
    <!-- HUD chevron banner -->
    <header class="screen-banner">
      <svg class="screen-banner__svg" viewBox="0 0 1600 78" preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient id="bannerFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="rgba(0, 229, 255, 0.16)" />
            <stop offset="100%" stop-color="rgba(0, 229, 255, 0.02)" />
          </linearGradient>
        </defs>
        <path
          d="M 280 4 L 360 4 L 380 26 L 1220 26 L 1240 4 L 1320 4 L 1360 64 L 240 64 Z"
          fill="url(#bannerFill)"
          stroke="rgba(0, 229, 255, 0.5)"
          stroke-width="1"
        />
        <path
          d="M 384 32 L 1216 32"
          stroke="rgba(0, 229, 255, 0.32)"
          stroke-width="1"
          stroke-dasharray="2 4"
          fill="none"
        />
        <path d="M 0 64 L 230 64 L 250 44" stroke="rgba(0, 229, 255, 0.5)" stroke-width="1" fill="none" />
        <path d="M 1370 44 L 1390 64 L 1600 64" stroke="rgba(0, 229, 255, 0.5)" stroke-width="1" fill="none" />
        <path d="M 240 64 L 250 54 L 270 54 L 280 64 Z" fill="rgba(0, 229, 255, 0.28)" stroke="rgba(0, 229, 255, 0.5)" stroke-width="1" />
        <path d="M 1330 64 L 1340 54 L 1360 54 L 1370 64 Z" fill="rgba(0, 229, 255, 0.28)" stroke="rgba(0, 229, 255, 0.5)" stroke-width="1" />
      </svg>

      <div class="screen-banner__side screen-banner__side--left">
        <span class="hud-chip hud-chip--accent">实时监控</span>
        <span class="hud-chip">同步 {{ lastUpdatedLabel }}</span>
      </div>

      <div class="screen-banner__title">麒麟安全智能运维大屏</div>

      <div class="screen-banner__side screen-banner__side--right">
        <span class="hud-chip">{{ dateLabel }}</span>
        <span class="hud-chip hud-chip__time">
          <strong>北京时间</strong>{{ timeLabel }}
        </span>
      </div>
    </header>

    <!-- Tab switcher -->
    <nav class="screen-tabs">
      <button
        v-for="(tab, index) in TABS"
        :key="tab.key"
        type="button"
        class="screen-tab"
        :class="{ 'screen-tab--active': currentPage === tab.key }"
        @click="currentPage = tab.key"
      >
        <span class="screen-tab__index">0{{ index + 1 }}</span>
        <span>{{ tab.label }}</span>
      </button>
    </nav>

    <section v-if="error" class="screen-alert">
      <strong>数据降级</strong><span>{{ error }}</span>
    </section>

    <!-- Active page -->
    <component :is="currentComponent" :dashboard="dashboard" :loading="loading" />

    <div v-if="loading && !dashboard" class="screen-loading">
      <div>
        <div class="screen-loading__spinner" />
        <div>数据加载中</div>
      </div>
    </div>
  </div>
</template>
