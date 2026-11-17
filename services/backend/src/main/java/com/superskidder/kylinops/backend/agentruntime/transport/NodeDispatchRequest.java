package com.superskidder.kylinops.backend.agentruntime.transport;

import java.time.Duration;
import java.util.List;

public record NodeDispatchRequest(
        String tenantId,
        String runId,
        String agentInstanceId,
        String agentInvocationId,
        String toolInvocationId,
        String executionAttemptId,
        String nodeId,
        String toolId,
        String toolVersion,
        String canonicalArgumentsJson,
        String argumentsDigest,
        String inputSchemaDigest,
        String outputSchemaDigest,
        String grantDigest,
        String catalogDigest,
        String riskCode,
        String executionMode,
        boolean readOnly,
        long lockVersion,
        List<LockEntry> resourceLocks,
        String approvalToken,
        Duration timeout
) {
    public record LockEntry(String resourceKey, String lockMode) {}
}
