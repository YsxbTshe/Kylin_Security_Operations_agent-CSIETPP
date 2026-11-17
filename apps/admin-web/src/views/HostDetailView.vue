<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  ArrowLeft,
  ChevronDown,
  ChevronRight,
  Ban,
  Cpu,
  FileKey,
  HardDrive,
  MessagesSquare,
  MemoryStick,
  ShieldCheck,
  Timer,
} from 'lucide-vue-next'
import {
  fetchHostCertificate,
  fetchHostDetail,
  revokeHostCertificate,
  type HostCertificateBundle,
  type HostDetail,
  type HostToolDefinition,
} from '../api/hosts'
import {
  Alert,
  Button,
  Dialog,
  Input,
  MetricCard,
  PageHeader,
  SectionCard,
  Tag,
  Tooltip,
  confirm,
  toast,
} from '../ui'

// 已知工具的中文说明，后端 description 缺失或为英文时使用
const route = useRoute()
const router = useRouter()
const hostname = computed(() => String(route.params.hostname ?? ''))

interface NativeGuardItem {
  tool: string
  supported: boolean
  status: string
  message: string
}

const loading = ref(false)
const error = ref('')
const detail = ref<HostDetail | null>(null)
const toolsExpanded = ref(false)
const certificateDialogOpen = ref(false)
const certificateLoading = ref(false)
const revokeLoading = ref(false)
const certificateBundle = ref<HostCertificateBundle | null>(null)
const revokeReason = ref('manual revocation')

const sortedTools = computed<HostToolDefinition[]>(() => {
  const list = detail.value?.supportedToolDetails ?? []
  return [...list].sort((a, b) => {
    if (a.readOnly !== b.readOnly) return a.readOnly ? 1 : -1
    return a.toolName.localeCompare(b.toolName)
  })
})

const toolApprovalCount = computed(() => sortedTools.value.filter((t) => !t.readOnly).length)
const nativeGuardItems = computed<NativeGuardItem[]>(() => {
  const raw = detail.value?.nativeGuardJson?.trim()
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as { items?: NativeGuardItem[] }
    return Array.isArray(parsed.items) ? parsed.items : []
  } catch {
    return []
  }
})

function describeTool(tool: HostToolDefinition) {
  // 说明文字直接取签名工具清单里的 description——此前这里挂了一张中文映射表，
  // 键是 log_read / service_restart 这类旧式工具名，而 manifest 全是 kylin.* 前缀，
  // 查找必然 undefined，等于一张永不命中的死表。
  const base = tool.description || '暂无说明'
  return tool.readOnly ? `${base}（只读）` : `${base}（执行需审批）`
}

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

function nodeExporterTone(status?: string): 'ok' | 'warn' | 'danger' | 'neutral' {
  const normalized = (status || '').toUpperCase()
  if (normalized === 'UP') return 'ok'
  if (normalized === 'DOWN' || normalized === 'ERROR') return 'danger'
  if (normalized === 'DISABLED' || normalized === 'STALE' || normalized === 'UNKNOWN') return 'warn'
  return 'neutral'
}

function nativeGuardItemTone(item: NativeGuardItem): 'ok' | 'warn' | 'danger' | 'neutral' {
  if (!item.supported) return 'neutral'
  const normalized = item.status.toLowerCase()
  if (['disabled', 'inactive', 'failed', 'off', 'error'].includes(normalized)) return 'danger'
  if (['permissive', 'unknown'].includes(normalized)) return 'warn'
  if (['enabled', 'active', 'running', 'on', 'enforcing'].includes(normalized)) return 'ok'
  return 'neutral'
}

function toolTone(readOnly: boolean): 'neutral' | 'warn' {
  return readOnly ? 'neutral' : 'warn'
}

function certTone(status?: string): 'ok' | 'warn' | 'danger' | 'neutral' {
  const normalized = (status || '').toUpperCase()
  if (normalized === 'ACTIVE') return 'ok'
  if (normalized === 'REVOKED' || normalized === 'EXPIRED') return 'danger'
  if (normalized === 'EXPIRING') return 'warn'
  return 'neutral'
}

function formatPercent(value?: number | null) {
  return typeof value === 'number' ? `${value.toFixed(1)}%` : '—'
}

function formatNumber(value?: number | null) {
  return typeof value === 'number' ? value.toFixed(2) : '—'
}

