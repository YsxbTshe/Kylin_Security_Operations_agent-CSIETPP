<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { useRouter } from 'vue-router'
import { fetchHosts, type HostSummary } from '../api/hosts'
import { Alert, Button, PageHeader, SectionCard, Tag } from '../ui'

/**
 * 逻辑/观测拓扑。
 * 2D(SVG) 与 3D(Three.js) 同源：相同的节点、边与状态色；无 WebGL 时自动降级到 2D，核心信息不丢。
 * 资产尚无 site/room/rack/uPosition，故明确称「逻辑拓扑」，不冒充物理机架视图。
 */
const router = useRouter()

const loading = ref(false)
const error = ref('')
const hosts = ref<HostSummary[]>([])
const mode = ref<'2d' | '3d'>('2d')
const webglOk = ref(true)
const selected = ref<HostSummary | null>(null)

const canvasHost = ref<HTMLDivElement | null>(null)

interface TopoNode {
  id: string
  label: string
  status: string
  host: HostSummary | null
  angle: number
}

const nodes = computed<TopoNode[]>(() => {
  const list = hosts.value
  return list.map((h, i) => ({
    id: h.nodeId,
    label: h.hostname || h.nodeId,
    status: (h.status || '').toUpperCase(),
    host: h,
    angle: list.length ? (i / list.length) * Math.PI * 2 : 0,
  }))
})

function statusColorHex(status: string): number {
  const s = (status || '').toUpperCase()
  if (s === 'ONLINE') return 0x46d89c
  if (s === 'OFFLINE') return 0xf25e5e
  return 0xffb347
}
function statusTone(status: string): 'ok' | 'danger' | 'warn' {
  const s = (status || '').toUpperCase()
  if (s === 'ONLINE') return 'ok'
  if (s === 'OFFLINE') return 'danger'
  return 'warn'
}

// --- 2D SVG 布局：中心控制平面 + 环形主机 ---
const VB = 600
const CENTER = VB / 2
const RING = 210
function node2d(n: TopoNode) {
  return { x: CENTER + RING * Math.cos(n.angle), y: CENTER + RING * Math.sin(n.angle) }
}

// --- 3D Three.js ---
let renderer: THREE.WebGLRenderer | null = null
let scene: THREE.Scene | null = null
let camera: THREE.PerspectiveCamera | null = null
let controls: OrbitControls | null = null
let raf = 0
let hostMeshes: THREE.Mesh[] = []
const raycaster = new THREE.Raycaster()
const pointer = new THREE.Vector2()

function detectWebgl(): boolean {
  try {
    const canvas = document.createElement('canvas')
    return !!(window.WebGLRenderingContext && (canvas.getContext('webgl') || canvas.getContext('experimental-webgl')))
  } catch {
    return false
  }
}

function disposeThree() {
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  controls?.dispose()
  controls = null
  if (renderer) {
    renderer.domElement.removeEventListener('pointerdown', onCanvasPointerDown)
    renderer.dispose()
    if (renderer.domElement.parentElement) renderer.domElement.parentElement.removeChild(renderer.domElement)
    renderer = null
  }
  scene = null
  camera = null
  hostMeshes = []
}

function initThree() {
  const el = canvasHost.value
  if (!el) return
  const width = el.clientWidth || 600
  const height = el.clientHeight || 460

  scene = new THREE.Scene()
  camera = new THREE.PerspectiveCamera(55, width / height, 0.1, 1000)
  camera.position.set(0, 5.5, 11)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setSize(width, height)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  el.appendChild(renderer.domElement)
  renderer.domElement.addEventListener('pointerdown', onCanvasPointerDown)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.autoRotate = true
  controls.autoRotateSpeed = 0.6

  scene.add(new THREE.AmbientLight(0xffffff, 0.85))
  const dir = new THREE.DirectionalLight(0x88bbff, 0.7)
  dir.position.set(4, 8, 6)
  scene.add(dir)

  // 中心控制平面节点
  const core = new THREE.Mesh(
    new THREE.IcosahedronGeometry(0.9, 1),
    new THREE.MeshStandardMaterial({ color: 0x00e5ff, emissive: 0x0a4a55, metalness: 0.3, roughness: 0.4 }),
  )
  scene.add(core)

  const radius = 5
  const list = nodes.value
  list.forEach((n, i) => {
    const angle = (i / Math.max(1, list.length)) * Math.PI * 2
    const x = radius * Math.cos(angle)
    const z = radius * Math.sin(angle)
    const mesh = new THREE.Mesh(
      new THREE.SphereGeometry(0.55, 24, 24),
      new THREE.MeshStandardMaterial({ color: statusColorHex(n.status), metalness: 0.2, roughness: 0.5 }),
    )
    mesh.position.set(x, 0, z)
    mesh.userData.nodeId = n.id
    scene!.add(mesh)
    hostMeshes.push(mesh)

    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(0, 0, 0), new THREE.Vector3(x, 0, z)]),
      new THREE.LineBasicMaterial({ color: 0x2a6a8a, transparent: true, opacity: 0.6 }),
    )
    scene!.add(line)
  })

  const animate = () => {
    raf = requestAnimationFrame(animate)
    controls?.update()
    if (renderer && scene && camera) renderer.render(scene, camera)
  }
  animate()
}

