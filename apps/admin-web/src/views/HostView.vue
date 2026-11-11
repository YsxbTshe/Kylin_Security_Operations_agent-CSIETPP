<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronRight, CircleCheck, MessagesSquare, RefreshCw, Search, ShieldCheck, Wifi, WifiOff } from 'lucide-vue-next'
import { fetchHosts, type HostSummary } from '../api/hosts'
import { useAuthStore } from '../stores/auth'
import { Alert, Button, Empty, MetricCard, PageHeader, Tag } from '../ui'

const router = useRouter()
const authStore = useAuthStore()
const loading = ref(false)
const error = ref('')
const hosts = ref<HostSummary[]>([])
const keyword = ref('')
const statusFilter = ref<'all' | 'ONLINE' | 'OFFLINE' | 'ALERTING'>('all')

const metrics = computed(() => {
  const upper = (s: string) => s.toUpperCase()
  return [
    { label: '纳管主机', value: hosts.value.length, icon: Wifi, tone: 'accent' as const, description: '当前组织已注册到平台的主机数量' },
    {
      label: '在线主机',
      value: hosts.value.filter((h) => upper(h.status) === 'ONLINE').length,
      icon: CircleCheck,
      tone: 'ok' as const,
      description: '最近心跳仍在有效时间窗口内',
    },
    {
      label: '离线主机',
      value: hosts.value.filter((h) => upper(h.status) === 'OFFLINE').length,
      icon: WifiOff,
      tone: 'warn' as const,
      description: '超过心跳超时阈值',
    },
    {
      label: 'Native Guard 风险',
      value: hosts.value.filter((h) => {
        const status = upper(h.nativeGuardStatus || '')
        return status === 'CRITICAL' || status === 'WARNING'
      }).length,
      icon: ShieldCheck,
      tone: hosts.value.some((h) => upper(h.nativeGuardStatus || '') === 'CRITICAL')
        ? 'danger' as const
        : hosts.value.some((h) => upper(h.nativeGuardStatus || '') === 'WARNING')
          ? 'warn' as const
          : 'ok' as const,
      description: `探针 critical ${hosts.value.reduce((sum, h) => sum + (h.nativeGuardCriticalCount || 0), 0)} · warning ${hosts.value.reduce((sum, h) => sum + (h.nativeGuardWarningCount || 0), 0)}`,
    },
  ]
})

const filteredHosts = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return hosts.value.filter((host) => {
    if (statusFilter.value !== 'all' && host.status.toUpperCase() !== statusFilter.value) {
      return false
    }
    if (!q) return true
    return (
      host.hostname.toLowerCase().includes(q) ||
      host.ip.toLowerCase().includes(q) ||
      host.nodeId.toLowerCase().includes(q) ||
      host.clusterName.toLowerCase().includes(q)
    )
  })
})

const clusterGroups = computed(() => {
  const map = new Map<string, HostSummary[]>()
  for (const host of filteredHosts.value) {
    const key = host.clusterName || '未分组'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(host)
  }
  // 集群内按状态/主机名排序：在线优先
  for (const list of map.values()) {
    list.sort((a, b) => {
      const onlineA = a.status.toUpperCase() === 'ONLINE' ? 0 : 1
      const onlineB = b.status.toUpperCase() === 'ONLINE' ? 0 : 1
      if (onlineA !== onlineB) return onlineA - onlineB
      return a.hostname.localeCompare(b.hostname)
    })
  }
  return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b))
})

const statusOptions: { value: typeof statusFilter.value; label: string; count: (h: HostSummary[]) => number }[] = [
  { value: 'all', label: '全部', count: (h) => h.length },
  { value: 'ONLINE', label: '在线', count: (h) => h.filter((x) => x.status.toUpperCase() === 'ONLINE').length },
  { value: 'OFFLINE', label: '离线', count: (h) => h.filter((x) => x.status.toUpperCase() === 'OFFLINE').length },
  { value: 'ALERTING', label: '告警', count: (h) => h.filter((x) => x.status.toUpperCase() === 'ALERTING').length },
]

function statusTone(status: string): 'ok' | 'warn' | 'danger' | 'neutral' {
  const normalized = status.toUpperCase()
  if (normalized === 'ONLINE') return 'ok'
  if (normalized === 'OFFLINE') return 'warn'
  if (normalized === 'ALERTING' || normalized === 'DEGRADED') return 'danger'
  return 'neutral'
}

function nativeGuardTone(status?: string): 'ok' | 'warn' | 'danger' | 'neutral' {
  const normalized = (status || '').toUpperCase()
  if (normalized === 'HEALTHY') return 'ok'
  if (normalized === 'CRITICAL') return 'danger'
  if (normalized === 'WARNING' || normalized === 'STALE') return 'warn'
  return 'neutral'
}

