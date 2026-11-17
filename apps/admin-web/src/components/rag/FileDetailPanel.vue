<script setup lang="ts">
/**
 * 资料详情面板：一个**原始文件**的全部内容与分片。
 *
 * 作为独立二级页面的主体渲染，不再用抽屉——分片、原文与 PDF 原件都需要足够宽度，
 * 抽屉里读长文档和看 PDF 都太局促。
 *
 * 数据层的一份 `document` 其实是原始文件的一个章节（导入时按标题切段，受单文档
 * 12000 字符上限约束），所以这里接收的是同一文件下的一组章节，在界面上重新合成
 * 「一个文件」的视角——使用者关心的是"这份手册"，不是它被切成了几条记录。
 *
 * 分片与原文并列成两个视角：检索、图谱抽取、证据回链都发生在分片粒度上，
 * 看不到分片就无法回答"这条结论来自原文的哪一段"。
 *
 * 从知识图谱跳转过来时，会高亮并滚动到目标分片。
 */
import { computed, nextTick, ref, watch } from 'vue'
import { fetchRagDocumentChunks, type RagChunkItem, type RagDocumentItem } from '../../api/rag'
import { sourceTypeLabel } from '../../utils/labels'
import { Button, Tabs, Tag } from '../../ui'
import MarkdownBody from '../MarkdownBody.vue'

const props = defineProps<{
  /** 文件名（章节标题的公共前缀） */
  fileName: string
  /** 该文件下的全部章节，按导入顺序 */
  sections: RagDocumentItem[]
  /** 从图谱跳转过来时要定位的分片 id */
  focusChunkId?: string
  canConfigure: boolean
}>()

const emit = defineEmits<{
  (e: 'edit', document: RagDocumentItem): void
  (e: 'summarize', document: RagDocumentItem): void
  (e: 'deposit', document: RagDocumentItem): void
  /** 删除整个文件（它在库里的全部章节） */
  (e: 'delete-file'): void
}>()

interface SectionChunks {
  section: RagDocumentItem
  chunks: RagChunkItem[]
}

const tab = ref('chunks')
const loaded = ref<SectionChunks[]>([])
const loading = ref(false)
const chunkRefs = ref<Record<string, HTMLElement | null>>({})
/** 展开的分片。默认全部折叠：18 片 × 800 字符全展开会形成极长滚动，找不到重点 */
const expanded = ref<Set<string>>(new Set())

function toggleChunk(id: string) {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}

/** 折叠态的预览：压掉换行，避免目录类内容一行一条撑出很高的空白 */
function chunkPreview(content: string) {
  const flat = content.replace(/\s+/g, ' ').trim()
  return flat.length > 120 ? `${flat.slice(0, 120)}…` : flat
}

/** 单章节文件不必再显示章节标题，那只是重复文件名 */
const showSectionTitles = computed(() => props.sections.length > 1)

const totalChars = computed(() => props.sections.reduce((sum, s) => sum + s.content.length, 0))
const totalChunks = computed(() => loaded.value.reduce((sum, s) => sum + s.chunks.length, 0))
const sourceType = computed(() => props.sections[0]?.sourceType ?? '')

/** 原始文件地址：同一文件的各章节共享，取第一个非空即可 */
const sourceUrl = computed(() => props.sections.find((s) => s.sourceUrl)?.sourceUrl ?? '')
const sourceFileName = computed(() => props.sections.find((s) => s.sourceFileName)?.sourceFileName ?? '')

const tabs = computed(() => {
  const list = [
    { value: 'chunks', label: `分片${totalChunks.value ? ` ${totalChunks.value}` : ''}` },
    { value: 'raw', label: `原文${props.sections.length > 1 ? ` · ${props.sections.length} 节` : ''}` },
  ]
  if (sourceUrl.value) {
    list.push({ value: 'source', label: '原件' })
  }
  return list
})

/** 章节标题里去掉文件名前缀，只留章节本身 */
function sectionTitle(document: RagDocumentItem) {
  const rest = document.title.startsWith(props.fileName)
    ? document.title.slice(props.fileName.length).replace(/^\s*—\s*/, '')
    : document.title
  return rest || '正文'
}

async function load() {
  if (!props.sections.length) return
  loading.value = true
  loaded.value = []
  try {
    // 章节数最多二十几个，一次并发取完即可
    const results = await Promise.all(
      props.sections.map(async (section) => ({
        section,
        chunks: await fetchRagDocumentChunks(section.id),
      })),
    )
    loaded.value = results
  } finally {
    loading.value = false
  }
  if (props.focusChunkId) {
    tab.value = 'chunks'
    expanded.value = new Set([props.focusChunkId])
    await nextTick()
    chunkRefs.value[props.focusChunkId]?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }
}

watch(() => props.fileName, () => void load(), { immediate: true })

function setChunkRef(id: string, el: unknown) {
  chunkRefs.value[id] = el as HTMLElement | null
}
</script>

