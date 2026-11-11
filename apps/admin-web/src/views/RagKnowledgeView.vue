<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createRagGraphEdge,
  createRagDocument,
  createRagMemory,
  deleteRagMemory,
  fetchRagGraphEdges,
  fetchRagDocuments,
  fetchRagMemories,
  queryRag,
  updateRagDocument,
  analyzeRagPdf,
  uploadRagPdf,
  type RagChunkItem,
  type RagDocumentAnalysis,
  type RagDocumentItem,
  type RagRetrievalTrace,
  type RagGraphEdgeItem,
  type RagMemoryItem,
  type SaveRagGraphEdgePayload,
  type SaveRagDocumentPayload,
  type SaveRagMemoryPayload,
} from '../api/rag'
import { BookText, ClipboardList, FileCode, FileText, FileType2, Settings2 } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { memoryTypeLabel, relationLabel, sourceTypeLabel } from '../utils/labels'
import {
  Button,
  DataTable,
  Dialog,
  FormField,
  Input,
  NumberInput,
  PageHeader,
  SectionCard,
  Tabs,
  Tag,
  confirm,
  toast,
  type DataTableColumn,
} from '../ui'
import PdfAnalyzeDialog from '../components/rag/PdfAnalyzeDialog.vue'
import RetrievalTracePanel from '../components/rag/RetrievalTracePanel.vue'
import { fetchRuntimeStatus, type RuntimeStatus } from '../api/system'

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 打开资料详情：走独立二级页面，抽屉宽度不够读长文档和看 PDF */
function openDetail(anchorDocumentId: string, chunkId = '') {
  void router.push({
    path: '/rag-knowledge/file',
    query: chunkId ? { doc: anchorDocumentId, chunk: chunkId } : { doc: anchorDocumentId },
  })
}

const loading = ref(false)
const submitting = ref(false)
const querying = ref(false)
const runtime = ref<RuntimeStatus | null>(null)
void fetchRuntimeStatus().then((s) => { runtime.value = s }).catch(() => {})
const dialogVisible = ref(false)
const memoryDialogVisible = ref(false)
const graphDialogVisible = ref(false)
const editingDocumentId = ref('')
const documents = ref<RagDocumentItem[]>([])
const memories = ref<RagMemoryItem[]>([])
const graphEdges = ref<RagGraphEdgeItem[]>([])
const queryText = ref('')
const queryAnswer = ref('')
const queryReferences = ref<RagDocumentItem[]>([])
const queryChunks = ref<RagChunkItem[]>([])
const queryMemories = ref<RagMemoryItem[]>([])
const queryGraphEdges = ref<RagGraphEdgeItem[]>([])
const pdfTitle = ref('')
const pdfFile = ref<File | null>(null)
const pdfInput = ref<HTMLInputElement>()

/* 解析可视化：上传 PDF 与对已有文档归纳共用同一个弹窗 */
const analyzeOpen = ref(false)
const analyzeMode = ref<'import' | 'summarize'>('import')
const analyzeName = ref('')
const analyzeLoading = ref(false)
const analyzeError = ref('')
const analysis = ref<RagDocumentAnalysis | null>(null)

/* 检索预览 */
const queryTrace = ref<RagRetrievalTrace | null>(null)
const resultTab = ref('chunks')
const libraryTab = ref('memories')

const resultTabs = computed(() => [
  { value: 'chunks', label: `分片 ${queryChunks.value.length}` },
  { value: 'references', label: `文档 ${queryReferences.value.length}` },
  { value: 'memories', label: `记忆 ${queryMemories.value.length}` },
  { value: 'edges', label: `关系 ${queryGraphEdges.value.length}` },
])

const libraryTabs = computed(() => [
  { value: 'memories', label: `记忆 ${memories.value.length}` },
  { value: 'edges', label: `图谱关系 ${graphEdges.value.length}` },
])

const form = reactive<SaveRagDocumentPayload>({
  title: '',
  sourceType: 'RUNBOOK',
  content: '',
})