function parsePercent(source: string | undefined | null): number | null {
  if (!source) return null
  const match = String(source).match(/(\d+(?:\.\d+)?)/)
  if (!match) return null
  const value = Number(match[1])
  return Number.isFinite(value) ? Math.min(100, Math.max(0, value)) : null
}

function gaugeTone(pct: number | null): 'ok' | 'warn' | 'danger' | 'neutral' {
  if (pct === null) return 'neutral'
  if (pct >= 85) return 'danger'
  if (pct >= 65) return 'warn'
  return 'ok'
}

async function loadHosts() {
  loading.value = true
  error.value = ''
  try {
    hosts.value = await fetchHosts()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载主机失败'
  } finally {
    loading.value = false
  }
}

function openHost(hostname: string) {
  void router.push(`/hosts/${hostname}`)
}

function openChat(hostname: string) {
  // 旧「自然语言」控制台已随旧执行链路下线；改为跳到 Agent 任务并预填目标节点，
  // 由统一的「新建任务」对话框承接（自然语言目标 → 计划/执行）。
  void router.push({ name: 'agent-runs', query: { nodeId: hostname } })
}

watch(() => authStore.user?.currentOrganizationId, () => void loadHosts(), { immediate: true })
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="主机" description="按集群分组查看纳管主机的运行状态">
      <template #actions>
        <Button :icon="RefreshCw" @click="loadHosts">刷新</Button>
      </template>
    </PageHeader>

    <Alert v-if="error" tone="danger" :title="error" />

    <div class="metric-grid">
      <MetricCard
        v-for="metric in metrics"
        :key="metric.label"
        :label="metric.label"
        :value="metric.value"
        :icon="metric.icon"
        :tone="metric.tone"
        :description="metric.description"
      />
    </div>

    <!-- 工具条：状态分段 + 关键字搜索 -->
    <div class="toolbar">
      <div class="seg">
        <button
          v-for="opt in statusOptions"
          :key="opt.value"
          type="button"
          class="seg__item"
          :class="{ on: statusFilter === opt.value }"
          @click="statusFilter = opt.value"
        >
          {{ opt.label }}
          <span class="seg__count num">{{ opt.count(hosts) }}</span>
        </button>
      </div>
      <div class="search">
        <Search class="search__ic" />
        <input v-model="keyword" placeholder="搜索主机名 / IP / Node ID / 集群" />
      </div>
    </div>

    <Empty v-if="!loading && hosts.length === 0" description="当前组织尚未注册任何主机" />
    <Empty
      v-else-if="!loading && filteredHosts.length === 0"
      description="没有匹配的主机"
    />

    <!-- 按集群分组 -->
    <section v-for="[clusterName, list] in clusterGroups" :key="clusterName" class="cluster">
      <header class="cluster__head">
        <div class="cluster__title">
          <span class="cluster__name">{{ clusterName }}</span>
          <span class="cluster__count num">{{ list.length }}</span>
        </div>
        <div class="cluster__legend">
          <span class="legend-dot legend-dot--ok" />在线
          <span class="legend-dot legend-dot--warn" />离线
          <span class="legend-dot legend-dot--danger" />告警
        </div>
      </header>

      <div class="card-grid">
        <article v-for="host in list" :key="host.hostname" class="hcard" :class="`hcard--${statusTone(host.status)}`">
          <header class="hcard__head">
            <span class="hcard__status-dot" :class="`s-${statusTone(host.status)}`" />
            <div class="hcard__title-wrap">
              <button type="button" class="hcard__title" @click="openHost(host.hostname)">
                {{ host.hostname }}
              </button>
              <div class="hcard__subtitle num">{{ host.ip }} · {{ host.osVersion }}</div>
            </div>
            <div class="hcard__tags">
              <Tag :tone="statusTone(host.status)">{{ host.status }}</Tag>
              <Tag :tone="nativeGuardTone(host.nativeGuardStatus)">
                Guard {{ host.nativeGuardStatus || 'UNKNOWN' }}
                <template v-if="host.nativeGuardCriticalCount || host.nativeGuardWarningCount">
                  · C{{ host.nativeGuardCriticalCount }} W{{ host.nativeGuardWarningCount }}
                </template>
              </Tag>
            </div>
          </header>

          <div class="hcard__gauges">
            <div
              v-for="g in [
                { label: 'CPU', value: host.cpuUsage, pct: parsePercent(host.cpuUsage) },
                { label: '内存', value: host.memoryUsage, pct: parsePercent(host.memoryUsage) },
                { label: '磁盘', value: host.diskUsage, pct: parsePercent(host.diskUsage) },
              ]"
              :key="g.label"
              class="gauge"
            >
              <div class="gauge__head">
                <span>{{ g.label }}</span>
                <span class="num">{{ g.value || '—' }}</span>
              </div>
              <div class="gauge__bar">
                <span
                  class="gauge__fill"
                  :class="`g-${gaugeTone(g.pct)}`"
                  :style="{ width: `${g.pct ?? 0}%` }"
                />
              </div>
            </div>
          </div>

          <footer class="hcard__foot">
            <div class="hcard__heartbeat num" :title="host.nodeId">
              {{ host.grpcConnected ? 'gRPC mTLS · 已连接' : 'gRPC mTLS · 未连接' }} · {{ host.lastHeartbeatAt || '未收到' }}
            </div>
            <div class="hcard__actions">
              <Button link size="sm" :icon="MessagesSquare" @click="openChat(host.hostname)">对话</Button>
              <Button link size="sm" @click="openHost(host.hostname)">
                详情<ChevronRight class="hcard__chev" />
              </Button>
            </div>
          </footer>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 640px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }
}
.hcard__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}
.seg {
  display: inline-flex;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 3px;
  gap: 2px;
}
.seg__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px;
  border: none;
  background: none;
  border-radius: var(--radius-sm);
  color: var(--text-3);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.13s, color 0.13s;
}
.seg__item:hover {
  color: var(--text);
}
.seg__item.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow);
}
.seg__count {
  font-size: 11.5px;
  color: var(--text-3);
  padding: 0 5px;
  border-radius: 4px;
  background: var(--surface);
}
.seg__item.on .seg__count {
  background: var(--surface-2);
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  min-width: 280px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text-3);
  transition: border-color 0.13s, box-shadow 0.13s;
}
.search:focus-within {
  border-color: var(--accent);
  box-shadow: var(--ring);
}
.search__ic {
  width: 16px;
  height: 16px;
}
.search input {
  border: none;
  outline: none;
  background: none;
  font: inherit;
  font-size: 13.5px;
  color: var(--text);
  width: 100%;
}

