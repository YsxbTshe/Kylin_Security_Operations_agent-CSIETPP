<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import Icon from '../components/Icon.vue'

const canvasHost = ref(null)
const mode3d = ref(true)
const fallbackReason = ref('')
const selected = ref(null)
const filter = ref('全部')

const hosts = Array.from({ length: 24 }, (_, i) => ({
  id: `prod-kylin-${String(i + 1).padStart(2, '0')}`,
  rack: `R${Math.floor(i / 6) + 1}`,
  slot: i % 6 + 1,
  status: [5, 17].includes(i) ? 'warning' : i === 11 ? 'offline' : 'healthy',
  group: i < 12 ? '核心业务组' : i < 18 ? '数据服务组' : '安全基础设施组',
  cpu: 23 + ((i * 13) % 51),
  agent: i === 11 ? 'disconnected' : 'v0.9.4',
  task: [1, 8, 14, 19].includes(i),
}))

const topologyEdges = hosts.flatMap((host) => [
  { from: 'control-plane', to: host.id, type: 'MTLS_CONNECTED', source: 'demo-heartbeat-fixture', observedAt: '2026-08-05T14:32:00+08:00', confidence: 1 },
  ...(host.task ? [{ from: 'task-demo-0842', to: host.id, type: 'EXECUTES_ON', source: 'demo-task-event-fixture', observedAt: '2026-08-05T14:32:18+08:00', confidence: 1 }] : []),
])

const visibleHosts = computed(() => filter.value === '全部' ? hosts : hosts.filter((host) => host.group === filter.value))
const selectedEdges = computed(() => selected.value ? topologyEdges.filter((edge) => edge.to === selected.value.id) : [])
const statusLabel = { healthy: '健康', warning: '注意', offline: '离线' }
let renderer, scene, camera, controls, raf, resizeObserver, raycaster, pointer
const meshes = []
const colors = { healthy: 0x67b58b, warning: 0xd6a85f, offline: 0xd96c67 }

function addLabelSprite(text, position) {
  const canvas = document.createElement('canvas')
  canvas.width = 256; canvas.height = 64
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = 'rgba(17,19,21,.94)'; ctx.fillRect(0, 0, 256, 64)
  ctx.strokeStyle = 'rgba(154,162,168,.45)'; ctx.strokeRect(1, 1, 254, 62)
  ctx.fillStyle = '#d7dbde'; ctx.font = '26px monospace'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(text, 128, 32)
  const texture = new THREE.CanvasTexture(canvas)
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false }))
  sprite.position.copy(position); sprite.scale.set(2.7, .68, 1)
  scene.add(sprite)
}

