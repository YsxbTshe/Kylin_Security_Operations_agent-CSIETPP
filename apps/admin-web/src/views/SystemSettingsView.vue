<script setup lang="ts">
/**
 * 系统设置：把原本散在侧边栏的「模型配置」和「RAG 设置」收进同一个入口。
 *
 * 这两页都是"平台级、低频、需要 platform:configure"的配置，分成两个导航项会让
 * 侧边栏显得零碎，也让人以为它们是两类不同的东西。合并后侧边栏只留一个「系统设置」。
 * 原路由保留，老书签和深链不会失效。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PageHeader, Tabs } from '../ui'
import PlatformLlmConfigView from './PlatformLlmConfigView.vue'
import RagSettingsView from './RagSettingsView.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  { value: 'llm', label: '模型配置' },
  { value: 'rag', label: 'RAG 与检索' },
]

/** Tab 反映到 query 上，便于分享和刷新后停在原处 */
const active = ref(typeof route.query.tab === 'string' && tabs.some((t) => t.value === route.query.tab)
  ? (route.query.tab as string)
  : 'llm')

watch(active, (value) => {
  void router.replace({ query: { ...route.query, tab: value } })
})

const description = computed(() =>
  active.value === 'llm'
    ? '管理平台使用的模型；未启用模型时，知识图谱抽取与助手推理都不可用。'
    : '配置向量生成、混合检索通道、交叉重排与外部索引服务。',
)
</script>

<template>
  <div class="page">
    <PageHeader title="系统设置" :description="description" />
    <Tabs v-model="active" :tabs="tabs">
      <template #llm>
        <PlatformLlmConfigView embedded />
      </template>
      <template #rag>
        <RagSettingsView embedded />
      </template>
    </Tabs>
  </div>
</template>

<style scoped>
/* 子页各自带 .page 的纵向间距，这里去掉重复的外层留白 */
.page :deep(.page) {
  gap: 18px;
  padding: 0;
}
</style>
