<script setup lang="ts">
import { ref, watch } from 'vue'
import { Download, RefreshCw } from 'lucide-vue-next'
import { downloadArtifact, fetchArtifacts, type AgentArtifactItem } from '../api/agent-runtime'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  DataTable,
  PageHeader,
  SectionCard,
  Tag,
  toast,
  type DataTableColumn,
} from '../ui'
import { formatBytes, formatInstant } from '../utils/format'
import { artifactStatusLabel, artifactStatusTone } from '../utils/labels'

const authStore = useAuthStore()

const loading = ref(false)
const downloading = ref('')
const rows = ref<AgentArtifactItem[]>([])

const columns: DataTableColumn[] = [
  { key: 'name', label: '名称', minWidth: '220px' },
  { key: 'sm3Hex', label: 'SM3 摘要', minWidth: '220px' },
  { key: 'sizeBytes', label: '大小', width: '100px', align: 'right' },
  { key: 'status', label: '状态', width: '100px' },
  { key: 'contentType', label: '内容类型', width: '170px' },
  { key: 'publishedAt', label: '发布时间', width: '170px' },
  { key: 'actions', label: '操作', width: '110px', align: 'right' },
]

async function loadArtifacts() {
  loading.value = true
  try {
    rows.value = await fetchArtifacts(100)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '加载产物列表失败')
  } finally {
    loading.value = false
  }
}

async function handleDownload(artifact: AgentArtifactItem) {
  downloading.value = artifact.id
  try {
    await downloadArtifact(artifact)
    toast.success(`已开始下载「${artifact.name}」`)
  } catch (err) {
    toast.error(err instanceof Error ? err.message : '下载失败')
  } finally {
    downloading.value = ''
  }
}

function shortDigest(digest: string | null) {
  if (!digest) return '—'
  return digest.length > 24 ? `${digest.slice(0, 12)}…${digest.slice(-12)}` : digest
}

watch(() => authStore.currentOrganizationId, () => {
  void loadArtifacts()
}, { immediate: true })
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader
      title="产物管理"
      description="Agent 运行期产出的 Artifact：SM3 摘要去重发布，已发布产物可签发限时链接下载。"
    >
      <template #actions>
        <Button :icon="RefreshCw" @click="loadArtifacts">刷新</Button>
      </template>
    </PageHeader>

    <SectionCard title="产物列表" flush>
      <DataTable :columns="columns" :data="rows" row-key="id" :loading="loading" empty="暂无产物">
        <template #name="{ row }">
          <div class="cell-main">{{ (row as AgentArtifactItem).name }}</div>
          <div class="cell-sub num">{{ (row as AgentArtifactItem).id }}</div>
        </template>
        <template #sm3Hex="{ row }">
          <span class="mono digest" :title="(row as AgentArtifactItem).sm3Hex ?? undefined">
            {{ shortDigest((row as AgentArtifactItem).sm3Hex) }}
          </span>
        </template>
        <template #sizeBytes="{ row }">
          <span class="num">{{ formatBytes((row as AgentArtifactItem).sizeBytes) }}</span>
        </template>
        <template #status="{ row }">
          <Tag :tone="artifactStatusTone((row as AgentArtifactItem).status)">
            {{ artifactStatusLabel((row as AgentArtifactItem).status) }}
          </Tag>
        </template>
        <template #contentType="{ row }">
          <span class="cell-sub">{{ (row as AgentArtifactItem).contentType }}</span>
        </template>
        <template #publishedAt="{ row }">
          <span class="num">{{ formatInstant((row as AgentArtifactItem).publishedAt) }}</span>
        </template>
        <template #actions="{ row }">
          <Button
            v-if="(row as AgentArtifactItem).status === 'PUBLISHED'"
            size="sm"
            link
            :icon="Download"
            :loading="downloading === (row as AgentArtifactItem).id"
            @click="handleDownload(row as AgentArtifactItem)"
          >
            下载
          </Button>
          <span v-else class="cell-sub">—</span>
        </template>
      </DataTable>
    </SectionCard>
  </div>
</template>

<style scoped>
.cell-main {
  font-size: 13px;
  font-weight: 500;
}
.cell-sub {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 2px;
}
.digest {
  font-size: 12px;
  color: var(--text-2);
}
</style>