const memoryForm = reactive<SaveRagMemoryPayload>({
  memoryType: 'OPERATION',
  title: '',
  content: '',
  importance: 3,
  sourceDocumentId: '',
})

const graphForm = reactive<SaveRagGraphEdgePayload>({
  sourceNode: '',
  relation: 'RELATED_TO',
  targetNode: '',
  evidence: '',
  sourceDocumentId: '',
})

/**
 * 按原始文件归并。
 *
 * 导入时受单文档 12000 字符上限约束，一个 .md 被切成了多条 `document` 记录，
 * 标题格式为「文件名 — 章节名」。这里按公共前缀还原成文件视角：使用者关心的是
 * "这份手册"，而不是它在库里被拆成了 23 条记录。
 */
interface FileGroup {
  name: string
  sections: RagDocumentItem[]
  chars: number
  sourceType: string
  excerpt: string
}

/**
 * 从章节标题还原原始文件名。
 *
 * 导入时有两层切分：先按标题切成「文件名 — 章节名」，章节仍超上限时再硬切并追加
 * 「(n/m)」。两个后缀都要剥掉，否则一份被硬切成三段的文档会显示成三张卡片。
 */
function fileNameOf(document: RagDocumentItem) {
  return document.title.split(' — ')[0].replace(/\s*\(\d+\/\d+\)\s*$/, '').trim()
}

/**
 * 按文件类型给图标、颜色和格式角标：先看原始文件扩展名，没有原件再看来源类型。
 *
 * 颜色取自图表色板而非状态色（ok/warn/danger）——状态色有"正常/告警/错误"的固定语义，
 * 拿红色标 PDF 会让人误以为这份文件出了问题。图表色板已验证明暗两套均可辨。
 */
interface FileKind {
  icon: typeof FileText
  color: string
  badge: string
}

function fileKindOf(group: { sourceType: string; sections: RagDocumentItem[] }): FileKind {
  const name = group.sections.find((s) => s.sourceFileName)?.sourceFileName ?? ''
  const extension = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
  if (extension === 'pdf' || group.sourceType === 'PDF') {
    return { icon: FileType2, color: 'var(--chart-sev-3)', badge: 'PDF' }
  }
  if (extension === 'md' || extension === 'markdown') {
    return { icon: FileCode, color: 'var(--chart-cat-1)', badge: 'MD' }
  }
  switch (group.sourceType) {
    case 'KYLIN_OFFICIAL':
    case 'OPENKYLIN_OFFICIAL':
    case 'KYLIN_SECURITY':
      return { icon: BookText, color: 'var(--chart-cat-2)', badge: 'MD' }
    case 'UPSTREAM_MANPAGE':
      return { icon: FileCode, color: 'var(--chart-cat-1)', badge: 'MAN' }
    case 'RUNBOOK':
      return { icon: ClipboardList, color: 'var(--chart-sev-1)', badge: 'RUNBOOK' }
    case 'SYSTEM':
      return { icon: Settings2, color: 'var(--text-3)', badge: 'SYS' }
    default:
      return { icon: FileText, color: 'var(--text-3)', badge: 'TXT' }
  }
}

const fileGroups = computed<FileGroup[]>(() => {
  const map = new Map<string, RagDocumentItem[]>()
  for (const document of documents.value) {
    const key = fileNameOf(document)
    const list = map.get(key)
    if (list) list.push(document)
    else map.set(key, [document])
  }
  return [...map.entries()].map(([name, sections]) => ({
    name,
    sections,
    chars: sections.reduce((sum, s) => sum + s.content.length, 0),
    sourceType: sections[0]?.sourceType ?? '',
    excerpt: cardExcerpt(sections[0]?.content ?? ''),
  }))
})

const fileCountLabel = computed(() => `${fileGroups.value.length} 个文件 · ${documents.value.length} 段`)