async function loadDetail() {
  if (!hostname.value) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await fetchHostDetail(hostname.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载主机失败'
  } finally {
    loading.value = false
  }
}

function openChat() {
  if (!detail.value) return
  // 旧「自然语言」控制台已下线，跳到 Agent 任务并预填目标节点，统一由新建任务对话框承接。
  void router.push({ name: 'agent-runs', query: { nodeId: detail.value.hostname } })
}

async function openCertificateBundle() {
  if (!detail.value) return
  certificateDialogOpen.value = true
  certificateLoading.value = true
  try {
    certificateBundle.value = await fetchHostCertificate(detail.value.hostname)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载证书包失败'
  } finally {
    certificateLoading.value = false
  }
}

async function revokeCertificate() {
  if (!detail.value) return
  const ok = await confirm({
    title: '吊销主机证书',
    message: `确认吊销 ${detail.value.hostname} 的 mTLS 证书？吊销后当前 ControlStream 会被断开，Agent 需要重新 enrollment。`,
    tone: 'danger',
    confirmText: '吊销证书',
  })
  if (!ok) return
  revokeLoading.value = true
  try {
    await revokeHostCertificate(detail.value.hostname, revokeReason.value || 'manual revocation')
    toast.success('证书已吊销')
    await loadDetail()
    certificateBundle.value = null
  } catch (err) {
    error.value = err instanceof Error ? err.message : '吊销证书失败'
  } finally {
    revokeLoading.value = false
  }
}

