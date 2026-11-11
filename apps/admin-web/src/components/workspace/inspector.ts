/**
 * 详情侧栏的选中目标。
 *
 * 单独放一个 .ts 文件而不是从 SFC 里 export：`<script setup>` 不允许 ES 模块导出，
 * 而这个类型同时被 InspectorPanel 与 AgentWorkspaceView 引用。
 */
import type { RuntimeToolInvocationItem } from '../../api/agent-runtime'
import type { WorkspaceEvent } from '../../utils/run-events'
import type { AgentBadgeModel } from './agent-badge'

export type InspectorTarget =
  | { kind: 'event'; event: WorkspaceEvent }
  | { kind: 'tool'; tool: RuntimeToolInvocationItem }
  | { kind: 'agent'; badge: AgentBadgeModel }
  | null