/** 正文摘要：卡片上给出足以判断"这份资料讲什么"的信息量 */
function cardExcerpt(content: string) {
  const normalized = content.replace(/\s+/g, ' ').trim()
  return normalized.length > 150 ? `${normalized.slice(0, 150)}…` : normalized
}
const canConfigure = computed(() => authStore.hasPermission('platform:configure'))
const dialogTitle = computed(() => editingDocumentId.value ? '编辑知识片段' : '添加知识片段')
const documentFormInvalid = computed(() => !form.title.trim() || !form.sourceType.trim() || !form.content.trim())
const memoryFormInvalid = computed(() => !memoryForm.title.trim() || !memoryForm.memoryType.trim() || !memoryForm.content.trim())
const graphFormInvalid = computed(() => !graphForm.sourceNode.trim() || !graphForm.relation.trim() || !graphForm.targetNode.trim())

const referenceColumns: DataTableColumn[] = [
  { key: 'title', label: '标题', minWidth: '220px' },
  { key: 'sourceType', label: '来源', width: '130px' },
  { key: 'content', label: '内容', minWidth: '360px' },
]

const chunkColumns: DataTableColumn[] = [
  { key: 'title', label: '标题', minWidth: '180px' },
  { key: 'chunkIndex', label: '分片', width: '80px' },
  { key: 'score', label: '得分', width: '90px' },
  { key: 'content', label: '内容', minWidth: '360px' },
]

const memoryColumns: DataTableColumn[] = [
  { key: 'title', label: '标题', minWidth: '220px' },
  { key: 'memoryType', label: '类型', width: '140px' },
  { key: 'importance', label: '重要度', width: '90px' },
  { key: 'content', label: '内容', minWidth: '360px' },
  { key: 'actions', label: '操作', width: '80px' },
]

const graphColumns: DataTableColumn[] = [
  { key: 'sourceNode', label: '源节点', minWidth: '180px' },
  { key: 'relation', label: '关系', width: '150px' },
  { key: 'targetNode', label: '目标节点', minWidth: '180px' },
  { key: 'evidence', label: '证据', minWidth: '300px' },
]

function resetForm() {
  editingDocumentId.value = ''
  form.title = ''
  form.sourceType = 'RUNBOOK'
  form.content = ''
}

function openCreateDialog() {
  resetForm()
  dialogVisible.value = true
}

function openEditDialog(document: RagDocumentItem) {
  editingDocumentId.value = document.id
  form.title = document.title
  form.sourceType = document.sourceType
  form.content = document.content
  dialogVisible.value = true
}

async function loadDocuments() {
  loading.value = true
  try {
    const [documentRows, memoryRows, graphRows] = await Promise.all([
      fetchRagDocuments(),
      fetchRagMemories(),
      fetchRagGraphEdges(),
    ])
    documents.value = documentRows
    memories.value = memoryRows
    graphEdges.value = graphRows
  } finally {
    loading.value = false
  }
}

async function submitDocument() {
  if (documentFormInvalid.value) {
    toast.warning('请填写标题、来源类型和内容')
    return
  }

  submitting.value = true
  try {
    const payload = {
      title: form.title.trim(),
      sourceType: form.sourceType.trim(),
      content: form.content.trim(),
    }
    if (editingDocumentId.value) {
      await updateRagDocument(editingDocumentId.value, payload)
      toast.success('知识片段已更新')
    } else {
      await createRagDocument(payload)
      toast.success('知识片段已添加')
    }
    dialogVisible.value = false
    await loadDocuments()
  } finally {
    submitting.value = false
  }
}

function pickPdf() {
  pdfInput.value?.click()
}

/** 选中文件后直接进入解析预览：先让人看清抽取质量，再决定要不要入库 */
async function onPdfFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  if (!file) return
  pdfFile.value = file
  analyzeMode.value = 'import'
  analyzeName.value = file.name
  analysis.value = null
  analyzeError.value = ''
  analyzeOpen.value = true
  analyzeLoading.value = true
  try {
    analysis.value = await analyzeRagPdf(file)
  } catch (error) {
    analyzeError.value = error instanceof Error ? error.message : 'PDF 解析失败'
  } finally {
    analyzeLoading.value = false
  }
}

