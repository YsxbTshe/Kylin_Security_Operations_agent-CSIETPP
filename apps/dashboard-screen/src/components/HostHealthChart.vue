<script setup lang="ts">
import { computed } from 'vue'

interface HostHealthItem {
  hostname: string
  status: string
  cpuUsage: string
  memoryUsage: string
  diskUsage: string
}

const props = defineProps<{
  items?: HostHealthItem[]
}>()

const hosts = computed(() => props.items ?? [])
const hasData = computed(() => hosts.value.length > 0)

function parsePct(value: string): number {
  const n = Number.parseFloat((value || '').replace('%', ''))
  if (Number.isNaN(n)) return 0
  return Math.max(0, Math.min(100, n))
}

function levelFor(value: number): 'ok' | 'warn' | 'danger' {
  if (value >= 85) return 'danger'
  if (value >= 65) return 'warn'
  return 'ok'
}

function statusKey(status: string): string {
  const u = (status || '').toUpperCase()
  if (u === 'OFFLINE' || u === 'DOWN') return 'OFFLINE'
  if (u === 'ALERTING') return 'ALERTING'
  if (u === 'DEGRADED') return 'DEGRADED'
  return 'ONLINE'
}
</script>

<template>
  <div v-if="hasData" class="resource-table">
    <div class="resource-table__head">
      <span>主机</span>
      <span>状态</span>
      <span class="resource-bar__col">CPU</span>
      <span class="resource-bar__col">内存</span>
      <span class="resource-bar__col">磁盘</span>
    </div>
    <div
      v-for="host in hosts"
      :key="host.hostname"
      class="resource-row"
      :data-status="statusKey(host.status)"
    >
      <div class="resource-row__host">
        <span class="resource-row__pulse" />
        <span class="resource-row__hostname">{{ host.hostname }}</span>
      </div>
      <div class="resource-row__status">{{ host.status }}</div>

      <div class="resource-bar">
        <span class="resource-bar__label">CPU</span>
        <div class="resource-bar__track">
          <div
            class="resource-bar__fill"
            :data-level="levelFor(parsePct(host.cpuUsage))"
            :style="{ width: parsePct(host.cpuUsage) + '%' }"
          />
        </div>
        <span class="resource-bar__value">{{ host.cpuUsage }}</span>
      </div>

      <div class="resource-bar">
        <span class="resource-bar__label">内存</span>
        <div class="resource-bar__track">
          <div
            class="resource-bar__fill"
            :data-level="levelFor(parsePct(host.memoryUsage))"
            :style="{ width: parsePct(host.memoryUsage) + '%' }"
          />
        </div>
        <span class="resource-bar__value">{{ host.memoryUsage }}</span>
      </div>

      <div class="resource-bar">
        <span class="resource-bar__label">磁盘</span>
        <div class="resource-bar__track">
          <div
            class="resource-bar__fill"
            :data-level="levelFor(parsePct(host.diskUsage))"
            :style="{ width: parsePct(host.diskUsage) + '%' }"
          />
        </div>
        <span class="resource-bar__value">{{ host.diskUsage }}</span>
      </div>
    </div>
  </div>
  <div v-else class="resource-empty">暂无主机数据</div>
</template>
