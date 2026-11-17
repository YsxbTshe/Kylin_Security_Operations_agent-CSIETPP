<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  recallMemory,
  rememberMemory,
  type MemoryHit,
  type MemoryRecallMode,
} from '../api/memory'
import { fetchRagMemories, type RagMemoryItem } from '../api/rag'
import { useAuthStore } from '../stores/auth'
import {
  Button,
  DataTable,
  Dialog,
  FormField,
  Input,
  NumberInput,
  PageHeader,
  SectionCard,
  Select,
  Tag,
  toast,
  type DataTableColumn,
} from '../ui'

const authStore = useAuthStore()
const canConfigure = () => authStore.hasPermission('platform:configure')

const TYPE_LABELS: Record<string, string> = {
  FACT: '事实', PROCEDURE: '流程', POLICY: '策略', INCIDENT: '事件',
  OP_LOG: '操作归档', DOCUMENT_SUMMARY: '文档摘要', CONFIG: '配置', MANUAL: '手记',
}
const MODE_OPTIONS = [
  { label: '图增强复合（语义+图+时近+重要度）', value: 'full' },
  { label: 'Generative-Agents（语义+时近+重要度）', value: 'generative-agents' },
  { label: '纯语义（向量）', value: 'semantic' },
]
function typeLabel(t: string) {
  return TYPE_LABELS[t] || t
}

const query = ref('')
const mode = ref<MemoryRecallMode>('full')
const recalling = ref(false)
const hits = ref<MemoryHit[]>([])
const searched = ref(false)

const loadingList = ref(false)
const memories = ref<RagMemoryItem[]>([])

const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ memoryType: 'FACT', title: '', content: '', importance: 3 })

const memoryColumns: DataTableColumn[] = [
  { key: 'memoryType', label: '类型', width: '110px' },
  { key: 'title', label: '标题', minWidth: '200px' },
  { key: 'content', label: '内容', minWidth: '320px' },
  { key: 'importance', label: '重要度', width: '90px' },
]

async function recall() {
  if (!query.value.trim()) {
    toast.error('请输入查询')
    return
  }
  recalling.value = true
  try {
    hits.value = await recallMemory(query.value.trim(), mode.value, 8)
    searched.value = true
  } catch (error) {
    toast.error('召回失败')
  } finally {
    recalling.value = false
  }
}

async function loadList() {
  loadingList.value = true
  try {
    memories.value = await fetchRagMemories()
  } catch (error) {
    toast.error('加载记忆库失败')
  } finally {
    loadingList.value = false
  }
}

async function submitRemember() {
  if (!form.title.trim() || !form.content.trim()) {
    toast.error('标题和内容不能为空')
    return
  }
  submitting.value = true
  try {
    await rememberMemory({ ...form, title: form.title.trim(), content: form.content.trim() })
    toast.success('记忆已写入（自动去重并关联知识图谱实体）')
    dialogVisible.value = false
    form.title = ''
    form.content = ''
    await loadList()
  } catch (error) {
    toast.error('写入失败')
  } finally {
    submitting.value = false
  }
}

function preview(text: string) {
  const t = text.replace(/\s+/g, ' ')
  return t.length > 80 ? t.slice(0, 80) + '…' : t
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <PageHeader title="智能记忆" description="基于知识图谱的 Agent 记忆：复合召回（语义+图邻域+时近+重要度），Agent 操作日志自动归档为记忆。">
      <template #actions>
        <Button v-if="canConfigure()" @click="dialogVisible = true">写入记忆</Button>
      </template>
    </PageHeader>

    <SectionCard title="记忆召回" description="对比不同召回策略；时序更新场景下 recency 可让新事实覆盖旧事实。">
      <div class="recall-bar">
        <Input v-model="query" placeholder="例如：封禁恶意IP重启后还在吗 / db-01 备份几点" @keyup.enter="recall" />
        <Select :model-value="mode" :options="MODE_OPTIONS" @update:model-value="(v: string) => (mode = v as MemoryRecallMode)" />
        <Button :loading="recalling" @click="recall">召回</Button>
      </div>
      <div v-if="searched && hits.length === 0" class="text-muted recall-empty">未召回到相关记忆。</div>
      <ul class="hit-list">
        <li v-for="(h, i) in hits" :key="h.id" class="hit">
          <div class="hit-head">
            <span class="rank">#{{ i + 1 }}</span>
            <Tag :tone="h.memoryType === 'OP_LOG' ? 'warn' : 'neutral'">{{ typeLabel(h.memoryType) }}</Tag>
            <strong>{{ h.title }}</strong>
            <span class="hit-score">score {{ h.score.toFixed(3) }}</span>
          </div>
          <p class="hit-content text-muted">{{ h.content }}</p>
          <div class="hit-breakdown">
            <span>语义 {{ h.semantic.toFixed(2) }}</span>
            <span>图 {{ h.graph.toFixed(2) }}</span>
            <span>时近 {{ h.recency.toFixed(2) }}</span>
            <span>重要度 {{ h.importance }}</span>
          </div>
        </li>
      </ul>
    </SectionCard>

    <SectionCard title="记忆库" :description="`共 ${memories.length} 条；操作归档(OP_LOG)由 Agent 任务完成时自动沉淀。`" flush>
      <DataTable v-loading="loadingList" :columns="memoryColumns" :data="memories" row-key="id" empty="暂无记忆">
        <template #memoryType="{ value }">
          <Tag :tone="value === 'OP_LOG' ? 'warn' : 'neutral'">{{ typeLabel(value) }}</Tag>
        </template>
        <template #content="{ row }">
          <span class="text-muted">{{ preview(row.content) }}</span>
        </template>
      </DataTable>
    </SectionCard>

    <Dialog v-model:open="dialogVisible" title="写入记忆" width="640px">
      <div class="dialog-form">
        <FormField label="类型">
          <Select
            :model-value="form.memoryType"
            :options="Object.entries(TYPE_LABELS).map(([value, label]) => ({ label, value }))"
            @update:model-value="(v: string) => (form.memoryType = v)"
          />
        </FormField>
        <FormField label="标题"><Input v-model="form.title" placeholder="一句话主题" /></FormField>
        <FormField label="内容"><Input v-model="form.content" type="textarea" placeholder="记忆内容" /></FormField>
        <FormField label="重要度 (1-5)"><NumberInput v-model="form.importance" :min="1" :max="5" /></FormField>
      </div>
      <template #footer>
        <Button @click="dialogVisible = false">取消</Button>
        <Button variant="primary" :loading="submitting" @click="submitRemember">保存</Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.recall-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.recall-bar :deep(.input-root) {
  flex: 1;
}
.recall-empty {
  padding: 8px 0;
}
.hit-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.hit {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
}
.hit-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hit-head .rank {
  color: var(--accent);
  font-weight: 600;
}
.hit-head .hit-score {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted, #888);
}
.hit-content {
  margin: 8px 0 6px;
  font-size: 13px;
}
.hit-breakdown {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: var(--accent);
}
</style>