.cluster {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cluster__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.cluster__title {
  display: inline-flex;
  align-items: baseline;
  gap: 9px;
}
.cluster__name {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text);
}
.cluster__count {
  font-size: 12px;
  color: var(--text-3);
  padding: 1px 7px;
  border-radius: 10px;
  background: var(--surface-2);
}
.cluster__legend {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--text-3);
}
.legend-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 4px;
  margin-left: 4px;
}
.legend-dot--ok { background: var(--ok); }
.legend-dot--warn { background: var(--warn); }
.legend-dot--danger { background: var(--danger); }

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 14px;
}

.hcard {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow);
  transition: border-color 0.14s, box-shadow 0.14s, transform 0.14s;
}
.hcard:hover {
  border-color: var(--border-2);
  box-shadow: var(--shadow-2);
}
.hcard--warn {
  background: linear-gradient(180deg, color-mix(in oklch, var(--warn-soft), transparent 70%), var(--surface) 80%);
}
.hcard--danger {
  border-color: var(--danger-ring);
}

.hcard__head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.hcard__status-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 7px;
  box-shadow: 0 0 0 3px color-mix(in oklch, currentColor, transparent 80%);
}
.hcard__status-dot.s-ok { background: var(--ok); color: var(--ok); }
.hcard__status-dot.s-warn { background: var(--warn); color: var(--warn); }
.hcard__status-dot.s-danger { background: var(--danger); color: var(--danger); }
.hcard__status-dot.s-neutral { background: var(--text-3); color: var(--text-3); }

.hcard__title-wrap {
  flex: 1;
  min-width: 0;
}
.hcard__title {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text);
  cursor: pointer;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
.hcard__title:hover {
  color: var(--accent);
}
.hcard__subtitle {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hcard__gauges {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}
.gauge {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.gauge__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--text-3);
}
.gauge__head span:last-child {
  color: var(--text-2);
  font-weight: 500;
}
.gauge__bar {
  height: 6px;
  border-radius: 999px;
  background: var(--surface-2);
  overflow: hidden;
}
.gauge__fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  transition: width 0.4s ease;
}
.gauge__fill.g-ok { background: var(--ok); }
.gauge__fill.g-warn { background: var(--warn); }
.gauge__fill.g-danger { background: var(--danger); }
.gauge__fill.g-neutral { background: var(--border-2); }

.hcard__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 4px;
  border-top: 1px solid var(--border);
  margin-top: -2px;
}
.hcard__heartbeat {
  font-size: 11px;
  color: var(--text-3);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hcard__actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex: none;
}
.hcard__chev {
  width: 14px;
  height: 14px;
  margin-left: 2px;
  vertical-align: -2px;
}
</style>