function initScene() {
  if (!canvasHost.value || !mode3d.value) return
  try {
  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x090a0b)
  scene.fog = new THREE.FogExp2(0x090a0b, 0.026)
  const width = canvasHost.value.clientWidth; const height = canvasHost.value.clientHeight
  camera = new THREE.PerspectiveCamera(42, width / height, .1, 100)
  camera.position.set(12, 10, 17)
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2)); renderer.setSize(width, height)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  canvasHost.value.appendChild(renderer.domElement)
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true; controls.dampingFactor = .065; controls.target.set(0, 2.7, 0); controls.maxDistance = 30; controls.minDistance = 7

  scene.add(new THREE.HemisphereLight(0xd8dcdf, 0x08090a, 1.05))
  const key = new THREE.DirectionalLight(0xf0f2f3, 1.45); key.position.set(7, 12, 5); scene.add(key)
  const grid = new THREE.GridHelper(28, 28, 0x3a4044, 0x24282b); grid.material.opacity = .32; grid.material.transparent = true; scene.add(grid)
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(28, 20), new THREE.MeshStandardMaterial({ color: 0x111315, roughness: .86, metalness: .06, transparent: true, opacity: .88 }))
  floor.rotation.x = -Math.PI / 2; floor.position.y = -.015; scene.add(floor)

  const rackMaterial = new THREE.MeshStandardMaterial({ color: 0x2a2e31, metalness: .48, roughness: .52, transparent: true, opacity: .82 })
  const barGeo = new THREE.BoxGeometry(.08, 6, .08)
  const crossGeo = new THREE.BoxGeometry(2.75, .08, .08)
  for (let r = 0; r < 4; r++) {
    const x = (r - 1.5) * 3.55
    ;[-1.27, 1.27].forEach(dx => [-.58, .58].forEach(dz => {
      const bar = new THREE.Mesh(barGeo, rackMaterial); bar.position.set(x + dx, 3, dz); scene.add(bar)
    }))
    ;[.05, 5.95].forEach(y => [-.58, .58].forEach(z => {
      const cross = new THREE.Mesh(crossGeo, rackMaterial); cross.position.set(x, y, z); scene.add(cross)
    }))
    addLabelSprite(`R${r + 1}`, new THREE.Vector3(x, 6.45, 0))
  }

  const nodeGeo = new THREE.BoxGeometry(2.45, .68, 1)
  hosts.forEach((host, i) => {
    const rack = Math.floor(i / 6); const slot = i % 6
    const material = new THREE.MeshStandardMaterial({ color: 0x25292c, metalness: .42, roughness: .46, emissive: 0x67b58b, emissiveIntensity: 0 })
    const mesh = new THREE.Mesh(nodeGeo, material)
    mesh.position.set((rack - 1.5) * 3.55, .55 + slot * .9, 0)
    mesh.userData.host = host; scene.add(mesh); meshes.push(mesh)
    const face = new THREE.Mesh(new THREE.PlaneGeometry(2.15, .4), new THREE.MeshBasicMaterial({ color: 0x101214 }))
    face.position.set(mesh.position.x, mesh.position.y, .506); scene.add(face)
    const led = new THREE.Mesh(new THREE.CircleGeometry(.07, 18), new THREE.MeshBasicMaterial({ color: colors[host.status] }))
    led.position.set(mesh.position.x + .88, mesh.position.y, 1.02); scene.add(led)
    if (host.task) {
      const marker = new THREE.Mesh(new THREE.PlaneGeometry(.13, .13), new THREE.MeshBasicMaterial({ color: 0xd6a85f }))
      marker.position.set(mesh.position.x + .63, mesh.position.y, 1.02); scene.add(marker)
    }
  })

  const curve = new THREE.CatmullRomCurve3([new THREE.Vector3(-8, .1, 4), new THREE.Vector3(-3, .1, 2.6), new THREE.Vector3(3, .1, 2.6), new THREE.Vector3(8, .1, 4)])
  const path = new THREE.Mesh(new THREE.TubeGeometry(curve, 60, .022, 8, false), new THREE.MeshBasicMaterial({ color: 0x67b58b, transparent: true, opacity: .32 }))
  scene.add(path)

  raycaster = new THREE.Raycaster(); pointer = new THREE.Vector2()
  renderer.domElement.addEventListener('pointerdown', onPointer)
  resizeObserver = new ResizeObserver(resize); resizeObserver.observe(canvasHost.value)
  animate()
  } catch (error) {
    fallbackReason.value = '当前浏览器无法初始化 WebGL，已自动切换到 2D 同源视图。'
    console.warn('Three.js WebGL fallback:', error)
    destroyScene()
    mode3d.value = false
  }
}

function onPointer(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const hit = raycaster.intersectObjects(meshes)[0]
  if (hit) selectHost(hit.object.userData.host)
}

function selectHost(host) {
  selected.value = host
  meshes.forEach(mesh => { mesh.material.emissiveIntensity = mesh.userData.host.id === host.id ? .1 : 0 })
}
function resetCamera() { camera?.position.set(12, 10, 17); controls?.target.set(0, 2.7, 0); controls?.update() }
function resize() { if (!renderer || !canvasHost.value) return; const w=canvasHost.value.clientWidth,h=canvasHost.value.clientHeight; camera.aspect=w/h;camera.updateProjectionMatrix();renderer.setSize(w,h) }
function animate(time = 0) {
  raf = requestAnimationFrame(animate); controls?.update()
  renderer?.render(scene,camera)
}
function destroyScene() {
  cancelAnimationFrame(raf); resizeObserver?.disconnect(); renderer?.domElement.removeEventListener('pointerdown', onPointer); controls?.dispose(); renderer?.dispose()
  if (renderer?.domElement.parentNode) renderer.domElement.parentNode.removeChild(renderer.domElement)
  meshes.splice(0); renderer = scene = camera = controls = null
}

