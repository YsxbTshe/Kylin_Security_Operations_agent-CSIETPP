<script setup lang="ts">
/**
 * PDF 解析可视化：把"抽取 → 归纳"这两步真实处理过程显性化。
 *
 * 三幕：
 *   1. 解析中     —— 后端 PDFBox 抽取正文 + LLM 归纳（一次请求，不落库）
 *   2. 显影       —— 抽取到的原文逐行从模糊到清晰，像照片显影
 *   3. 凝缩       —— 原文向中线收拢并淡出，摘要从那条线里升起
 *
 * 动画只改变"呈现方式"，不改变内容：显影出来的就是后端真实返回的 extractedText，
 * 摘要就是 LLM 的真实输出。摘要不可用时（未配置模型/调用失败）如实提示并停留在原文，
 * 不会播放凝缩动画去暗示有摘要。
 */
import { computed, ref, watch } from 'vue'
import { FileText, Sparkles, TriangleAlert } from 'lucide-vue-next'
import type { RagDocumentAnalysis } from '../../api/rag'
import { Button, Dialog } from '../../ui'

const props = defineProps<{
  open: boolean
  fileName: string
  loading: boolean
  analysis: RagDocumentAnalysis | null
  error: string
  /** 入库进行中 */
  saving: boolean
  /** import：上传后确认入库；summarize：对已入库文档做归纳，只看不写 */
  mode?: 'import' | 'summarize'
}>()

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'confirm'): void
}>()

type Phase = 'parsing' | 'revealing' | 'condensing' | 'summary'
const phase = ref<Phase>('parsing')
let timers: number[] = []

function clearTimers() {
  timers.forEach((id) => window.clearTimeout(id))
  timers = []
}

