package com.superskidder.kylinops.backend.agentruntime.memory;

import java.time.Instant;

public record MemoryCandidate(
        String id,
        String tenantId,
        String sourceRecordId,
        String statement,
        String evidenceJson,
        String status,
        Instant createdAt
) {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROMOTED = "PROMOTED";
    public static final String STATUS_REJECTED = "REJECTED";
}
