package com.superskidder.kylinops.backend.incident;

/** 事件列表行。时间戳用 ISO-8601 字符串（可空）以便前端直接展示。 */
public record IncidentSummary(
        String id,
        String title,
        String severity,
        String category,
        String sourceIp,
        String targetNodeId,
        String dataMode,
        String status,
        int occurrenceCount,
        String taskId,
        String detectedAt,
        String updatedAt
) {
}