<template>
  <div class="dd">
      <div class="dd__meta">
        <Tag>{{ sourceTypeLabel(sourceType) }}</Tag>
        <span class="dd__stat">{{ totalChars.toLocaleString() }} 字符</span>
        <span v-if="showSectionTitles" class="dd__stat">{{ sections.length }} 节</span>
        <span v-if="totalChunks" class="dd__stat">{{ totalChunks }} 分片</span>
        <Button v-if="canConfigure" link variant="danger" @click="emit('delete-file')">删除文件</Button>
      </div>

      <Tabs v-model="tab" :tabs="tabs">
        <template #chunks>
          <p v-if="loading" class="dd__hint">加载分片…</p>
          <p v-else-if="!totalChunks" class="dd__hint">该文件尚未生成分片</p>
          <template v-else>
            <!-- 分片是"索引视角"：展示的必须是真正入库被检索的文本，不做 markdown 排版 -->
            <p class="dd__note">分片按原始文本展示（所见即入库内容）；需要阅读排版请切到「原文」。</p>
          </template>
          <div v-if="!loading && totalChunks" class="dd__sections">
            <section v-for="item in loaded" :key="item.section.id" class="dd__section">
              <h5 v-if="showSectionTitles" class="dd__section-title">
                {{ sectionTitle(item.section) }}
                <span class="dd__section-count">{{ item.chunks.length }} 片</span>
              </h5>
              <article
                v-for="chunk in item.chunks"
                :key="chunk.id"
                :ref="(el) => setChunkRef(chunk.id, el)"
                class="dd__chunk"
                :class="{ 'dd__chunk--focus': chunk.id === focusChunkId }"
              >
                <header class="dd__chunk-head" role="button" tabindex="0"
                        @click="toggleChunk(chunk.id)" @keyup.enter="toggleChunk(chunk.id)">
                  <span class="dd__chunk-no num">#{{ chunk.chunkIndex }}</span>
                  <span>{{ chunk.content.length }} 字符</span>
                  <span v-if="chunk.id === focusChunkId" class="dd__chunk-badge">图谱来源</span>
                  <span class="dd__spacer" />
                  <span class="dd__chunk-toggle">{{ expanded.has(chunk.id) ? '收起' : '展开' }}</span>
                </header>
                <p v-if="expanded.has(chunk.id)" class="dd__chunk-text">{{ chunk.content }}</p>
                <p v-else class="dd__chunk-preview">{{ chunkPreview(chunk.content) }}</p>
              </article>
            </section>
          </div>
        </template>

        <template #raw>
          <div class="dd__sections">
            <section v-for="section in sections" :key="section.id" class="dd__section">
              <h5 class="dd__section-title">
                {{ sectionTitle(section) }}
                <span class="dd__section-count">{{ section.content.length.toLocaleString() }} 字符</span>
                <span class="dd__section-ops">
                  <Button link @click="emit('summarize', section)">归纳</Button>
                  <template v-if="canConfigure">
                    <Button link @click="emit('deposit', section)">沉淀</Button>
                    <Button link @click="emit('edit', section)">编辑</Button>
                  </template>
                </span>
              </h5>
              <div class="dd__body"><MarkdownBody :source="section.content" /></div>
            </section>
          </div>
        </template>
        <template v-if="sourceUrl" #source>
          <div class="dd__source">
            <div class="dd__source-bar">
              <span class="dd__stat">{{ sourceFileName || '原始文件' }}</span>
              <span class="dd__spacer" />
              <a :href="sourceUrl" target="_blank" rel="noopener" class="dd__source-link">在新标签打开</a>
            </div>
            <!-- 浏览器内置 PDF 阅读器渲染原件：解析后的文本丢失了排版、插图位置与页码 -->
            <iframe :src="sourceUrl" class="dd__source-frame" title="原始文件预览" />
          </div>
        </template>
    </Tabs>
  </div>
</template>

<style scoped>
.dd {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.dd__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  font-size: 12.5px;
  color: var(--text-3);
}
.dd__stat {
  font-variant-numeric: tabular-nums;
}
.dd__spacer {
  flex: 1;
}
.dd__note {
  margin: 6px 0 0;
  font-size: 11.5px;
  color: var(--text-3);
}
.dd__hint {
  margin: 16px 0;
  font-size: 13px;
  color: var(--text-3);
}
.dd__sections {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 8px;
}
.dd__section-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 0 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-2);
}
.dd__section-count {
  font-size: 11px;
  font-weight: 400;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.dd__section-ops {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.dd__chunk {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 8px 11px;
  background: var(--surface);
  margin-bottom: 6px;
}
/* 从图谱跳过来的那一条：加边框和底色，不靠滚动位置让人自己找 */
.dd__chunk--focus {
  border-color: var(--accent);
  background: var(--accent-soft);
}
.dd__chunk-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 5px;
  font-size: 11.5px;
  color: var(--text-3);
  cursor: pointer;
}
.dd__chunk-head:focus-visible {
  outline: none;
  box-shadow: var(--ring);
  border-radius: var(--radius-sm);
}
.dd__chunk-toggle {
  color: var(--accent);
}
.dd__chunk-preview {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
}
.dd__chunk-no {
  font-weight: 600;
  color: var(--text-2);
}
.dd__chunk-badge {
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--accent);
  color: var(--accent-contrast);
  font-size: 10.5px;
}
.dd__chunk-text {
  margin: 0;
  max-height: 320px;
  overflow-y: auto;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
}
.dd__body {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
}
.dd__source {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 6px;
}
.dd__source-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  color: var(--text-3);
}
.dd__source-link {
  color: var(--accent);
  text-decoration: none;
  font-size: 12.5px;
}
.dd__source-link:hover {
  text-decoration: underline;
}
.dd__source-frame {
  width: 100%;
  height: 68vh;
  min-height: 480px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
}
.dd__raw {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