/** 原文按行切分，用于逐行显影；只取前若干行，其余以"还有 N 行"收口，避免弹窗被长文撑爆 */
const MAX_LINES = 14
const allLines = computed(() =>
  (props.analysis?.extractedText ?? '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean),
)
const lines = computed(() => allLines.value.slice(0, MAX_LINES))
const hiddenLineCount = computed(() => Math.max(0, allLines.value.length - MAX_LINES))

const hasSummary = computed(() => props.analysis?.summaryStatus === 'OK')

const summaryNotice = computed(() => {
  if (!props.analysis) return ''
  if (props.analysis.summaryStatus === 'LLM_DISABLED') return '未配置平台模型，本次只做文本抽取，未生成摘要。'
  if (props.analysis.summaryStatus === 'FAILED') return '摘要生成失败，已保留抽取到的原文。'
  return ''
})

/** 解析完成后按幕推进；没有摘要就停在显影这一幕 */
watch(
  () => props.analysis,
  (analysis) => {
    clearTimers()
    if (!analysis) {
      phase.value = 'parsing'
      return
    }
    phase.value = 'revealing'
    if (!hasSummary.value) return
    // 显影时长随行数增加，但设上限，避免长文档等太久
    const revealMs = Math.min(400 + lines.value.length * 70, 1500)
    timers.push(window.setTimeout(() => (phase.value = 'condensing'), revealMs))
    timers.push(window.setTimeout(() => (phase.value = 'summary'), revealMs + 620))
  },
  { immediate: true },
)

watch(
  () => props.open,
  (open) => {
    if (!open) {
      clearTimers()
      phase.value = 'parsing'
    }
  },
)

function replay() {
  if (!hasSummary.value) return
  clearTimers()
  phase.value = 'revealing'
  const revealMs = Math.min(400 + lines.value.length * 70, 1500)
  timers.push(window.setTimeout(() => (phase.value = 'condensing'), revealMs))
  timers.push(window.setTimeout(() => (phase.value = 'summary'), revealMs + 620))
}
</script>

<template>
  <Dialog
    :open="open"
    width="680px"
    :title="mode === 'summarize' ? '文档归纳' : '解析文档'"
    :description="fileName"
    @update:open="emit('update:open', $event)"
  >
    <div class="pa">
      <!-- 阶段指示 -->
      <div class="pa__steps">
        <span class="pa__step" :class="{ 'pa__step--done': phase !== 'parsing' }">
          <FileText /> 抽取正文
        </span>
        <i class="pa__arrow" />
        <span
          class="pa__step"
          :class="{ 'pa__step--done': phase === 'summary', 'pa__step--off': !hasSummary && !loading }"
        >
          <Sparkles /> AI 归纳
        </span>
      </div>

      <!-- 1. 解析中 -->
      <div v-if="loading" class="pa__parsing">
        <div class="pa__scan" />
        <p>{{ mode === 'summarize' ? '正在归纳文档内容…' : '正在抽取 PDF 正文并归纳…' }}</p>
      </div>

      <div v-else-if="error" class="pa__error">
        <TriangleAlert /> {{ error }}
      </div>

      <template v-else-if="analysis">
        <!-- 抽取事实：全部来自后端真实返回 -->
        <div class="pa__facts">
          <span v-if="analysis.pageCount > 0">{{ analysis.pageCount }} 页</span>
          <span>抽取 {{ analysis.extractedChars.toLocaleString() }} 字符</span>
          <template v-if="analysis.parseStats">
            <span v-if="analysis.parseStats.describedImages" class="pa__facts-ok">
              识别插图 {{ analysis.parseStats.describedImages }} 张
            </span>
            <span v-if="analysis.parseStats.visionPages" class="pa__facts-ok">
              视觉解析 {{ analysis.parseStats.visionPages }} 页
            </span>
            <span v-if="analysis.parseStats.emptyPages" class="pa__facts-warn">
              {{ analysis.parseStats.emptyPages }} 页无法解析
            </span>
            <span class="pa__facts-sep">将入库 {{ analysis.parseStats.documentCount }} 个分页文档</span>
          </template>
        </div>
        <!-- 未配置视觉模型时如实说明代价，而不是让人以为解析已经完整 -->
        <p v-if="analysis.parseStats && !analysis.parseStats.visionAvailable" class="pa__notice">
          未配置视觉模型：扫描页与插图内容不会被解析，仅抽取文字层。
        </p>

        <div class="pa__stage">
          <!-- 2. 原文显影 / 3. 凝缩淡出 -->
          <div
            v-if="phase !== 'summary'"
            class="pa__origin"
            :class="{ 'pa__origin--condense': phase === 'condensing' }"
          >
            <p
              v-for="(line, index) in lines"
              :key="index"
              class="pa__line"
              :style="{ '--i': index }"
            >
              {{ line }}
            </p>
            <p v-if="hiddenLineCount" class="pa__more">
              还有 {{ hiddenLineCount }} 行未展示
            </p>
          </div>

          <!-- 凝缩瞬间的那道线 -->
          <div v-if="phase === 'condensing'" class="pa__seam" />

          <!-- 4. 摘要升起 -->
          <div v-if="phase === 'summary'" class="pa__summary">
            <p class="pa__summary-text">{{ analysis.summary }}</p>
            <ul class="pa__points">
              <li
                v-for="(point, index) in analysis.keyPoints"
                :key="index"
                :style="{ '--i': index }"
              >
                {{ point }}
              </li>
            </ul>
          </div>
        </div>

        <p v-if="summaryNotice" class="pa__notice">{{ summaryNotice }}</p>
      </template>

      <div class="pa__actions">
        <Button v-if="hasSummary && phase === 'summary'" variant="subtle" @click="replay">重放</Button>
        <span class="pa__spacer" />
        <Button variant="subtle" @click="emit('update:open', false)">
          {{ mode === 'summarize' ? '关闭' : '取消' }}
        </Button>
        <Button
          v-if="mode !== 'summarize'"
          :disabled="!analysis || loading || saving"
          @click="emit('confirm')"
        >
          {{ saving ? '入库中…' : '确认入库' }}
        </Button>
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.pa {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* ── 阶段指示 ─────────────────────────────────────────── */
.pa__steps {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pa__step {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--text-3);
  transition: color .35s ease;
}
.pa__step :deep(svg) {
  width: 14px;
  height: 14px;
}
.pa__step--done {
  color: var(--accent);
}
.pa__step--off {
  opacity: 0.45;
}
.pa__arrow {
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, var(--border), var(--border-2), var(--border));
}

/* ── 解析中：一道来回扫描的光带 ───────────────────────── */
.pa__parsing {
  display: grid;
  place-items: center;
  gap: 14px;
  min-height: 190px;
  color: var(--text-3);
  font-size: 13px;
}
.pa__scan {
  position: relative;
  width: 190px;
  height: 3px;
  border-radius: 2px;
  background: var(--surface-2);
  overflow: hidden;
}
.pa__scan::after {
  content: '';
  position: absolute;
  inset: 0;
  width: 42%;
  border-radius: 2px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
  animation: pa-scan 1.15s ease-in-out infinite;
}
@keyframes pa-scan {
  0% { transform: translateX(-120%); }
  100% { transform: translateX(280%); }
}

.pa__error {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 120px;
  padding: 14px;
  border-radius: var(--radius);
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 13px;
}
.pa__error :deep(svg) {
  width: 16px;
  height: 16px;
}

.pa__facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 12px;
  color: var(--text-3);
}
.pa__facts-warn {
  color: var(--warn);
}
.pa__facts-ok {
  color: var(--ok);
}
.pa__facts-sep {
  color: var(--text-2);
}

