<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import {
  createKgEdge,
  deleteKgEdge,
  fetchRagGraph,
  fetchRagGraphNeighbors,
  fetchRagGraphStats,
  fetchRagNodeChunks,
  rebuildRagGraph,
  updateKgEdge,
  type RagNodeChunkItem,
  type RagGraphEdge,
  type RagGraphNode,
  type RagGraphStats,
  type SaveKgEdgePayload,
} from '../api/rag'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { NODE_TYPE_LABELS, RELATION_LABELS, relationLabel } from '../utils/labels'
import { Button, Dialog, FormField, Input, PageHeader, SectionCard, Select, Tag, confirm, toast } from '../ui'
import NodePicker from '../components/rag/NodePicker.vue'

const authStore = useAuthStore()
const router = useRouter()
const canConfigure = () => authStore.hasPermission('platform:configure')

const TYPE_LABELS = NODE_TYPE_LABELS
const TYPE_COLORS: Record<string, string> = {
  CONCEPT: '#6b7cff', COMPONENT: '#3aa6a0', SERVICE: '#e0883a', COMMAND: '#8a6bd4',
  TOOL: '#3a8fd6', FILE_PATH: '#9aa0ad', CONFIG: '#d4b03a', RISK_LEVEL: '#d65f5f',
  POLICY: '#5fae6a', PROCEDURE: '#d67ba6', THREAT: '#c0392b', ACCOUNT: '#46b1a8',
}
const RELATION_OPTIONS = Object.entries(RELATION_LABELS).map(([value, label]) => ({ value, label }))
const TYPES = Object.keys(TYPE_LABELS)
const FOCUS_ZOOM = 1.5

const chartEl = ref<HTMLDivElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)
const loading = ref(false)
const rebuilding = ref(false)
const stats = ref<RagGraphStats | null>(null)
const selected = ref<RagGraphNode | null>(null)

const nodeMap = new Map<string, RagGraphNode>()
const edges: RagGraphEdge[] = []
const frozen = ref(false)
const frozenPos = new Map<string, [number, number]>()
let highlightSet: Set<string> | null = null

/* 探索：搜索定位、类型筛选、N 跳下钻 */
const search = ref('')
const typeFilter = ref('')
const scope = ref<{ node: string; hops: number } | null>(null)
/**
 * 全量渲染近千个节点会糊成一团，力导向也收敛不了。这里分两级控制密度：
 *   nodeLimit  —— 只取显著度最高的 N 个节点参与布局
 *   labelTop   —— 只给其中最重要的少数显示常驻标签，其余靠 hover / 选中显示
 * 想看更细的结构应该用「下钻邻域」，而不是把整张图铺开。
 */
const nodeLimit = ref(120)
const nodeLimitOptions = [
  { value: '80', label: '80 个节点' },
  { value: '120', label: '120 个节点' },
  { value: '200', label: '200 个节点' },
  { value: '400', label: '400 个节点' },
]
const LABEL_TOP = 28

/** 供选择器使用的节点清单 */
const pickerNodes = computed(() =>
  [...nodeMap.values()].map((n) => ({ id: n.id, name: n.name, nodeType: n.nodeType })),
)
/** 常驻标签的节点集合，按显著度取前 LABEL_TOP 个 */
let labelSet = new Set<string>()

const hopOptions = [
  { value: '1', label: '1 跳' },
  { value: '2', label: '2 跳' },
  { value: '3', label: '3 跳' },
]

/* 该实体的来源分片（证据回链） */
const citing = ref<RagNodeChunkItem[]>([])
const citingLoading = ref(false)

/* 关系编辑 */
const edgeDialogOpen = ref(false)
const editingEdgeId = ref('')
const edgeForm = reactive<SaveKgEdgePayload>({
  sourceNode: '',
  relation: 'RELATED_TO',
  targetNode: '',
  evidence: '',
})
const edgeFormInvalid = computed(() => {
  if (!edgeForm.relation.trim()) return true
  // 编辑态只改关系与证据，端点不可变，因此不校验源/目标
  if (editingEdgeId.value) return false
  return !edgeForm.sourceNode?.trim() || !edgeForm.targetNode?.trim()
})