/**
 * 走服务端的分页入库接口，而不是把预览里的正文直接建成一个文档——
 * 预览正文为了控制响应体是截断过的，直接用会丢内容；分页入库还会一并保存原始 PDF。
 */
async function confirmPdfImport() {
  if (!analysis.value || !pdfFile.value) return
  submitting.value = true
  try {
    const created = await uploadRagPdf(pdfFile.value, pdfTitle.value)
    toast.success(`已入库，共 ${created.length} 个分页文档`)
    analyzeOpen.value = false
    pdfTitle.value = ''
    pdfFile.value = null
    await loadDocuments()
  } finally {
    submitting.value = false
  }
}

function openMemoryDialog() {
  memoryForm.memoryType = 'OPERATION'
  memoryForm.title = ''
  memoryForm.content = ''
  memoryForm.importance = 3
  memoryForm.sourceDocumentId = ''
  memoryDialogVisible.value = true
}

async function submitMemory() {
  if (memoryFormInvalid.value) {
    toast.warning('请填写记忆类型、标题和内容')
    return
  }
  submitting.value = true
  try {
    await createRagMemory({
      memoryType: memoryForm.memoryType.trim(),
      title: memoryForm.title.trim(),
      content: memoryForm.content.trim(),
      importance: memoryForm.importance,
      sourceDocumentId: memoryForm.sourceDocumentId?.trim() || undefined,
    })
    toast.success('记忆已保存')
    memoryDialogVisible.value = false
    await loadDocuments()
  } finally {
    submitting.value = false
  }
}

async function removeMemory(memory: RagMemoryItem) {
  if (!(await confirm({ title: '删除记忆', message: `确认删除记忆“${memory.title}”？`, tone: 'danger', confirmText: '删除' }))) {
    return
  }
  await deleteRagMemory(memory.id)
  toast.success('记忆已删除')
  await loadDocuments()
}

function openGraphDialog() {
  graphForm.sourceNode = ''
  graphForm.relation = 'RELATED_TO'
  graphForm.targetNode = ''
  graphForm.evidence = ''
  graphForm.sourceDocumentId = ''
  graphDialogVisible.value = true
}

async function submitGraphEdge() {
  if (graphFormInvalid.value) {
    toast.warning('请填写源节点、关系和目标节点')
    return
  }
  submitting.value = true
  try {
    await createRagGraphEdge({
      sourceNode: graphForm.sourceNode.trim(),
      relation: graphForm.relation.trim(),
      targetNode: graphForm.targetNode.trim(),
      evidence: graphForm.evidence?.trim() || undefined,
      sourceDocumentId: graphForm.sourceDocumentId?.trim() || undefined,
    })
    toast.success('知识图谱关系已保存')
    graphDialogVisible.value = false
    await loadDocuments()
  } finally {
    submitting.value = false
  }
}

async function runQueryPreview() {
  querying.value = true
  try {
    const result = await queryRag(queryText.value.trim(), true)
    queryTrace.value = result.trace ?? null
    queryAnswer.value = result.answer
    queryReferences.value = result.references
    queryChunks.value = result.chunks
    queryMemories.value = result.memories
    queryGraphEdges.value = result.graphEdges
  } finally {
    querying.value = false
  }
}

function previewContent(content: string) {
  const normalized = content.trim()
  return normalized.length > 140 ? `${normalized.slice(0, 140)}...` : normalized
}

