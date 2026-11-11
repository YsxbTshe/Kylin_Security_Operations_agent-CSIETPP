<script setup lang="ts">
/**
 * 知识库资料详情页（二级页面）。
 *
 * 不用抽屉：分片列表、原文与 PDF 原件预览都需要整屏宽度，抽屉里读长文档太局促。
 * 独立路由还带来两个好处——可以直接分享链接，以及从知识图谱跳过来时浏览器返回键能回到图谱。
 *
 * 入参用某个章节的 documentId 而不是文件名：文件名含中文、括号、斜杠（如 `systemd.service(5)`），
 * 放进路径要处理各种转义，而 id 是安全的。页面自己按标题前缀还原出它所属的文件。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from 'lucide-vue-next'
import {
  deleteRagDocument,
  depositRagDocument,
  fetchRagDocuments,
  summarizeRagDocument,
  type RagDocumentAnalysis,
  type RagDocumentItem,
} from '../api/rag'
import { Alert, Button, PageHeader, confirm, toast } from '../ui'
import FileDetailPanel from '../components/rag/FileDetailPanel.vue'
import PdfAnalyzeDialog from '../components/rag/PdfAnalyzeDialog.vue'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const error = ref('')
const documents = ref<RagDocumentItem[]>([])

const canConfigure = computed(() => authStore.hasPermission('platform:configure'))
const focusChunkId = computed(() => (typeof route.query.chunk === 'string' ? route.query.chunk : ''))

/** 与知识库列表页保持同一套还原规则：两层切分的后缀都要剥掉 */
function fileNameOf(document: RagDocumentItem) {
  return document.title.split(' — ')[0].replace(/\s*\(\d+\/\d+\)\s*$/, '').trim()
}

const fileName = computed(() => {
  const docId = typeof route.query.doc === 'string' ? route.query.doc : ''
  const anchor = documents.value.find((item) => item.id === docId)
  return anchor ? fileNameOf(anchor) : ''
})

const sections = computed(() =>
  documents.value.filter((item) => fileNameOf(item) === fileName.value),
)

/* 归纳弹窗与列表页共用同一个组件 */
const analyzeOpen = ref(false)
const analyzeName = ref('')
const analyzeLoading = ref(false)
const analyzeError = ref('')
const analysis = ref<RagDocumentAnalysis | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    documents.value = await fetchRagDocuments()
    if (!sections.value.length) {
      error.value = '未找到对应资料，可能已被删除'
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载资料失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

function back() {
  void router.push('/rag-knowledge')
}

async function summarize(document: RagDocumentItem) {
  analyzeName.value = document.title
  analysis.value = null
  analyzeError.value = ''
  analyzeOpen.value = true
  analyzeLoading.value = true
  try {
    analysis.value = await summarizeRagDocument(document.id)
  } catch (err) {
    analyzeError.value = err instanceof Error ? err.message : '归纳失败'
  } finally {
    analyzeLoading.value = false
  }
}

async function deposit(document: RagDocumentItem) {
  await depositRagDocument(document.id)
  toast.success('已沉淀为记忆')
}

/** 删除整个文件：库里对应的全部章节一并删掉，避免留下半份资料 */
async function removeFile() {
  const ok = await confirm({
    title: '删除文件',
    message: `确认删除「${fileName.value}」？将同时删除它的 ${sections.value.length} 个章节与全部分片。`,
    tone: 'danger',
    confirmText: '删除',
  })
  if (!ok) return
  for (const section of sections.value) {
    await deleteRagDocument(section.id)
  }
  toast.success('文件已删除')
  back()
}
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader :title="fileName || '资料详情'" description="分片、原文与原始文件">
      <template #actions>
        <Button @click="back"><ArrowLeft class="back-icon" />返回知识库</Button>
      </template>
    </PageHeader>

    <Alert v-if="error" tone="danger" :title="error" />

    <FileDetailPanel
      v-if="sections.length"
      :file-name="fileName"
      :sections="sections"
      :focus-chunk-id="focusChunkId"
      :can-configure="canConfigure"
      @summarize="summarize"
      @deposit="deposit"
      @edit="(doc) => router.push({ path: '/rag-knowledge', query: { edit: doc.id } })"
      @delete-file="removeFile"
    />

    <PdfAnalyzeDialog
      v-model:open="analyzeOpen"
      mode="summarize"
      :file-name="analyzeName"
      :loading="analyzeLoading"
      :analysis="analysis"
      :error="analyzeError"
      :saving="false"
    />
  </div>
</template>

<style scoped>
.back-icon {
  width: 14px;
  height: 14px;
  margin-right: 5px;
}
</style>