const searchHits = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return []
  return [...nodeMap.values()]
    .filter((node) => node.name.toLowerCase().includes(keyword))
    .slice(0, 8)
})
let camera: { center?: [number, number]; zoom: number } = { zoom: 1 }
let freezeTimer: number | undefined

function symbolSize(node: RagGraphNode) {
  return 10 + Math.min(22, (node.salience || 0) * 16 + Math.min(node.mentionCount, 8) * 1.2)
}

function adjacencyOf(id: string): Set<string> {
  const set = new Set<string>([id])
  for (const e of edges) {
    if (e.source === id) set.add(e.target)
    if (e.target === id) set.add(e.source)
  }
  return set
}

function buildOption(): echarts.EChartsOption {
  const categories = TYPES.map((t) => ({ name: TYPE_LABELS[t], itemStyle: { color: TYPE_COLORS[t] } }))
  const present = new Set(nodeMap.keys())
  const hl = highlightSet
  const nodes: any[] = [...nodeMap.values()].map((n) => {
    const on = !hl || hl.has(n.id)
    const node: any = {
      id: n.id,
      name: n.id,
      cnName: n.name,
      nodeType: n.nodeType,
      category: Math.max(0, TYPES.indexOf(n.nodeType)),
      symbolSize: symbolSize(n),
      value: n.salience,
      itemStyle: { opacity: on ? 1 : 0.08 },
      // 聚焦时只标注邻域；未聚焦时只标注最显著的少数，其余 hover 才出标签
      label: { show: hl ? on : labelSet.has(n.id), opacity: on ? 1 : 0.05 },
    }
    if (frozen.value) {
      const p = frozenPos.get(n.id)
      if (p) {
        node.x = p[0]
        node.y = p[1]
      }
    }
    return node
  })
  const links: any[] = edges
    .filter((e) => present.has(e.source) && present.has(e.target))
    .map((e) => {
      const on = !hl || (hl.has(e.source) && hl.has(e.target))
      return {
        source: e.source,
        target: e.target,
        value: e.relation,
        lineStyle: { width: on ? 1 + Math.min(4, e.weight) : 1, opacity: on ? 0.55 : 0.03 },
      }
    })
  const series: any = {
    type: 'graph',
    layout: frozen.value ? 'none' : 'force',
    roam: true,
    draggable: true,
    categories,
    nodes,
    links,
    zoom: camera.zoom,
    label: {
      show: true,
      position: 'right',
      color: 'var(--text)',
      fontSize: 11,
      formatter: (p: any) => p.data.cnName,
    },
    emphasis: { focus: 'adjacency', label: { show: true }, lineStyle: { width: 3 } },
    lineStyle: { color: 'source', curveness: 0.08 },
    // 节点越多越要加大斥力和边长，否则会挤成一个球
    force: {
      repulsion: Math.round(260 * Math.max(1, Math.sqrt(nodes.length / 60))),
      edgeLength: nodes.length > 150 ? [90, 260] : [60, 170],
      gravity: nodes.length > 150 ? 0.04 : 0.06,
      friction: 0.16,
    },
    scaleLimit: { min: 0.25, max: 5 },
    animationDurationUpdate: 650,
    animationEasingUpdate: 'cubicInOut',
  }
  if (camera.center) series.center = camera.center
  return {
    backgroundColor: 'transparent',
    tooltip: {
      formatter: (p: any) =>
        p.dataType === 'edge'
          ? relationLabel(p.data.value)
          : `${p.data.cnName}<br/><span style="opacity:.7">${TYPE_LABELS[p.data.nodeType] || p.data.nodeType}</span>`,
    },
    legend: [{ data: TYPES.map((t) => TYPE_LABELS[t]), textStyle: { color: 'var(--text)' }, type: 'scroll', top: 0 }],
    series: [series],
  }
}

