/**
 * 枚举的中文呈现。
 *
 * 界面只显示中文，英文枚举仅作为存储值与提交值——把 `WAITING_APPROVAL`、`UPSTREAM_MANPAGE`
 * 这类内部标识直接摆给使用者看，既不易读也不专业。
 *
 * 统一规则：查不到的键**原样返回**，不返回空串也不硬编造译名。后端新增取值时界面会退化成
 * 显示英文枚举，是一个明显可见的信号，提示这里需要补词条；静默吞掉才是真正的坑。
 */

function labeler(dict: Record<string, string>) {
  return (value: string | null | undefined) => {
    if (!value) return ''
    return dict[value] ?? value
  }
}

/** 知识来源类型（与导入脚本 SOURCE_TYPES 及 PDF/手工录入保持一致） */
export const SOURCE_TYPE_LABELS: Record<string, string> = {
  KYLIN_OFFICIAL: '麒麟官方',
  KYLIN_SECURITY: '麒麟安全',
  OPENKYLIN_OFFICIAL: 'openKylin 官方',
  UPSTREAM_MANPAGE: '上游手册页',
  CORPUS: '语料',
  RUNBOOK: '运维手册',
  PDF: 'PDF 文档',
  SYSTEM: '系统内置',
}
export const sourceTypeLabel = labeler(SOURCE_TYPE_LABELS)

/** 记忆类型 */
export const MEMORY_TYPE_LABELS: Record<string, string> = {
  OPERATION: '操作经验',
  OP_LOG: '操作记录',
  DOCUMENT_SUMMARY: '文档摘要',
  INCIDENT: '事件复盘',
}
export const memoryTypeLabel = labeler(MEMORY_TYPE_LABELS)

/** 图谱关系。键必须与后端 KnowledgeGraphService.RELATIONS 完全一致 */
export const RELATION_LABELS: Record<string, string> = {
  RELATED_TO: '相关',
  PART_OF: '属于',
  DEPENDS_ON: '依赖',
  CONFIGURED_BY: '由…配置',
  MANAGED_BY: '由…管理',
  REQUIRES_APPROVAL: '需审批',
  MITIGATES: '缓解',
  DETECTS: '检测',
  AFFECTS: '影响',
  PROTECTS: '保护',
}
export const relationLabel = labeler(RELATION_LABELS)

/** 图谱节点类型。键必须与后端 KnowledgeGraphService.NODE_TYPES 完全一致 */
export const NODE_TYPE_LABELS: Record<string, string> = {
  CONCEPT: '概念',
  COMPONENT: '组件',
  SERVICE: '服务',
  COMMAND: '命令',
  TOOL: '工具',
  FILE_PATH: '路径',
  CONFIG: '配置',
  RISK_LEVEL: '风险等级',
  POLICY: '策略',
  PROCEDURE: '流程',
  THREAT: '威胁',
  ACCOUNT: '账号权限',
}
export const nodeTypeLabel = labeler(NODE_TYPE_LABELS)

/**
 * 任务与步骤状态。
 * CANCELLED / CANCELED 两种拼写都收录：后端历史数据两种都存在，
 * 统一词表，避免同一状态在各界面显示为不同名称。
 */
export const TASK_STATUS_LABELS: Record<string, string> = {
  PENDING: '待处理',
  READY: '就绪',
  RUNNING: '执行中',
  PAUSED: '已暂停',
  WAITING_APPROVAL: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  CANCELED: '已取消',
  ROLLED_BACK: '已回滚',
  SKIPPED: '已跳过',
  TIMEOUT: '超时',
}
export const taskStatusLabel = labeler(TASK_STATUS_LABELS)

/** 主机在线状态 */
export const HOST_STATUS_LABELS: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
  ALERTING: '告警',
}
export const hostStatusLabel = labeler(HOST_STATUS_LABELS)

/** 设备证书状态 */
export const CERT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '有效',
  REVOKED: '已吊销',
  EXPIRED: '已过期',
  PENDING: '待签发',
}
export const certStatusLabel = labeler(CERT_STATUS_LABELS)

/** 告警状态与级别 */
export const ALERT_STATUS_LABELS: Record<string, string> = {
  OPEN: '未处理',
  CLOSED: '已关闭',
  ACK: '已确认',
  RESOLVED: '已解决',
}
export const alertStatusLabel = labeler(ALERT_STATUS_LABELS)