watch(mode3d, async (value) => { destroyScene(); if (value) { await nextTick(); initScene() } })
onMounted(initScene); onBeforeUnmount(destroyScene)
</script>

<template>
  <div class="topology-view">
    <div class="hero-row">
      <div><p class="eyebrow">模拟逻辑拓扑</p><h2 class="section-heading">让“影响范围”变成可指认的空间</h2><p class="section-copy">三维视图把任务批次、逻辑故障域与主机状态放进同一个视角。当前资产模型没有真实机房字段，R1–R4 均为演示分组，接入 site / room / rack 后才称物理拓扑。</p></div>
      <div class="hero-actions">
        <div class="segmented"><button :class="{active:mode3d}" @click="mode3d=true">3D 逻辑视图</button><button :class="{active:!mode3d}" @click="mode3d=false">2D 应急视图</button></div>
        <button class="icon-button" aria-label="复位相机" @click="resetCamera"><Icon name="reset" :size="16" /></button>
      </div>
    </div>

    <div class="topology-shell panel">
      <div class="topology-toolbar">
        <div class="legend"><span><i class="healthy"></i>模拟健康 21</span><span><i class="warning"></i>模拟注意 2</span><span><i class="offline"></i>模拟离线 1</span><span><i class="task"></i>模拟任务中 4</span></div>
        <div class="filter-row"><button v-for="group in ['全部','核心业务组','数据服务组','安全基础设施组']" :key="group" :class="{active:filter===group}" @click="filter=group">{{ group }}</button></div>
      </div>

      <div class="topology-content">
        <div class="scene-area">
          <div v-show="mode3d" ref="canvasHost" class="canvas-host"><div class="canvas-hint"><Icon name="cube" :size="14" />拖拽旋转 · 滚轮缩放 · 点击主机</div></div>
          <div v-if="!mode3d" class="fallback-grid">
            <div v-if="fallbackReason" class="fallback-notice">{{ fallbackReason }}</div>
            <section v-for="rack in 4" :key="rack" class="rack-column"><header><span>故障域 R{{ rack }}</span><small>逻辑分组 {{ rack }}</small></header><button v-for="host in visibleHosts.filter(h=>h.rack===`R${rack}`)" :key="host.id" class="host-tile" :class="[host.status,{active:selected?.id===host.id}]" @click="selectHost(host)"><span class="host-led"></span><b>{{ host.id }}</b><small>槽位 {{ host.slot }} · CPU {{ host.cpu }}%</small><span v-if="host.task" class="task-wave"></span></button></section>
          </div>
          <div class="scene-stats"><div><span>可用率</span><b>95.8<small>%</small></b></div><div><span>Agent 在线</span><b>23<small>/24</small></b></div><div><span>任务批次</span><b>02<small>/06</small></b></div></div>
        </div>

        <aside class="host-inspector">
          <template v-if="selected">
            <div class="host-title"><span class="server-glyph"><Icon name="database" :size="19" /></span><div><p>已选实例</p><h3>{{ selected.id }}</h3></div><button class="icon-button" @click="selected=null"><Icon name="close" :size="14" /></button></div>
            <div class="host-health"><span class="status-orb" :class="selected.status"></span><div><small>模拟状态</small><b>{{ statusLabel[selected.status] }}</b></div><span class="tag" :class="selected.status==='healthy'?'good':selected.status==='warning'?'warn':'bad'">Agent {{ selected.agent }}</span></div>
            <dl class="host-facts"><div><dt>权限组</dt><dd>{{ selected.group }}</dd></div><div><dt>逻辑分组</dt><dd>{{ selected.rack }} · 槽位 {{ selected.slot }}</dd></div><div><dt>操作系统</dt><dd>Kylin V10（演示数据）</dd></div><div><dt>CPU / 内存</dt><dd>{{ selected.cpu }}% / 61%</dd></div><div><dt>Kysec 状态</dt><dd>演示数据 · 待真机采集</dd></div></dl>
            <div class="edge-provenance"><p class="eyebrow">关系来源</p><div v-for="edge in selectedEdges" :key="edge.type"><b>{{ edge.type }}</b><small>{{ edge.source }}</small><time>{{ edge.observedAt }} · 可信度 {{ edge.confidence }}</time></div></div>
            <div class="task-detail" :class="{idle:!selected.task}"><p class="eyebrow">当前任务</p><b>{{ selected.task ? 'SSH 安全基线强化' : '当前无执行任务' }}</b><div v-if="selected.task" class="progress-track"><div class="progress-fill" style="width:68%"></div></div><small>{{ selected.task ? '验证配置文件 · 68%' : '实例处于受控待命状态' }}</small></div>
            <button class="button inspector-action">打开实例证据链 <Icon name="chevron" :size="14" /></button>
          </template>
          <div v-else class="no-selection"><span class="selection-orbit"><Icon name="cube" :size="27" /></span><h3>选择一台主机</h3><p>点击 3D 机架中的服务器，或切换到 2D 视图快速定位。</p></div>
        </aside>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scan-line{display:none}
