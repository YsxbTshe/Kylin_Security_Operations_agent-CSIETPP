package com.superskidder.kylinops.backend.agentruntime.memory;

import java.time.Instant;

public record ExecutionRecord(
        String id,
        String tenantId,
        String runId,
        String agentInstanceId,
        String toolId,
        String outcomeSummary,
        boolean success,
        Instant createdAt
) {
}