watch(hostname, () => void loadDetail(), { immediate: true })
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="head-row">
      <button type="button" class="back" @click="router.push('/hosts')">
        <ArrowLeft class="back__ic" />返回主机列表
      </button>
    </div>

    <Alert v-if="error" tone="danger" :title="error" />

    <PageHeader
      v-if="detail"
      :title="detail.hostname"
      :description="`${detail.ip} · ${detail.osVersion}`"
    >
      <template #actions>
        <Tag :tone="statusTone(detail.status)">{{ detail.status }}</Tag>
        <Tag :tone="certTone(detail.certStatus)">证书 {{ detail.certStatus || 'UNKNOWN' }}</Tag>
        <Tag :tone="nativeGuardTone(detail.nativeGuardStatus)">Native Guard {{ detail.nativeGuardStatus || 'UNKNOWN' }}</Tag>
        <Tag :tone="nodeExporterTone(detail.nodeExporterStatus)">node_exporter {{ detail.nodeExporterStatus || 'UNKNOWN' }}</Tag>
        <Button variant="default" :icon="FileKey" @click="openCertificateBundle">
          证书包 / CRL
        </Button>
        <Button variant="danger" :icon="Ban" :loading="revokeLoading" @click="revokeCertificate">
          吊销证书
        </Button>
        <Button variant="primary" :icon="MessagesSquare" @click="openChat">
          与这台主机对话
        </Button>
      </template>
    </PageHeader>

    <!-- 资源指标卡 -->
    <div v-if="detail" class="metric-grid">
      <MetricCard label="CPU" :value="detail.cpuUsage || '—'" :icon="Cpu" tone="accent" description="当前占用" />
      <MetricCard label="内存" :value="detail.memoryUsage || '—'" :icon="MemoryStick" tone="ok" description="当前占用" />
      <MetricCard label="磁盘" :value="detail.diskUsage || '—'" :icon="HardDrive" tone="warn" description="当前占用" />
      <MetricCard label="在线时长" :value="detail.uptimeText || '—'" :icon="Timer" tone="accent" description="自上次启动" />
    </div>

    <!-- 主机基础信息 -->
    <SectionCard v-if="detail" title="主机信息">
      <div class="kv-grid">
        <div class="kv-item">
          <span class="kv-item__label">Node ID</span>
          <strong class="kv-item__value num">{{ detail.nodeId }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">集群</span>
          <strong class="kv-item__value">{{ detail.clusterName }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">Agent 版本</span>
          <strong class="kv-item__value num">{{ detail.agentVersion }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">gRPC 连接</span>
          <Tag :tone="detail.grpcConnected ? 'ok' : 'warn'">{{ detail.grpcConnected ? 'mTLS connected' : 'disconnected' }}</Tag>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">最近心跳</span>
          <strong class="kv-item__value num">{{ detail.lastHeartbeatAt || '未收到' }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">系统版本</span>
          <strong class="kv-item__value">{{ detail.osVersion }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">IP</span>
          <strong class="kv-item__value num">{{ detail.ip }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">负载</span>
          <strong class="kv-item__value num">{{ detail.loadAverage || '—' }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">状态</span>
          <Tag :tone="statusTone(detail.status)">{{ detail.status }}</Tag>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">证书状态</span>
          <Tag :tone="certTone(detail.certStatus)">{{ detail.certStatus || 'UNKNOWN' }}</Tag>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">证书主体</span>
          <strong class="kv-item__value num">{{ detail.certSubject || '-' }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">证书过期</span>
          <strong class="kv-item__value num">{{ detail.certExpiresAt || '-' }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">gRPC 接入时间</span>
          <strong class="kv-item__value num">{{ detail.grpcConnectedAt || '-' }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">Native Guard</span>
          <Tag :tone="nativeGuardTone(detail.nativeGuardStatus)">{{ detail.nativeGuardStatus || 'UNKNOWN' }}</Tag>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">Guard 检查时间</span>
          <strong class="kv-item__value num">{{ detail.nativeGuardCheckedAt || '-' }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">Guard Critical</span>
          <strong class="kv-item__value num">{{ detail.nativeGuardCriticalCount }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">Guard Warning</span>
          <strong class="kv-item__value num">{{ detail.nativeGuardWarningCount }}</strong>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">node_exporter</span>
          <Tag :tone="nodeExporterTone(detail.nodeExporterStatus)">{{ detail.nodeExporterStatus || 'UNKNOWN' }}</Tag>
        </div>
        <div class="kv-item">
          <span class="kv-item__label">node_exporter 检查时间</span>
          <strong class="kv-item__value num">{{ detail.nodeExporterCheckedAt || '-' }}</strong>
        </div>
      </div>
    </SectionCard>

    <SectionCard v-if="detail" title="node_exporter 指标快照" description="来自 Host Agent 心跳中的 Prometheus 指标采集结果">
      <div class="node-exporter-summary">
        <div class="node-exporter-summary__status">
          <Activity class="node-exporter-summary__ic" />
          <div>
            <div class="node-exporter-summary__title">采集状态</div>
            <div class="node-exporter-summary__meta">
              <Tag :tone="nodeExporterTone(detail.nodeExporterStatus)">{{ detail.nodeExporterStatus || 'UNKNOWN' }}</Tag>
              <span class="num">{{ detail.nodeExporterCheckedAt || '尚未上报' }}</span>
            </div>
          </div>
        </div>
        <div class="node-exporter-metrics">
          <div>
            <span>CPU</span>
            <strong>{{ formatPercent(detail.nodeExporterCpuUsagePercent) }}</strong>
          </div>
          <div>
            <span>Memory</span>
            <strong>{{ formatPercent(detail.nodeExporterMemoryUsagePercent) }}</strong>
          </div>
          <div>
            <span>Filesystem</span>
            <strong>{{ formatPercent(detail.nodeExporterFilesystemUsagePercent) }}</strong>
          </div>
          <div>
            <span>Load 1m</span>
            <strong>{{ formatNumber(detail.nodeExporterLoad1) }}</strong>
          </div>
        </div>
      </div>
      <label v-if="detail.nodeExporterJson" class="guard-json">
        <span>Raw node_exporter JSON</span>
        <Input :model-value="detail.nodeExporterJson" type="textarea" :rows="8" readonly />
      </label>
      <div v-else class="guard-empty">这台主机暂未上报 node_exporter 指标快照。</div>
    </SectionCard>

    <SectionCard v-if="detail" title="Native Guard 主机安全姿态" description="KYSEC / SELinux / auditd / security-switch 只读探测结果">
      <div class="guard-summary">
        <div class="guard-summary__status">
          <ShieldCheck class="guard-summary__ic" />
          <div>
            <div class="guard-summary__title">当前姿态</div>
            <div class="guard-summary__meta">
              <Tag :tone="nativeGuardTone(detail.nativeGuardStatus)">{{ detail.nativeGuardStatus || 'UNKNOWN' }}</Tag>
              <span class="num">{{ detail.nativeGuardCheckedAt || '尚未上报' }}</span>
            </div>
          </div>
        </div>
        <div class="guard-summary__counts">
          <div><span>Critical</span><strong>{{ detail.nativeGuardCriticalCount }}</strong></div>
          <div><span>Warning</span><strong>{{ detail.nativeGuardWarningCount }}</strong></div>
        </div>
      </div>

      <div v-if="nativeGuardItems.length" class="guard-list">
        <div v-for="item in nativeGuardItems" :key="item.tool" class="guard-item">
          <div class="guard-item__main">
            <strong class="num">{{ item.tool }}</strong>
            <span>{{ item.message || '未提供说明' }}</span>
          </div>
          <div class="guard-item__tags">
            <Tag :tone="item.supported ? 'ok' : 'neutral'">{{ item.supported ? 'supported' : 'unsupported' }}</Tag>
            <Tag :tone="nativeGuardItemTone(item)">{{ item.status || 'unknown' }}</Tag>
          </div>
        </div>
      </div>
      <div v-else class="guard-empty">这台主机暂未上报 Native Guard 姿态。</div>

      <label v-if="detail.nativeGuardJson" class="guard-json">
        <span>Raw posture JSON</span>
        <Input :model-value="detail.nativeGuardJson" type="textarea" :rows="8" readonly />
      </label>
    </SectionCard>

    <SectionCard v-if="detail" title="gRPC / mTLS 证书生命周期" description="服务端 CA、主机证书状态与吊销列表">
      <div class="cert-actions">
        <div class="cert-status-card">
          <ShieldCheck class="cert-status-card__ic" />
          <div>
            <div class="cert-status-card__title">当前证书</div>
            <div class="cert-status-card__meta">
              <Tag :tone="certTone(detail.certStatus)">{{ detail.certStatus || 'UNKNOWN' }}</Tag>
              <span class="num">{{ detail.certExpiresAt || '未记录过期时间' }}</span>
            </div>
          </div>
        </div>
        <div class="cert-actions__buttons">
          <Button variant="default" :icon="FileKey" @click="openCertificateBundle">查看证书包 / CRL</Button>
          <Button variant="danger" :icon="Ban" :loading="revokeLoading" @click="revokeCertificate">吊销证书</Button>
        </div>
      </div>
      <div class="cert-reason">
        <span class="kv-item__label">吊销原因</span>
        <Input v-model="revokeReason" placeholder="manual revocation" />
      </div>
    </SectionCard>

    <!-- 已注册工具：默认收起，需审批排前 -->
    <SectionCard v-if="detail" title="已注册工具" :description="`${sortedTools.length} 个工具${toolApprovalCount > 0 ? ` · ${toolApprovalCount} 个需审批` : ''}`">
      <template #actions>
        <Button link size="sm" @click="toolsExpanded = !toolsExpanded">
          <component :is="toolsExpanded ? ChevronDown : ChevronRight" class="ic" />
          {{ toolsExpanded ? '收起' : '展开' }}
        </Button>
      </template>

      <div v-if="toolsExpanded" class="tool-tags">
        <Tooltip
          v-for="tool in sortedTools"
          :key="tool.toolName"
          :content="describeTool(tool)"
        >
          <span>
            <Tag :tone="toolTone(tool.readOnly)">
              {{ tool.toolName }}
              <span class="tool-tags__meta">{{ tool.readOnly ? '只读' : '需审批' }}</span>
            </Tag>
          </span>
        </Tooltip>
        <span v-if="sortedTools.length === 0" class="tool-tags__empty">这台主机暂未注册可调用的工具</span>
      </div>
      <div v-else class="tool-tags__collapsed">
        点击右上角的"展开"查看本机可调用的工具列表与说明。
      </div>
    </SectionCard>
    <Dialog v-model:open="certificateDialogOpen" title="主机证书包 / CRL" description="包含当前 CA 证书和本组织已吊销证书 CRL PEM。" width="760px">
      <div v-loading="certificateLoading" class="cert-dialog">
        <div v-if="certificateBundle" class="cert-dialog__meta">
          <div><span>Host</span><strong>{{ certificateBundle.hostname }}</strong></div>
          <div><span>Node ID</span><strong>{{ certificateBundle.nodeId }}</strong></div>
          <div><span>Status</span><Tag :tone="certTone(certificateBundle.certStatus)">{{ certificateBundle.certStatus }}</Tag></div>
          <div><span>Expires</span><strong>{{ certificateBundle.certExpiresAt || '-' }}</strong></div>
        </div>
        <div v-if="certificateBundle" class="pem-grid">
          <label>
            <span>CA Certificate PEM</span>
            <Input :model-value="certificateBundle.caCertPem" type="textarea" :rows="10" readonly />
          </label>
          <label>
            <span>Certificate Revocation List PEM</span>
            <Input :model-value="certificateBundle.crlPem" type="textarea" :rows="10" readonly />
          </label>
        </div>
      </div>
      <template #footer>
        <Button variant="default" @click="certificateDialogOpen = false">关闭</Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.head-row {
  display: flex;
  align-items: center;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
}
.back:hover {
  color: var(--accent);
}
.back__ic {
  width: 15px;
  height: 15px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px 18px;
}
.kv-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.kv-item__label {
  font-size: 12px;
  color: var(--text-3);
}
.kv-item__value {
  font-size: 13.5px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tool-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.tool-tags__meta {
  margin-left: 4px;
  opacity: 0.85;
  font-size: 11px;
}
.tool-tags__empty {
  font-size: 13px;
  color: var(--text-3);
}
.tool-tags__collapsed {
  font-size: 13px;
  color: var(--text-3);
}

.node-exporter-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}
.node-exporter-summary__status {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.node-exporter-summary__ic {
  width: 26px;
  height: 26px;
  color: var(--accent);
}
.node-exporter-summary__title {
  font-size: 13.5px;
  font-weight: 600;
}
.node-exporter-summary__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
  color: var(--text-3);
  font-size: 12.5px;
}
.node-exporter-metrics {
  display: grid;
  grid-template-columns: repeat(4, 96px);
  gap: 10px;
}
.node-exporter-metrics div {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
}
.node-exporter-metrics span {
  display: block;
  color: var(--text-3);
  font-size: 12px;
}
.node-exporter-metrics strong {
  display: block;
  margin-top: 3px;
  font-size: 18px;
}

.guard-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}
.guard-summary__status {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.guard-summary__ic {
  width: 26px;
  height: 26px;
  color: var(--accent);
}
.guard-summary__title {
  font-size: 13.5px;
  font-weight: 600;
}
.guard-summary__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
  color: var(--text-3);
  font-size: 12.5px;
}
.guard-summary__counts {
  display: grid;
  grid-template-columns: repeat(2, 90px);
  gap: 10px;
}
.guard-summary__counts div {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-2);
}
.guard-summary__counts span {
  display: block;
  color: var(--text-3);
  font-size: 12px;
}
.guard-summary__counts strong {
  display: block;
  margin-top: 3px;
  font-size: 18px;
}
.guard-list {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}
.guard-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--surface-1);
}
.guard-item__main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.guard-item__main strong {
  font-size: 13px;
}
.guard-item__main span {
  color: var(--text-3);
  font-size: 12.5px;
}
.guard-item__tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.guard-empty {
  margin-top: 14px;
  color: var(--text-3);
  font-size: 13px;
}
.guard-json {
  display: block;
  margin-top: 14px;
}
.guard-json > span {
  display: block;
  margin-bottom: 6px;
  color: var(--text-3);
  font-size: 12px;
}
.guard-json :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
}

.cert-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.cert-status-card {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.cert-status-card__ic {
  width: 26px;
  height: 26px;
  color: var(--accent);
}
.cert-status-card__title {
  font-size: 13.5px;
  font-weight: 600;
}
.cert-status-card__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
  color: var(--text-3);
  font-size: 12.5px;
}
.cert-actions__buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cert-reason {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
}
.cert-dialog {
  min-height: 180px;
}
.cert-dialog__meta {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}
.cert-dialog__meta div {
  min-width: 0;
}
.cert-dialog__meta span {
  display: block;
  margin-bottom: 4px;
  color: var(--text-3);
  font-size: 12px;
}
.cert-dialog__meta strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.pem-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.pem-grid label > span {
  display: block;
  margin-bottom: 6px;
  color: var(--text-3);
  font-size: 12px;
}
.pem-grid :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
}

.ic {
  width: 14px;
  height: 14px;
}

@media (max-width: 900px) {
  .metric-grid,
  .kv-grid,
  .cert-dialog__meta,
  .pem-grid,
  .node-exporter-metrics,
  .guard-summary__counts {
    grid-template-columns: repeat(2, 1fr);
  }
  .node-exporter-summary,
  .guard-summary,
  .guard-item {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
