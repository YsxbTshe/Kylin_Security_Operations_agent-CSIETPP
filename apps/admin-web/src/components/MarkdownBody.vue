<script setup lang="ts">
/**
 * Markdown 正文渲染。
 *
 * 安全前提：`html: false`（markdown-it 的默认值，这里显式写出以免被误改）。
 * 知识库内容来自上传的文件与人工录入，属于不可信输入；一旦放开原始 HTML，
 * 一份夹带 `<script>` 或 `onerror=` 的文档就会在任何打开它的人浏览器里执行。
 * 关掉之后原始 HTML 会被转义成可见文本，既安全又不隐藏内容。
 */
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = withDefaults(defineProps<{
  source: string
  /**
   * 单个换行是否渲染成 <br>。
   * 文档正文默认关闭：PDF/手册的换行是版面折行，不是语义换行，开着会让整段碎成一行一句。
   * 聊天类场景用户手敲的换行有语义，那里应显式开启。
   */
  breaks?: boolean
}>(), { breaks: false })

const markdown = new MarkdownIt({ linkify: true, breaks: props.breaks })

const html = computed(() => markdown.render(props.source ?? ''))
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- 输入已由 markdown-it 在 html:false 下转义 -->
  <div class="md" v-html="html" />
</template>

<style scoped>
.md {
  font-size: 13px;
  line-height: 1.85;
  color: var(--text-2);
  word-break: break-word;
}
.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4),
.md :deep(h5) {
  margin: 18px 0 8px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--text);
}
.md :deep(h1) { font-size: 17px; }
.md :deep(h2) { font-size: 15.5px; }
.md :deep(h3) { font-size: 14px; }
.md :deep(h4),
.md :deep(h5) { font-size: 13px; }
.md :deep(> :first-child) {
  margin-top: 0;
}
.md :deep(p) {
  margin: 0 0 10px;
}
.md :deep(ul),
.md :deep(ol) {
  margin: 0 0 10px;
  padding-left: 22px;
}
.md :deep(li) {
  margin: 3px 0;
}
.md :deep(code) {
  padding: 1.5px 5px;
  border-radius: var(--radius-sm);
  background: var(--surface-3);
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text);
}
.md :deep(pre) {
  margin: 0 0 12px;
  padding: 11px 13px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
  overflow-x: auto;
}
.md :deep(pre code) {
  padding: 0;
  background: none;
  font-size: 11.5px;
  line-height: 1.7;
}
.md :deep(blockquote) {
  margin: 0 0 12px;
  padding: 2px 0 2px 12px;
  border-left: 3px solid var(--border-2);
  color: var(--text-3);
}
.md :deep(a) {
  color: var(--accent);
  text-decoration: none;
}
.md :deep(a:hover) {
  text-decoration: underline;
}
/* 手册类文档表格很多，必须能横向滚动而不撑破抽屉 */
.md :deep(table) {
  display: block;
  width: max-content;
  max-width: 100%;
  overflow-x: auto;
  border-collapse: collapse;
  margin: 0 0 12px;
  font-size: 12px;
}
.md :deep(th),
.md :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
}
.md :deep(th) {
  background: var(--surface-2);
  font-weight: 600;
  color: var(--text);
}
.md :deep(hr) {
  margin: 16px 0;
  border: none;
  border-top: 1px solid var(--border);
}
/* 文档插图按原始像素可能上千宽，必须同时限宽和限高，否则一张架构图能顶掉整屏 */
.md :deep(img) {
  display: block;
  max-width: 100%;
  max-height: 420px;
  width: auto;
  height: auto;
  object-fit: contain;
  margin: 12px auto;
  padding: 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
</style>