onMounted(async () => {
  await loadDocuments()
  // 从知识图谱跳转过来：定位到目标资料并高亮对应分片
  // 详情页点「编辑」会带 ?edit= 回到这里打开编辑弹窗
  const editId = typeof route.query.edit === 'string' ? route.query.edit : ''
  if (editId) {
    const target = documents.value.find((item) => item.id === editId)
    if (target) openEditDialog(target)
    void router.replace({ path: route.path, query: {} })
    return
  }

  const docId = typeof route.query.doc === 'string' ? route.query.doc : ''
  const chunkId = typeof route.query.chunk === 'string' ? route.query.chunk : ''
  if (docId) {
    // 图谱跳转直接转到详情页，保持"一个资料一个地址"
    openDetail(docId, chunkId)
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="知识库" description="混合检索的知识来源：文档分片、记忆与图谱关系">
      <template #actions>
        <input ref="pdfInput" type="file" accept="application/pdf,.pdf" hidden @change="onPdfFileChange" />
        <Button v-if="canConfigure" @click="pickPdf">上传 PDF</Button>
        <Button v-if="canConfigure" variant="primary" @click="openCreateDialog">添加片段</Button>
      </template>
    </PageHeader>

    <!-- 检索预览提到首位：这是本页最有价值的交互，也是混合检索能力的直接体现 -->
    <SectionCard title="检索预览" description="向量 / 关键词 / 图谱三路召回，RRF 融合后交叉重排">
      <div v-if="runtime && !runtime.embeddingReal" class="embed-badge">
        <b>向量通道：本地 hash 近似</b>
        <span>
          当前 embedding 提供商为 <code>{{ runtime.embeddingProvider || 'LOCAL' }}</code>，是 hash 分桶伪向量、<b>非语义向量</b>，
          得分仅供演示；真实语义检索请在
          <a @click.prevent="router.push('/rag-settings')">系统设置 → RAG</a>
          配置 <code>OPENAI_COMPATIBLE</code> embedding 端点。
        </span>
      </div>
      <div class="inline-form">
        <Input
          v-model="queryText"
          placeholder="输入故障现象、处置关键词或审批问题"
          @keyup.enter="runQueryPreview"
        />
        <Button variant="primary" :loading="querying" :disabled="!queryText.trim()" @click="runQueryPreview">
          检索
        </Button>
      </div>

      <RetrievalTracePanel v-if="queryTrace" class="trace-panel" :trace="queryTrace" />

      <div v-if="queryAnswer" class="answer-card">
        <div class="answer-title">{{ queryAnswer }}</div>
      </div>

      <Tabs v-if="queryAnswer" v-model="resultTab" :tabs="resultTabs" class="result-tabs">
        <template #chunks>
          <DataTable :columns="chunkColumns" :data="queryChunks" row-key="id" empty="未命中分片">
            <template #chunkIndex="{ value }"><span class="num">#{{ value }}</span></template>
            <template #score="{ value }"><span class="num">{{ Number(value).toFixed(3) }}</span></template>
            <template #content="{ row }"><span class="text-muted">{{ previewContent(row.content) }}</span></template>
          </DataTable>
        </template>
        <template #references>
          <DataTable :columns="referenceColumns" :data="queryReferences" row-key="id" empty="未命中文档">
            <template #sourceType="{ value }"><Tag>{{ sourceTypeLabel(value) }}</Tag></template>
            <template #content="{ row }"><span class="text-muted">{{ previewContent(row.content) }}</span></template>
          </DataTable>
        </template>
        <template #memories>
          <DataTable
            :columns="memoryColumns.filter((item) => item.key !== 'actions')"
            :data="queryMemories"
            row-key="id"
            empty="未命中记忆"
          >
            <template #memoryType="{ value }"><Tag>{{ memoryTypeLabel(value) }}</Tag></template>
            <template #importance="{ value }"><span class="num">{{ value }}</span></template>
            <template #content="{ row }"><span class="text-muted">{{ previewContent(row.content) }}</span></template>
          </DataTable>
        </template>
        <template #edges>
          <DataTable :columns="graphColumns" :data="queryGraphEdges" row-key="id" empty="未命中图谱关系">
            <template #relation="{ value }"><Tag>{{ relationLabel(value) }}</Tag></template>
            <template #evidence="{ row }"><span class="text-muted">{{ previewContent(row.evidence ?? '') }}</span></template>
          </DataTable>
        </template>
      </Tabs>
    </SectionCard>

    <SectionCard title="资料" :description="fileCountLabel">
      <div v-if="!fileGroups.length" class="doc-empty">暂无资料</div>
      <div v-else class="doc-grid">
        <article
          v-for="file in fileGroups"
          :key="file.name"
          class="doc-card"
          role="button"
          tabindex="0"
          @click="openDetail(file.sections[0].id)"
          @keyup.enter="openDetail(file.sections[0].id)"
        >
          <header class="doc-card__head">
            <span
              class="doc-card__icon"
              :style="{ color: fileKindOf(file).color, background: `color-mix(in oklab, ${fileKindOf(file).color} 14%, transparent)` }"
            >
              <component :is="fileKindOf(file).icon" />
            </span>
            <div class="doc-card__titles">
              <h4 class="doc-card__title" :title="file.name">{{ file.name }}</h4>
              <span class="doc-card__badge" :style="{ color: fileKindOf(file).color }">
                {{ fileKindOf(file).badge }}
              </span>
            </div>
          </header>
          <p class="doc-card__excerpt">{{ file.excerpt }}</p>
          <footer class="doc-card__foot">
            <Tag>{{ sourceTypeLabel(file.sourceType) }}</Tag>
            <span class="doc-card__len num">{{ file.chars.toLocaleString() }} 字符</span>
            <span v-if="file.sections.length > 1" class="doc-card__len num">{{ file.sections.length }} 节</span>
          </footer>
        </article>
      </div>
    </SectionCard>

    <!-- 记忆与图谱关系合并为一张卡，避免整页被同构表格堆成长条 -->
    <SectionCard title="记忆与图谱">
      <template #actions>
        <Button v-if="canConfigure && libraryTab === 'memories'" variant="primary" @click="openMemoryDialog">添加记忆</Button>
        <Button v-if="canConfigure && libraryTab === 'edges'" variant="primary" @click="openGraphDialog">添加关系</Button>
      </template>
      <Tabs v-model="libraryTab" :tabs="libraryTabs">
        <template #memories>
          <DataTable :columns="memoryColumns" :data="memories" row-key="id" empty="暂无记忆">
            <template #memoryType="{ value }"><Tag>{{ memoryTypeLabel(value) }}</Tag></template>
            <template #importance="{ value }"><span class="num">{{ value }}</span></template>
            <template #content="{ row }"><span class="text-muted">{{ previewContent(row.content) }}</span></template>
            <template #actions="{ row }">
              <Button v-if="canConfigure" link variant="danger" @click="removeMemory(row)">删除</Button>
              <span v-else class="text-muted">只读</span>
            </template>
          </DataTable>
        </template>
        <template #edges>
          <DataTable :columns="graphColumns" :data="graphEdges" row-key="id" empty="暂无知识图谱关系">
            <template #relation="{ value }"><Tag>{{ relationLabel(value) }}</Tag></template>
            <template #evidence="{ row }">
              <span class="text-muted">{{ previewContent(row.evidence ?? '') }}</span>
            </template>
          </DataTable>
        </template>
      </Tabs>
    </SectionCard>

    <PdfAnalyzeDialog
      v-model:open="analyzeOpen"
      :mode="analyzeMode"
      :file-name="analyzeName"
      :loading="analyzeLoading"
      :analysis="analysis"
      :error="analyzeError"
      :saving="submitting"
      @confirm="confirmPdfImport"
    />

    <Dialog v-model:open="dialogVisible" :title="dialogTitle" width="760px">
      <div class="dialog-form">
        <div class="form-grid">
          <FormField label="标题">
            <Input v-model="form.title" />
          </FormField>
          <FormField label="来源类型">
            <Input v-model="form.sourceType" />
          </FormField>
        </div>
        <FormField label="内容">
          <Input v-model="form.content" type="textarea" :rows="10" />
        </FormField>
      </div>

      <template #footer>
        <Button @click="dialogVisible = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="documentFormInvalid" @click="submitDocument">保存</Button>
      </template>
    </Dialog>

    <Dialog v-model:open="memoryDialogVisible" title="添加记忆" width="760px">
      <div class="dialog-form">
        <div class="form-grid">
          <FormField label="类型">
            <Input v-model="memoryForm.memoryType" />
          </FormField>
          <FormField label="重要度">
            <NumberInput v-model="memoryForm.importance" :min="1" :max="5" />
          </FormField>
        </div>
        <FormField label="标题">
          <Input v-model="memoryForm.title" />
        </FormField>
        <FormField label="内容">
          <Input v-model="memoryForm.content" type="textarea" :rows="8" />
        </FormField>
      </div>

      <template #footer>
        <Button @click="memoryDialogVisible = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="memoryFormInvalid" @click="submitMemory">保存</Button>
      </template>
    </Dialog>

    <Dialog v-model:open="graphDialogVisible" title="添加知识图谱关系" width="760px">
      <div class="dialog-form">
        <div class="form-grid">
          <FormField label="源节点">
            <Input v-model="graphForm.sourceNode" />
          </FormField>
          <FormField label="关系">
            <Input v-model="graphForm.relation" />
          </FormField>
        </div>
        <FormField label="目标节点">
          <Input v-model="graphForm.targetNode" />
        </FormField>
        <FormField label="证据">
          <Input v-model="graphForm.evidence" type="textarea" :rows="6" />
        </FormField>
      </div>

      <template #footer>
        <Button @click="graphDialogVisible = false">取消</Button>
        <Button variant="primary" :loading="submitting" :disabled="graphFormInvalid" @click="submitGraphEdge">保存</Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.text-muted {
  color: var(--text-3);
}
.embed-badge {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid color-mix(in srgb, #d97706 40%, var(--border));
  background: color-mix(in srgb, #d97706 8%, var(--surface));
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-2);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.embed-badge a {
  color: var(--accent);
  cursor: pointer;
  text-decoration: underline;
}
.embed-badge code {
  font-size: 11px;
}
.inline-form {
  display: flex;
  align-items: center;
  gap: 12px;
}
.inline-form :deep(.field) {
  flex: 1;
}
.file-name {
  font-size: 13px;
  color: var(--text-2);
}
.answer-card {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
}
.answer-title {
  font-size: 13.5px;
  font-weight: 500;
}
.answer-meta {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--text-3);
}
.doc-empty {
  padding: 28px 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
}
.doc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}
.doc-card {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--surface);
  cursor: pointer;
  transition: border-color .16s, transform .16s, box-shadow .16s;
}
.doc-card:hover {
  border-color: var(--border-2);
  transform: translateY(-1px);
  box-shadow: var(--shadow-2);
}
.doc-card:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}
.doc-card__head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.doc-card__icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: var(--radius-md);
}
.doc-card__icon :deep(svg) {
  width: 19px;
  height: 19px;
  stroke-width: 1.9;
}
.doc-card__titles {
  min-width: 0;
  flex: 1;
}
.doc-card__badge {
  display: inline-block;
  margin-top: 3px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .06em;
}
.doc-card__title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--text);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.doc-card__excerpt {
  margin: 0;
  flex: 1;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-3);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.doc-card__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 9px;
  border-top: 1px solid var(--border);
}
.doc-card__len {
  font-size: 11px;
  color: var(--text-3);
}
.doc-card__spacer {
  flex: 1;
}
.doc-card__ops {
  display: flex;
  gap: 8px;
}
.trace-panel {
  margin-top: 14px;
}
.result-tabs {
  margin-top: 14px;
}
.dialog-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.row-actions {
  display: flex;
  gap: 12px;
}
@media (max-width: 680px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