function onCanvasPointerDown(event: PointerEvent) {
  if (!renderer || !camera) return
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hits = raycaster.intersectObjects(hostMeshes)
  if (hits.length) {
    const id = hits[0].object.userData.nodeId as string
    selected.value = hosts.value.find((h) => h.nodeId === id) ?? null
  }
}

function onResize() {
  if (!renderer || !camera || !canvasHost.value) return
  const width = canvasHost.value.clientWidth
  const height = canvasHost.value.clientHeight
  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height)
}

function selectNode(host: HostSummary | null) {
  selected.value = host
}

function openDetail(host: HostSummary) {
  router.push(`/hosts/${encodeURIComponent(host.hostname)}`)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    hosts.value = await fetchHosts()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载主机拓扑失败'
  } finally {
    loading.value = false
  }
}

watch(mode, (m) => {
  if (m === '3d') {
    if (!webglOk.value) {
      mode.value = '2d'
      return
    }
    // 等 DOM 显示后再初始化
    requestAnimationFrame(() => initThree())
  } else {
    disposeThree()
  }
})

// hosts 变化时若在 3D，重建场景
watch(nodes, () => {
  if (mode.value === '3d' && webglOk.value) {
    disposeThree()
    requestAnimationFrame(() => initThree())
  }
})

onMounted(() => {
  webglOk.value = detectWebgl()
  window.addEventListener('resize', onResize)
  void load()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  disposeThree()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="逻辑拓扑" description="主机与控制平面的连接拓扑" />

    <Alert v-if="error" tone="danger" :title="error" />
    <Alert v-if="!webglOk" tone="warn" title="当前环境不支持 WebGL，已切换为 2D 视图" />

    <SectionCard title="拓扑" flush>
      <template #actions>
        <div class="topo-toolbar">
          <div class="seg">
            <button :class="{ active: mode === '2d' }" @click="mode = '2d'">2D</button>
            <button :class="{ active: mode === '3d' }" :disabled="!webglOk" @click="mode = '3d'">3D</button>
          </div>
          <div class="legend">
            <span><i class="dot dot--ok" />在线</span>
            <span><i class="dot dot--warn" />告警/降级</span>
            <span><i class="dot dot--danger" />离线</span>
          </div>
        </div>
      </template>

      <div class="topo-body">
        <div class="topo-stage">
          <!-- 2D SVG -->
          <svg v-show="mode === '2d'" :viewBox="`0 0 ${VB} ${VB}`" class="topo-svg" preserveAspectRatio="xMidYMid meet">
            <line
              v-for="n in nodes"
              :key="`l-${n.id}`"
              :x1="CENTER" :y1="CENTER"
              :x2="node2d(n).x" :y2="node2d(n).y"
              class="edge"
            />
            <g class="center-node">
              <circle :cx="CENTER" :cy="CENTER" r="34" />
              <text :x="CENTER" :y="CENTER + 4">控制平面</text>
            </g>
            <g
              v-for="n in nodes"
              :key="`n-${n.id}`"
              class="host-node"
              :class="{ selected: selected?.nodeId === n.id }"
              @click="selectNode(n.host)"
              @dblclick="n.host && openDetail(n.host)"
            >
              <circle :cx="node2d(n).x" :cy="node2d(n).y" r="22" :class="`fill--${statusTone(n.status)}`" />
              <text :x="node2d(n).x" :y="node2d(n).y + 38">{{ n.label }}</text>
            </g>
            <text v-if="!nodes.length" :x="CENTER" :y="CENTER + 70" class="empty-text">暂无受管主机</text>
          </svg>

          <!-- 3D Three.js -->
          <div v-show="mode === '3d'" ref="canvasHost" class="topo-canvas" />
        </div>

        <aside class="topo-info">
          <div v-if="selected" class="info">
            <div class="info-hd">
              <strong>{{ selected.hostname }}</strong>
              <Tag :tone="statusTone(selected.status)">{{ selected.status }}</Tag>
            </div>
            <dl>
              <div><dt>节点</dt><dd class="num">{{ selected.nodeId }}</dd></div>
              <div><dt>IP</dt><dd class="num">{{ selected.ip || '—' }}</dd></div>
              <div><dt>系统</dt><dd>{{ selected.osVersion || '—' }}</dd></div>
              <div><dt>CPU</dt><dd class="num">{{ selected.cpuUsage || '—' }}</dd></div>
              <div><dt>Native Guard</dt><dd>{{ selected.nativeGuardStatus || '—' }}</dd></div>
              <div><dt>gRPC</dt><dd>{{ selected.grpcConnected ? '已连接' : '未连接' }}</dd></div>
            </dl>
            <Button size="sm" variant="subtle" @click="openDetail(selected)">查看主机详情</Button>
          </div>
          <div v-else class="info-empty">点击节点查看主机信息，双击进入主机详情。</div>
        </aside>
      </div>
    </SectionCard>
  </div>
</template>

<style scoped>
.topo-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
}
.seg {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}
.seg button {
  padding: 4px 14px;
  border: none;
  background: var(--surface-2);
  color: var(--text-2);
  cursor: pointer;
  font-size: 13px;
}
.seg button.active {
  background: var(--accent);
  color: #fff;
}
.seg button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.legend {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--text-3);
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}
.dot--ok { background: #46d89c; }
.dot--warn { background: #ffb347; }
.dot--danger { background: #f25e5e; }
.topo-body {
  display: grid;
  grid-template-columns: 1fr 260px;
  gap: 12px;
  min-height: 480px;
}
.topo-stage {
  position: relative;
  min-height: 480px;
  border-radius: var(--radius);
  background: radial-gradient(circle at 50% 40%, color-mix(in srgb, var(--accent) 8%, transparent), transparent 60%), var(--surface-2);
  border: 1px solid var(--border);
  overflow: hidden;
}
.topo-svg { width: 100%; height: 480px; }
.topo-canvas { width: 100%; height: 480px; }
.edge { stroke: color-mix(in srgb, var(--accent) 40%, var(--border)); stroke-width: 1.5; }
.center-node circle { fill: color-mix(in srgb, var(--accent) 22%, var(--surface)); stroke: var(--accent); stroke-width: 2; }
.center-node text { fill: var(--text); font-size: 13px; text-anchor: middle; font-weight: 600; }
.host-node { cursor: pointer; }
.host-node circle { stroke: var(--surface); stroke-width: 2; transition: r .15s ease; }
.host-node.selected circle { stroke: var(--accent); stroke-width: 3; }
.host-node text { fill: var(--text-2); font-size: 12px; text-anchor: middle; }
.fill--ok { fill: #46d89c; }
.fill--warn { fill: #ffb347; }
.fill--danger { fill: #f25e5e; }
.empty-text { fill: var(--text-3); font-size: 14px; text-anchor: middle; }
.topo-info {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
  padding: 14px;
}
.info { display: flex; flex-direction: column; gap: 12px; }
.info-hd { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.info dl { margin: 0; display: flex; flex-direction: column; gap: 8px; }
.info dl div { display: flex; flex-direction: column; gap: 2px; }
.info dt { font-size: 12px; color: var(--text-3); }
.info dd { margin: 0; font-size: 13.5px; }
.info-empty { color: var(--text-3); font-size: 13px; line-height: 1.6; }
@media (max-width: 900px) {
  .topo-body { grid-template-columns: 1fr; }
}
</style>