function render() {
  chart.value?.setOption(buildOption(), { notMerge: true })
}

// Capture force-layout positions once it has settled, then switch to a static layout so
// subsequent interactions never re-run the force simulation (no more nodes flying around).
function freezeLayout() {
  if (!chart.value || frozen.value) return
  try {
    const data: any = (chart.value as any).getModel().getSeriesByIndex(0).getData()
    const ids = [...nodeMap.keys()]
    ids.forEach((id, i) => {
      const p = data.getItemLayout(i)
      if (p && Number.isFinite(p[0]) && Number.isFinite(p[1])) frozenPos.set(id, [p[0], p[1]])
    })
  } catch {
    /* keep force layout if positions are unavailable */
    return
  }
  if (frozenPos.size > 0) {
    frozen.value = true
    render()
  }
}

function highlightNode(node: RagGraphNode) {
  selected.value = node
  highlightSet = adjacencyOf(node.id)
  void loadCiting(node.id)
  const p = frozenPos.get(node.id)
  if (p) camera = { center: [p[0], p[1]], zoom: FOCUS_ZOOM } // smooth camera move to the node
  render()
}

function clearHighlight() {
  selected.value = null
  highlightSet = null
  camera = { zoom: 1, center: centroid() }
  render()
}

function centroid(): [number, number] | undefined {
  if (frozenPos.size === 0) return undefined
  let sx = 0
  let sy = 0
  frozenPos.forEach(([x, y]) => {
    sx += x
    sy += y
  })
  return [sx / frozenPos.size, sy / frozenPos.size]
}

/** 从某个节点下钻：只保留它的 N 跳邻域，作为一个独立的探索作用域 */
async function drillInto(node: RagGraphNode, hops = 1) {
  loading.value = true
  try {
    const subgraph = await fetchRagGraphNeighbors(node.name, hops)
    applyGraph(subgraph.nodes, subgraph.edges)
    scope.value = { node: node.name, hops }
    const focused = [...nodeMap.values()].find((item) => item.id === node.id) ?? null
    selected.value = focused
    highlightSet = null
    render()
    window.clearTimeout(freezeTimer)
    freezeTimer = window.setTimeout(freezeLayout, 2200)
    void loadCiting(node.id)
  } catch {
    toast.error('加载邻域失败')
  } finally {
    loading.value = false
  }
}

async function changeHops(value: string) {
  const node = selected.value
  if (!node) return
  await drillInto(node, Number(value) || 1)
}

function exitScope() {
  scope.value = null
  citing.value = []
  void loadGraph()
}

/**
 * 取该实体的来源分片。
 *
 * 走抽取时写入的绑定关系，而不是拿实体名再检索一遍——后者只是近似猜测，
 * 会把并未参与该实体抽取的片段也带进来，作为"证据"是不成立的。
 */
/** 「文档名 — 章节名」中的章节部分；没有分隔符时回退用整个标题 */
function sectionOf(title: string) {
  const parts = (title || '').split(' — ')
  return parts.length > 1 ? parts[parts.length - 1] : title
}

/** 同上的文档部分，作为次要信息 */
function documentOf(title: string) {
  const parts = (title || '').split(' — ')
  return parts.length > 1 ? parts.slice(0, -1).join(' — ') : ''
}

/** 跳到知识库并定位到该分片：图谱不是终点，要能回到原文 */
function openInKnowledgeBase(chunk: RagNodeChunkItem) {
  void router.push({
    path: '/rag-knowledge',
    query: { doc: chunk.documentId, chunk: chunk.chunkId },
  })
}

async function loadCiting(nodeId: string) {
  citingLoading.value = true
  citing.value = []
  try {
    citing.value = await fetchRagNodeChunks(nodeId, 8)
  } catch {
    // 取证据失败不影响图谱本身，静默处理即可
  } finally {
    citingLoading.value = false
  }
}

