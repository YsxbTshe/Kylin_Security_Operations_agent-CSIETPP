package com.superskidder.kylinops.backend.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 事件接入请求。相同 dedupe（类别|来源IP|节点）的非终态事件会被幂等合并而非重复创建。 */
public record IncidentIntakeRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 255, message = "长度不能超过255")
        String title,
        @Size(max = 16, message = "长度不能超过16")
        String severity,
        @NotBlank(message = "事件类别不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String category,
        @Size(max = 64, message = "长度不能超过64")
        String sourceIp,
        @Size(max = 128, message = "长度不能超过128")
        String targetNodeId,
        @Size(max = 16, message = "长度不能超过16")
        String dataMode,
        @Size(max = 1024, message = "长度不能超过1024")
        String summary,
        String evidenceJson,
        Long sourceAlertId,
        @Size(max = 64, message = "长度不能超过64")
        String runbookId
) {
}
