<script setup lang="ts">
/**
 * ⏳ 预留组件位：Context injection 面板（本次不实现，等后端 API）。
 *
 * 目标：把「本轮进入模型上下文的一切」按来源分类展示，点条目看原文——
 *   系统提示词 / Agent 定义 / 工具目录 / 运行策略 / 目标 / RAG 命中 / 记忆召回。
 *
 * ── 为什么现在是空的 ─────────────────────────────────────────────────
 * 提示词正文根本不在事件链上。后端把编译好的上下文写进 `context_frame` 表、
 * 把消息写进 `agent_run_message` 表，事件里只留一个指针：
 *   CONTEXT_COMPILED.payload = { contextFrameId, contextDigest,
 *                                stablePrefixDigest, prefixCacheHit, tokenEstimate }
 * 这两张表目前都没有只读 API，工具结果也还没有 provenance 字段。
 * 与其拿假数据糊一个面板，不如留空位——本组件刻意不渲染任何东西。
 *
 * ── 等待的后端 API（请按此对齐）─────────────────────────────────────
 *   GET /api/v2/agent-runtime/context-frames/{contextFrameId}
 *   权限：agent:read；租户取会话组织（不接受 query 传 tenantId）
 *   ApiResponse<ContextFrameView>：
 *     {
 *       id: string
 *       runId: string
 *       sequenceNo: number
 *       contextDigest: string
 *       stablePrefixDigest: string
 *       tokenEstimate: number
 *       createdAt: string                 // ISO-8601
 *       sections: ContextSection[]        // 已按注入顺序排好
 *     }
 *   ContextSection：
 *     {
 *       source: 'SYSTEM_PROMPT' | 'AGENT_DEFINITION' | 'TOOL_CATALOG'
 *             | 'RUNTIME_POLICY' | 'OBJECTIVE' | 'RAG_HIT' | 'MEMORY_RECALL'
 *             | 'TOOL_RESULT' | 'CONVERSATION'
 *       title: string                     // 面板上的行标题
 *       content: string                   // 原文，点开即看
 *       tokenEstimate: number | null
 *       stable: boolean                   // 是否属于可缓存的稳定前缀
 *       provenance: {                     // 工具结果与 RAG 命中的溯源，其余可为 null
 *         toolInvocationId?: string
 *         nodeId?: string
 *         documentId?: string
 *         chunkId?: string
 *         score?: number
 *         memoryId?: string
 *       } | null
 *     }
 *
 * 前端就绪后：本组件按 `source` 分组渲染，行内显示 tokenEstimate 占比条，
 * 点击展开 `content` 原文，`provenance` 渲染成可跳转的溯源链接。
 * 挂载点已经就位——TrajectoryPanel 的「输入」栏和 InspectorPanel 各一处，
 * contextFrameId 已经从 CONTEXT_COMPILED 事件里解析好并传进来。
 */

defineProps<{
  /** 来自 CONTEXT_COMPILED.contextFrameId，已是真实值；API 就绪后直接拿它去查。 */
  contextFrameId: string
}>()
</script>

<template>
  <!-- 预留：Context injection 面板。等 GET /agent-runtime/context-frames/{id} 上线后在此渲染。 -->
</template>