function applyGraph(nodes: RagGraphNode[], graphEdges: RagGraphEdge[]) {
  nodeMap.clear()
  edges.length = 0
  frozenPos.clear()
  frozen.value = false
  camera = { zoom: 1 }
  nodes.forEach((node) => nodeMap.set(node.id, node))
  edges.push(...graphEdges)
  labelSet = new Set(
    [...nodeMap.values()]
      .sort((a, b) => (b.salience || 0) - (a.salience || 0) || b.mentionCount - a.mentionCount)
      .slice(0, LABEL_TOP)
      .map((node) => node.id),
  )
}

function focusByName(node: RagGraphNode) {
  search.value = ''
  highlightNode(node)
}

/* ── 关系编辑 ─────────────────────────────────────────────── */
function openEdgeDialog(edge?: RagGraphEdge) {
  editingEdgeId.value = edge?.id ?? ''
  edgeForm.sourceNode = edge ? nodeName(edge.source) : selected.value?.name ?? ''
  edgeForm.relation = edge?.relation ?? 'RELATED_TO'
  edgeForm.targetNode = edge ? nodeName(edge.target) : ''
  edgeForm.evidence = edge?.evidence ?? ''
  edgeDialogOpen.value = true
}

async function submitEdge() {
  if (edgeFormInvalid.value) {
    toast.warning('请填写源节点、关系和目标节点')
    return
  }
  const payload: SaveKgEdgePayload = {
    sourceNode: edgeForm.sourceNode?.trim(),
    relation: edgeForm.relation.trim(),
    targetNode: edgeForm.targetNode?.trim(),
    evidence: edgeForm.evidence?.trim() || undefined,
  }
  try {
    if (editingEdgeId.value) {
      await updateKgEdge(editingEdgeId.value, payload)
      toast.success('关系已更新')
    } else {
      await createKgEdge(payload)
      toast.success('关系已添加')
    }
    edgeDialogOpen.value = false
    await loadGraph()
  } catch {
    toast.error('保存关系失败')
  }
}

async function removeEdge(edge: RagGraphEdge) {
  const ok = await confirm({
    title: '删除关系',
    message: `确认删除「${nodeName(edge.source)} ${edge.relation} ${nodeName(edge.target)}」？`,
    tone: 'danger',
    confirmText: '删除',
  })
  if (!ok) return
  try {
    await deleteKgEdge(edge.id)
    toast.success('关系已删除')
    await loadGraph()
  } catch {
    toast.error('删除失败')
  }
}

async function loadGraph() {
  loading.value = true
  frozen.value = false
  highlightSet = null
  camera = { zoom: 1 }
  selected.value = null
  scope.value = null
  citing.value = []
  frozenPos.clear()
  try {
    const [graph, s] = await Promise.all([
      fetchRagGraph({ limit: nodeLimit.value, nodeType: typeFilter.value || undefined }),
      fetchRagGraphStats(),
    ])
    applyGraph(graph.nodes, graph.edges)
    stats.value = s
    render()
    window.clearTimeout(freezeTimer)
    freezeTimer = window.setTimeout(freezeLayout, 3200)
  } catch (error) {
    toast.error('加载知识图谱失败')
  } finally {
    loading.value = false
  }
}

async function rebuild() {
  rebuilding.value = true
  try {
    await rebuildRagGraph()
    toast.success('已触发知识图谱重建')
    await loadGraph()
  } catch (error) {
    toast.error('重建失败')
  } finally {
    rebuilding.value = false
  }
}

function changeNodeLimit(value: string) {
  nodeLimit.value = Number(value) || 120
  void loadGraph()
}

function toggleType(type: string) {
  typeFilter.value = typeFilter.value === type ? '' : type
  void loadGraph()
}

function onResize() {
  chart.value?.resize()
}