/* ── 舞台 ─────────────────────────────────────────────── */
.pa__stage {
  position: relative;
  min-height: 210px;
  max-height: 310px;
  overflow: hidden;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
}

/* 原文逐行显影：模糊 → 清晰，自上而下错峰 */
.pa__line {
  margin: 0 0 5px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  animation: pa-develop .5s ease-out backwards;
  animation-delay: calc(var(--i) * 55ms);
}
@keyframes pa-develop {
  from { opacity: 0; filter: blur(7px); }
  to { opacity: 1; filter: blur(0); }
}
.pa__more {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--text-3);
}

/* 凝缩：整块向中线压扁并淡出，不是简单消失 */
.pa__origin--condense .pa__line,
.pa__origin--condense .pa__more {
  animation: pa-condense .58s cubic-bezier(.4, 0, .2, 1) forwards;
  animation-delay: calc(var(--i, 0) * 14ms);
}
@keyframes pa-condense {
  to {
    opacity: 0;
    filter: blur(3px);
    transform: translateY(26px) scaleY(.55);
  }
}

/* 凝缩瞬间横贯的一道细线 */
.pa__seam {
  position: absolute;
  left: 16px;
  right: 16px;
  top: 50%;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--accent), transparent);
  animation: pa-seam .58s ease-out forwards;
}
@keyframes pa-seam {
  0% { opacity: 0; transform: scaleX(.2); }
  55% { opacity: 1; transform: scaleX(1); }
  100% { opacity: 0; transform: scaleX(1); }
}

/* 摘要从那道线里升起 */
.pa__summary {
  animation: pa-rise .5s cubic-bezier(.2, .7, .3, 1) backwards;
}
@keyframes pa-rise {
  from { opacity: 0; transform: translateY(16px); filter: blur(5px); }
  to { opacity: 1; transform: translateY(0); filter: blur(0); }
}
.pa__summary-text {
  margin: 0 0 12px;
  font-size: 13.5px;
  line-height: 1.85;
  /* 摘要用与原文不同的颜色，一眼区分"这是模型产出，不是原文" */
  color: var(--chart-cat-1);
}
.pa__points {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.pa__points li {
  position: relative;
  padding-left: 16px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--text-2);
  animation: pa-rise .42s ease-out backwards;
  animation-delay: calc(180ms + var(--i) * 70ms);
}
.pa__points li::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 8px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--chart-cat-1);
}

.pa__notice {
  margin: 0;
  font-size: 12px;
  color: var(--warn);
}

.pa__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pa__spacer {
  flex: 1;
}

@media (prefers-reduced-motion: reduce) {
  .pa__line,
  .pa__origin--condense .pa__line,
  .pa__origin--condense .pa__more,
  .pa__seam,
  .pa__summary,
  .pa__points li {
    animation: none;
  }
  .pa__scan::after {
    animation: none;
  }
}
</style>