.legend i{width:8px!important;height:3px!important;border-radius:1px!important;box-shadow:none!important}
.status-orb{border-radius:2px!important;box-shadow:none!important}
.scene-area{background:#090a0b!important}
.canvas-hint,.scene-stats>div{background:#151719!important;backdrop-filter:none!important;border-radius:5px!important}
.host-inspector{background:var(--panel)!important}
.selection-orbit{border-radius:8px!important;background:var(--panel-strong)!important;box-shadow:none!important}
.task-wave{animation:none!important}
.topology-shell { overflow: hidden; }
.topology-toolbar { min-height: 55px; display:flex;align-items:center;justify-content:space-between;gap:16px;padding:10px 14px;border-bottom:1px solid var(--line); }
.legend,.filter-row{display:flex;align-items:center;flex-wrap:wrap;gap:12px}.legend span{color:var(--muted);font-size:9px}.legend i{display:inline-block;width:6px;height:6px;margin-right:5px;border-radius:1px}.healthy{background:var(--mint)}.warning{background:var(--amber)}.offline{background:var(--red)}.legend .task{width:8px;height:3px;border:0;background:var(--amber);box-shadow:none}
.filter-row{gap:3px}.filter-row button{padding:5px 8px;border:0;border-radius:7px;background:transparent;color:var(--faint);font-size:9px;cursor:pointer}.filter-row button.active{color:var(--text);background:var(--panel-soft)}
.topology-content{display:grid;grid-template-columns:minmax(0,1fr) 280px;min-height:610px}.scene-area{position:relative;min-width:0;background:#090a0b}.canvas-host{position:absolute;inset:0;overflow:hidden}.canvas-host :deep(canvas){display:block}.canvas-hint{position:absolute;z-index:3;top:13px;left:13px;display:flex;align-items:center;gap:6px;padding:6px 8px;border:1px solid var(--line);border-radius:5px;background:#151719;color:var(--faint);font-size:9px;pointer-events:none}.scene-stats{position:absolute;z-index:4;left:14px;right:14px;bottom:14px;display:flex;gap:7px;pointer-events:none}.scene-stats>div{min-width:105px;padding:9px 11px;border:1px solid var(--line);border-radius:5px;background:#151719}.scene-stats span{display:block;color:var(--faint);font-size:9px}.scene-stats b{font:500 19px 'DM Mono';color:var(--text)}.scene-stats small{margin-left:2px;color:var(--faint);font-size:9px}
.host-inspector{padding:16px;border-left:1px solid var(--line);background:var(--panel)}.host-title{display:grid;grid-template-columns:38px 1fr 34px;align-items:center;gap:9px;padding-bottom:15px;border-bottom:1px solid var(--line)}.server-glyph{width:36px;height:36px;display:grid;place-items:center;border:1px solid rgba(103,181,139,.28);border-radius:7px;color:var(--mint);background:var(--panel-strong)}.host-title p{margin:0 0 3px;color:var(--faint);font-size:8px}.host-title h3{margin:0;font:500 12px 'DM Mono'}.host-health{display:flex;align-items:center;gap:9px;padding:16px 0}.status-orb{width:10px;height:4px;border-radius:1px;box-shadow:none}.status-orb.healthy{color:var(--mint)}.status-orb.warning{color:var(--amber)}.status-orb.offline{color:var(--red)}.host-health div{display:flex;flex-direction:column}.host-health small{color:var(--faint);font-size:8px}.host-health b{font-size:11px}.host-health .tag{margin-left:auto}.host-facts{margin:0;border-top:1px solid var(--line)}.host-facts>div{display:flex;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid var(--line)}.host-facts dt{color:var(--faint);font-size:9px}.host-facts dd{margin:0;color:var(--muted);font:9px 'DM Mono';text-align:right}.task-detail{margin-top:16px;padding:13px;border:1px solid rgba(103,181,139,.24);border-radius:7px;background:var(--panel-strong)}.task-detail.idle{border-color:var(--line)}.task-detail b{display:block;margin-bottom:10px;font-size:11px}.task-detail small{display:block;margin-top:7px;color:var(--faint);font-size:8px}.inspector-action{width:100%;margin-top:12px;justify-content:space-between}.no-selection{height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center}.selection-orbit{width:68px;height:68px;display:grid;place-items:center;border:1px solid var(--line);border-radius:8px;color:var(--mint);background:var(--panel-strong);box-shadow:none}.no-selection h3{margin:18px 0 5px;font-size:13px}.no-selection p{max-width:190px;margin:0;color:var(--faint);font-size:10px;line-height:1.7}
.edge-provenance{margin-top:13px}.edge-provenance>div{display:grid;gap:3px;margin-top:6px;padding:8px;border:1px solid var(--line);border-radius:7px;background:rgba(119,242,182,.025)}.edge-provenance b{color:var(--mint);font:8px 'DM Mono'}.edge-provenance small,.edge-provenance time{color:var(--faint);font:7px 'DM Mono'}
.fallback-grid{position:absolute;inset:0;padding:18px 18px 84px;display:grid;grid-template-columns:repeat(4,1fr);gap:10px;overflow:auto}.rack-column{padding:10px;border:1px solid var(--line);border-radius:7px;background:var(--panel)}.rack-column header{margin-bottom:10px}.rack-column header span,.rack-column header small{display:block}.rack-column header span{font:500 11px 'DM Mono'}.rack-column header small{margin-top:2px;color:var(--faint);font-size:7px}.host-tile{position:relative;width:100%;display:grid;grid-template-columns:10px 1fr;gap:2px 5px;padding:8px;margin-bottom:6px;border:1px solid var(--line);border-radius:5px;background:var(--panel-strong);text-align:left;cursor:pointer;overflow:hidden}.host-tile.active{border-color:var(--mint);background:rgba(103,181,139,.08)}.host-tile b{overflow:hidden;font:8px 'DM Mono';text-overflow:ellipsis}.host-tile small{grid-column:2;color:var(--faint);font:7px 'DM Mono'}.host-led{width:5px;height:5px;margin-top:2px;border-radius:1px;background:var(--mint)}.host-tile.warning .host-led{background:var(--amber)}.host-tile.offline .host-led{background:var(--red)}.task-wave{position:absolute;inset:0;border-left:2px solid var(--amber)}
@media(max-width:1050px){.topology-content{grid-template-columns:1fr;grid-template-rows:540px auto}.host-inspector{min-height:300px;border-left:0;border-top:1px solid var(--line)}.topology-toolbar{align-items:flex-start;flex-direction:column}.fallback-grid{grid-template-columns:repeat(2,1fr)}}
.fallback-notice{grid-column:1/-1;padding:9px 11px;border:1px solid rgba(241,192,106,.28);border-radius:8px;color:var(--amber);background:rgba(241,192,106,.06);font-size:10px}
</style>