/**
 * 详情侧栏是 v-if 出现的，它一出现图表容器就变窄——但 echarts 的 canvas 尺寸是
 * init 时固定下来的，不会自己跟随容器。不在这里 resize，canvas 会保持原宽度溢出，
 * 把侧栏挤出可视区，表现就是"点了节点什么也没看到"。
 */
watch(selected, () => {
  void nextTick(() => chart.value?.resize())
})

function selectedEdges() {
  if (!selected.value) return []
  const id = selected.value.id
  return edges.filter((e) => e.source === id || e.target === id)
}
function nodeName(id: string) {
  return nodeMap.get(id)?.name || id
}

onMounted(() => {
  if (!chartEl.value) return
  chart.value = echarts.init(chartEl.value)
  chart.value.on('click', (params: any) => {
    if (params.dataType === 'node') {
      const node = nodeMap.get(params.data.id)
      if (node) highlightNode(node)
    }
  })
  // click on empty canvas resets focus
  chart.value.getZr().on('click', (e: any) => {
    if (!e.target) clearHighlight()
  })
  window.addEventListener('resize', onResize)
  loadGraph()
})

onBeforeUnmount(() => {
  window.clearTimeout(freezeTimer)
  window.removeEventListener('resize', onResize)
  chart.value?.dispose()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader
      title="知识图谱"
      description="从官方麒麟知识库自动抽取的实体—关系图谱：点击节点聚焦邻居，可下钻到 N 跳邻域并查看引用它的知识片段。"
    >
      <template #actions>
        <Button v-if="canConfigure()" @click="openEdgeDialog()">添加关系</Button>
        <Button v-if="canConfigure()" :loading="rebuilding" @click="rebuild">重建图谱</Button>
        <Button variant="subtle" @click="loadGraph">刷新</Button>
      </template>
    </PageHeader>

    <SectionCard flush>
      <!-- 工具条：搜索定位 + 类型筛选 + 作用域面包屑 -->
      <div class="kg-toolbar">
        <div class="kg-search">
          <Input v-model="search" placeholder="搜索实体名称…" />
          <ul v-if="searchHits.length" class="kg-search-hits">
            <li v-for="hit in searchHits" :key="hit.id" @click="focusByName(hit)">
              <span class="dot" :style="{ background: TYPE_COLORS[hit.nodeType] || '#888' }" />
              {{ hit.name }}
              <span class="text-muted">{{ TYPE_LABELS[hit.nodeType] || hit.nodeType }}</span>
            </li>
          </ul>
        </div>

        <div class="kg-density">
          <span class="text-muted">显示密度</span>
          <Select
            :model-value="String(nodeLimit)"
            :options="nodeLimitOptions"
            @update:model-value="changeNodeLimit($event as string)"
          />
        </div>

        <div v-if="scope" class="kg-scope">
          <span>已下钻：<b>{{ scope.node }}</b> 的 {{ scope.hops }} 跳邻域</span>
          <Select
            :model-value="String(scope.hops)"
            :options="hopOptions"
            @update:model-value="changeHops($event as string)"
          />
          <Button link @click="exitScope">返回全图</Button>
        </div>
      </div>

      <div class="kg-stats" v-if="stats">
        <Tag>节点 {{ stats.nodeCount }}</Tag>
        <Tag>关系 {{ stats.edgeCount }}</Tag>
        <!-- 类型标签可点：点一次筛选该类型，再点取消 -->
        <button
          v-for="(count, type) in stats.nodesByType"
          :key="type"
          type="button"
          class="kg-type-chip"
          :class="{ 'kg-type-chip--on': typeFilter === type }"
          @click="toggleType(String(type))"
        >
          <span class="dot" :style="{ background: TYPE_COLORS[type] || '#888' }" />
          {{ TYPE_LABELS[type] || type }} {{ count }}
        </button>
        <span v-if="selected" class="kg-focus-tip text-muted">已聚焦「{{ selected.name }}」 · 点空白处复位</span>
      </div>

      <div class="kg-wrap">
        <div ref="chartEl" class="kg-chart" />
        <aside v-if="selected" class="kg-detail">
          <div class="kg-detail-head">
            <span class="dot" :style="{ background: TYPE_COLORS[selected.nodeType] || '#888' }" />
            <strong>{{ selected.name }}</strong>
            <Tag>{{ TYPE_LABELS[selected.nodeType] || selected.nodeType }}</Tag>
          </div>
          <p v-if="selected.description" class="text-muted">{{ selected.description }}</p>

          <div class="kg-detail-actions">
            <Button link @click="drillInto(selected, 1)">下钻邻域</Button>
            <Button v-if="canConfigure()" link @click="openEdgeDialog()">加关系</Button>
          </div>

          <!-- 证据回链：这个实体是从哪些分片里抽出来的（抽取时的真实绑定，非二次检索） -->
          <div class="kg-detail-cite">
            <div class="kg-detail-label">
              来源分片<span v-if="citing.length">（{{ citing.length }}）</span>
              <span v-if="citingLoading" class="text-muted">加载中…</span>
            </div>
            <ul v-if="citing.length">
              <li
                v-for="chunk in citing"
                :key="chunk.chunkId"
                class="kg-cite-item"
                role="button"
                tabindex="0"
                title="在知识库中查看该分片"
                @click="openInKnowledgeBase(chunk)"
                @keyup.enter="openInKnowledgeBase(chunk)"
              >
                <div class="kg-cite-head">
                  <!-- 章节名做主行：同一文档的多个片段只有这里不同，截断它等于让几条看起来一样 -->
                  <span class="kg-cite-title">{{ sectionOf(chunk.documentTitle || chunk.title) }}</span>
                  <span class="kg-cite-meta text-muted">提及 {{ chunk.weight.toFixed(0) }} 次</span>
                </div>
                <div v-if="documentOf(chunk.documentTitle)" class="kg-cite-doc text-muted">
                  {{ documentOf(chunk.documentTitle) }}
                </div>
                <div class="kg-cite-text text-muted">{{ chunk.content.slice(0, 120) }}…</div>
              </li>
            </ul>
            <p v-else-if="!citingLoading" class="text-muted">该实体没有记录来源分片</p>
          </div>
          <div class="kg-detail-edges">
            <div class="kg-detail-label">关系（{{ selectedEdges().length }}）</div>
            <ul>
              <li v-for="edge in selectedEdges()" :key="edge.id" class="kg-edge">
                <span class="kg-edge-main">
                  <span class="rel">{{ relationLabel(edge.relation) }}</span>
                  <span class="text-muted">
                    {{ edge.source === selected.id ? '→ ' + nodeName(edge.target) : '← ' + nodeName(edge.source) }}
                  </span>
                </span>
                <span v-if="canConfigure()" class="kg-edge-ops">
                  <Button link @click="openEdgeDialog(edge)">改</Button>
                  <Button link variant="danger" @click="removeEdge(edge)">删</Button>
                </span>
              </li>
            </ul>
          </div>

        </aside>
      </div>
    </SectionCard>

    <Dialog v-model:open="edgeDialogOpen" :title="editingEdgeId ? '编辑关系' : '添加关系'" width="620px">
      <div class="kg-form">
        <p v-if="editingEdgeId" class="kg-form-hint">编辑时只能修改关系与证据；要换端点请新建一条后删除旧的。</p>
        <div class="kg-form-grid">
          <FormField label="源节点">
            <NodePicker
              v-model="edgeForm.sourceNode"
              :nodes="pickerNodes"
              :type-labels="TYPE_LABELS"
              :type-colors="TYPE_COLORS"
              :disabled="!!editingEdgeId"
            />
          </FormField>
          <FormField label="关系">
            <Select v-model="edgeForm.relation" :options="RELATION_OPTIONS" />
          </FormField>
          <FormField label="目标节点">
            <NodePicker
              v-model="edgeForm.targetNode"
              :nodes="pickerNodes"
              :type-labels="TYPE_LABELS"
              :type-colors="TYPE_COLORS"
              :disabled="!!editingEdgeId"
            />
          </FormField>
        </div>
        <FormField label="证据（可选）">
          <Input v-model="edgeForm.evidence" type="textarea" :rows="3" />
        </FormField>
      </div>
      <template #footer>
        <Button @click="edgeDialogOpen = false">取消</Button>
        <Button variant="primary" :disabled="edgeFormInvalid" @click="submitEdge">保存</Button>
      </template>
    </Dialog>
  </div>
</template>

<style scoped>
.kg-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.kg-search {
  position: relative;
  width: 260px;
}
.kg-search-hits {
  position: absolute;
  z-index: 5;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  margin: 0;
  padding: 4px;
  list-style: none;
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--radius);
  box-shadow: var(--shadow-pop);
}
.kg-search-hits li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  cursor: pointer;
}
.kg-search-hits li:hover {
  background: var(--surface-2);
}
.kg-search-hits .text-muted {
  margin-left: auto;
  font-size: 11px;
}
.kg-density {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  white-space: nowrap;
}
.kg-scope {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  color: var(--text-2);
}
.kg-detail-actions {
  display: flex;
  gap: 12px;
  margin: 10px 0 4px;
}
.kg-edge {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kg-edge-main {
  flex: 1;
  min-width: 0;
}
.kg-edge-ops {
  display: flex;
  gap: 6px;
  flex: none;
}
.kg-detail-cite {
  margin-top: 14px;
}
.kg-detail-cite ul {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.kg-cite-item {
  padding: 6px 8px;
  margin: 0 -8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background .15s;
}
.kg-cite-item:hover {
  background: var(--surface-2);
}
.kg-cite-item:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}
.kg-cite-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.kg-cite-title {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kg-cite-meta {
  flex: none;
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
}
.kg-cite-doc {
  font-size: 10.5px;
  line-height: 1.5;
  margin: 1px 0 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kg-cite-text {
  font-size: 11.5px;
  line-height: 1.6;
}
.kg-form-hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-3);
}
.kg-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.kg-form-grid {
  display: grid;
  grid-template-columns: 1fr 200px 1fr;
  gap: 12px;
  align-items: start;
}
.kg-stats {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.kg-focus-tip {
  margin-left: auto;
  font-size: 12px;
}
.kg-type-chip {
  display: inline-flex;
  align-items: center;
  padding: 3px 9px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  font-size: 12px;
  color: var(--text-2);
  cursor: pointer;
  transition: border-color .15s, background .15s;
}
.kg-type-chip:hover {
  border-color: var(--border-2);
}
.kg-type-chip--on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
.kg-type-chip .dot,
.kg-detail-head .dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: middle;
}
.kg-wrap {
  display: flex;
  min-height: 600px;
}
.kg-chart {
  flex: 1;
  min-width: 0;
  height: 70vh;
  min-height: 600px;
}
.kg-detail {
  width: 320px;
  flex: none;
  /* 必须给出确定高度，否则 overflow-y 不生效：侧栏会被内容撑高，
     关系一多就把「来源分片」顶到页面很下面，看起来像"没有内容"。 */
  height: 70vh;
  min-height: 600px;
  border-left: 1px solid var(--border);
  padding: 16px;
  overflow-y: auto;
}
/* 关系可能几十条，单独限高滚动，不让它挤占来源分片 */
.kg-detail-edges ul {
  max-height: 190px;
  overflow-y: auto;
}
.kg-detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.kg-detail-edges {
  margin-top: 12px;
}
.kg-detail-label {
  font-weight: 600;
  margin-bottom: 6px;
}
.kg-detail-edges ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.kg-detail-edges .rel {
  display: inline-block;
  font-size: 12px;
  color: var(--accent);
  margin-right: 6px;
}
</style>
