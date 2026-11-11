package com.superskidder.kylinops.backend.agentruntime.persistence;

public record LeasedAgentRun(
        String id,
        String tenantId,
        String humanPrincipalId,
        String rootAgentInstanceId,
        String objective,
        String executionMode,
        String targetScopeJson,
        String grantSnapshotJson,
        String budgetJson,
        long inputRevision,
        String modelProfileId,
        String reasoningLevel
) {
}
