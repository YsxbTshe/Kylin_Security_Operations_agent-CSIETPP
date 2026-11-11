<script setup lang="ts">
/**
 * 图谱节点选择器：从已有实体里搜索选取，也允许输入新名称。
 *
 * 让人凭记忆手打实体名是不可用的——图里有近千个节点，名称大小写、空格、
 * 中英文写法稍有出入就会新建出一个重复实体，把图污染掉。这里边输入边过滤，
 * 命中已有实体时明确标出类型；确实要新建时也给出提示，让人知道自己在造新节点。
 */
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { Input } from '../../ui'

interface PickerNode {
  id: string
  name: string
  nodeType: string
}

const props = defineProps<{
  modelValue?: string
  nodes: PickerNode[]
  typeLabels: Record<string, string>
  typeColors: Record<string, string>
  disabled?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const open = ref(false)
const rootEl = ref<HTMLElement | null>(null)

/**
 * 建议列表 Teleport 到 body 并用 fixed 定位。
 *
 * 原因有二：对话框的 .dlg-body 是 overflow:auto，绝对定位的浮层会被裁掉；
 * 且对话框自身建立了层叠上下文，浮层再高的 z-index 也翻不出去。
 */
const pos = ref({ top: 0, left: 0, width: 0 })

function measure() {
  const el = rootEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  pos.value = { top: r.bottom + 4, left: r.left, width: r.width }
}

function onFocus() {
  open.value = true
  void nextTick(measure)
  window.addEventListener('scroll', measure, true)
  window.addEventListener('resize', measure)
}

function onBlur() {
  open.value = false
  window.removeEventListener('scroll', measure, true)
  window.removeEventListener('resize', measure)
}

onBeforeUnmount(onBlur)

const text = computed({
  get: () => props.modelValue ?? '',
  set: (value: string) => emit('update:modelValue', value),
})

const matches = computed(() => {
  const keyword = text.value.trim().toLowerCase()
  if (!keyword) return props.nodes.slice(0, 8)
  return props.nodes.filter((node) => node.name.toLowerCase().includes(keyword)).slice(0, 8)
})

/** 输入的名称在图里不存在时，提交会新建一个 CONCEPT 节点，这里提前告知 */
const willCreate = computed(() => {
  const value = text.value.trim().toLowerCase()
  return value.length > 0 && !props.nodes.some((node) => node.name.toLowerCase() === value)
})

function pick(node: PickerNode) {
  text.value = node.name
  onBlur()
}
</script>

<template>
  <div ref="rootEl" class="np">
    <Input
      v-model="text"
      :disabled="disabled"
      :placeholder="placeholder ?? '搜索已有实体，或输入新名称'"
      @focus="onFocus"
      @blur="onBlur"
    />
    <p v-if="willCreate && !disabled" class="np__new">图中无此实体，提交后将新建（类型 CONCEPT）</p>

    <Teleport to="body">
      <ul
        v-if="open && matches.length && !disabled"
        class="np__list"
        :style="{ top: `${pos.top}px`, left: `${pos.left}px`, width: `${pos.width}px` }"
      >
        <!-- mousedown 而非 click：blur 会先触发并关闭列表，click 就选不中了 -->
        <li v-for="node in matches" :key="node.id" @mousedown.prevent="pick(node)">
          <span class="np__dot" :style="{ background: typeColors[node.nodeType] || '#888' }" />
          <span class="np__name">{{ node.name }}</span>
          <span class="np__type">{{ typeLabels[node.nodeType] || node.nodeType }}</span>
        </li>
      </ul>
    </Teleport>
  </div>
</template>

<style scoped>
.np {
  position: relative;
}
.np__new {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--warn);
}
</style>

<!-- 列表 Teleport 到 body，scoped 选择器命中不到，这段必须是全局的 -->
<style>
.np__list {
  position: fixed;
  z-index: var(--z-dropdown);
  margin: 0;
  padding: 4px;
  list-style: none;
  max-height: 220px;
  overflow-y: auto;
  background: var(--surface);
  border: 1px solid var(--border-2);
  border-radius: var(--radius);
  box-shadow: var(--shadow-pop);
}
.np__list li {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  font-size: 12.5px;
  cursor: pointer;
}
.np__list li:hover {
  background: var(--surface-2);
}
.np__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.np__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}
.np__type {
  flex: none;
  font-size: 11px;
  color: var(--text-3);
}
</style>