export const ALERT_LEVEL_LABELS: Record<string, string> = {
  HIGH: '高危',
  MEDIUM: '中危',
  LOW: '低危',
}
export const alertLevelLabel = labeler(ALERT_LEVEL_LABELS)

/* ---------------- Agent Runtime（Phase 6.5 时间线 UI） ---------------- */

/** 与 Tag 组件 tone 对齐的色调类型 */
export type TagTone = 'neutral' | 'accent' | 'ok' | 'warn' | 'danger'

/** AgentRun 状态（agent_run_status_ck） */
export const RUN_STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  WAITING: '等待中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}
export const runStatusLabel = labeler(RUN_STATUS_LABELS)

const RUN_STATUS_TONES: Record<string, TagTone> = {
  QUEUED: 'neutral',
  RUNNING: 'accent',
  WAITING: 'warn',
  COMPLETED: 'ok',
  FAILED: 'danger',
  CANCELLED: 'neutral',
}
export function runStatusTone(status: string | null | undefined): TagTone {
  return (status && RUN_STATUS_TONES[status]) || 'neutral'
}

/** run 等待原因（waiting_reason 约束取值） */
export const WAITING_REASON_LABELS: Record<string, string> = {
  APPROVAL: '等待审批',
  TOOL: '等待工具',
  SUB_AGENT: '等待子 Agent',
  RETRY_TIME: '等待重试',
}
export const waitingReasonLabel = labeler(WAITING_REASON_LABELS)

/** run 结果（outcome 约束取值） */
export const RUN_OUTCOME_LABELS: Record<string, string> = {
  SUCCEEDED: '达成',
  PARTIAL: '部分达成',
  NOT_ACHIEVED: '未达成',
}
export const runOutcomeLabel = labeler(RUN_OUTCOME_LABELS)

/** 执行模式 */
export const EXECUTION_MODE_LABELS: Record<string, string> = {
  PLAN: '只读规划',
  CAUTIOUS: '谨慎执行',
  AUTO: '自动执行',
}
export const executionModeLabel = labeler(EXECUTION_MODE_LABELS)

/** 工具调用状态（tool_invocation_status_ck） */
export const TOOL_INVOCATION_STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
  UNKNOWN: '未知',
}
export const toolInvocationStatusLabel = labeler(TOOL_INVOCATION_STATUS_LABELS)

const TOOL_INVOCATION_STATUS_TONES: Record<string, TagTone> = {
  QUEUED: 'neutral',
  RUNNING: 'accent',
  SUCCEEDED: 'ok',
  FAILED: 'danger',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
  UNKNOWN: 'warn',
}
export function toolInvocationStatusTone(status: string | null | undefined): TagTone {
  return (status && TOOL_INVOCATION_STATUS_TONES[status]) || 'neutral'
}

/** 工具风险等级 R0–R5：越高越醒目 */
const RISK_CODE_TONES: Record<string, TagTone> = {
  R0: 'neutral',
  R1: 'ok',
  R2: 'accent',
  R3: 'warn',
  R4: 'danger',
  R5: 'danger',
}
export function riskCodeTone(riskCode: string | null | undefined): TagTone {
  return (riskCode && RISK_CODE_TONES[riskCode]) || 'neutral'
}

/** 审批决策（approval_decision_ck） */
export const APPROVAL_DECISION_LABELS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  REVOKED: '已撤销',
  EXPIRED: '已过期',
}
export const approvalDecisionLabel = labeler(APPROVAL_DECISION_LABELS)

const APPROVAL_DECISION_TONES: Record<string, TagTone> = {
  PENDING: 'warn',
  APPROVED: 'ok',
  REJECTED: 'danger',
  REVOKED: 'neutral',
  EXPIRED: 'neutral',
}
export function approvalDecisionTone(decision: string | null | undefined): TagTone {
  return (decision && APPROVAL_DECISION_TONES[decision]) || 'neutral'
}

/** Artifact 状态（ArtifactService 收敛的取值） */
export const ARTIFACT_STATUS_LABELS: Record<string, string> = {
  WRITING: '写入中',
  PUBLISHED: '已发布',
  FAILED: '失败',
}
export const artifactStatusLabel = labeler(ARTIFACT_STATUS_LABELS)

