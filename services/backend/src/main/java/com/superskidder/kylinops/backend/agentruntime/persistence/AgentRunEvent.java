package com.superskidder.kylinops.backend.agentruntime.persistence;

import java.time.Instant;

public record AgentRunEvent(
        String eventId,
        String runId,
        long sequence,
        String eventType,
        String actorType,
        String actorId,
        String payloadJson,
        String previousHash,
        String eventHash,
        Instant createdAt
) {
}
