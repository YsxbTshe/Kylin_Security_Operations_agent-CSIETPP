package com.superskidder.kylinops.backend.incident;

/**
 * 研判计划：把事件翻译成一条 SafeOps 处置任务的输入（目标主机 + 事件响应话术）。
 * 控制器据此调用 HostTaskService.createTask，再回填 task_id——事件编排与执行门禁解耦。
 */
public record IncidentTriagePlan(String incidentId, String targetNodeId, String prompt) {
}
