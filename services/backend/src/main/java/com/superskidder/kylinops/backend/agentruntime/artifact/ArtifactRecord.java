package com.superskidder.kylinops.backend.agentruntime.artifact;

import java.time.Instant;

public record ArtifactRecord(
        String id,
        String tenantId,
        String agentInstanceId,
        String name,
        String contentType,
        long sizeBytes,
        String sm3Hex,
        String status,
        String storagePath,
        Instant createdAt,
        Instant publishedAt
) {
}