const ARTIFACT_STATUS_TONES: Record<string, TagTone> = {
  WRITING: 'accent',
  PUBLISHED: 'ok',
  FAILED: 'danger',
}
export function artifactStatusTone(status: string | null | undefined): TagTone {
  return (status && ARTIFACT_STATUS_TONES[status]) || 'neutral'
}

/** SubAgent handoff 状态 */
export const HANDOFF_STATUS_LABELS: Record<string, string> = {
  OPEN: '进行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}
export const handoffStatusLabel = labeler(HANDOFF_STATUS_LABELS)

const HANDOFF_STATUS_TONES: Record<string, TagTone> = {
  OPEN: 'accent',
  COMPLETED: 'ok',
  FAILED: 'danger',
  CANCELLED: 'neutral',
}
export function handoffStatusTone(status: string | null | undefined): TagTone {
  return (status && HANDOFF_STATUS_TONES[status]) || 'neutral'
}

/** agent_run_event 事件类型的中文标题；未收录的类型原样显示（labels 统一约定） */
export const RUN_EVENT_TYPE_LABELS: Record<string, string> = {
  RUN_CREATED: '任务创建',
  RUN_STARTED: '任务开始执行',
  RUN_WAITING: '任务进入等待',
  RUN_WAKEUP: '任务被唤醒',
  RUN_COMPLETED: '任务完成',
  RUN_FAILED: '任务失败',
  RUN_CANCELLED: '任务取消',
  CONTEXT_COMPILED: '上下文编译完成',
  CONTEXT_SUMMARIZED: '上下文已压缩',
  MODEL_INVOCATION_STARTED: '模型调用开始',
  MODEL_INVOCATION_COMPLETED: '模型调用完成',
  MODEL_INVOCATION_FAILED: '模型调用失败',
  MODEL_UNAVAILABLE_FALLBACK: '模型不可用，已降级',
  TOOL_INVOCATION_QUEUED: '工具调用排队',
  TOOL_INVOCATION_SUCCEEDED: '工具调用成功',
  TOOL_INVOCATION_FAILED: '工具调用失败',
  NODE_INVOCATION_ACKNOWLEDGED: '节点已确认接收',
  APPROVAL_CREATED: '发起审批',
  APPROVAL_DECIDED: '审批已决策',
  APPROVAL_AUTO_GRANTED: '审批自动放行',
  APPROVAL_EXPIRED: '审批已过期',
  SUB_AGENT: '分派子 Agent',
  SUB_AGENT_RESULT: '子 Agent 结果回传',
  PLAN_VERSION_CREATED: '计划版本生成',
  PLAN_VERSION_AMENDED: '计划版本修订',
  /* 策略命中码：与后端 PolicyEngine / DispatchGate / IntentAlignmentGuard 的
   * POLICY_* 判定码一一对应。它们不是事件类型，出现在 errorCode 字段上，
   * 由 utils/run-events.ts#policyHitLabel 复用本词表翻译。 */
  POLICY_DENIED: '策略拒绝执行',
  POLICY_R5_FORBIDDEN_OR_INJECTION: 'R5 禁止操作或疑似注入',
  POLICY_R5_NATIVE_GUARD_DISABLE: 'R5 禁止关闭内核防护',
  POLICY_DSL_RULE: '命中 DSL 策略规则',
  POLICY_HIGH_RISK_APPROVAL: '高风险，转人工审批',
  POLICY_INTENT_MISMATCH: '操作与目标意图不符',
  POLICY_IRRECOVERABLE_ACTION: '不可恢复操作',
  POLICY_READ_ONLY_AUTO: '只读操作自动放行',
  POLICY_SENSITIVE_PATH: '命中敏感路径',
  POLICY_MANIFEST_ALLOWED_ROOTS: '受清单允许根目录约束',
  POLICY_MANIFEST_BLOCKED_PATH: '命中清单禁止路径',
  POLICY_AUDITD_DOWN_ESCALATION: 'auditd 未运行，风险升级',
  POLICY_KYSEC_DISABLED_ESCALATION: 'kysec 已关闭，风险升级',
  POLICY_SECURITY_SWITCH_OFF_DENY: '安全开关关闭，拒绝执行',
}
export const runEventTypeLabel = labeler(RUN_EVENT_TYPE_LABELS)
