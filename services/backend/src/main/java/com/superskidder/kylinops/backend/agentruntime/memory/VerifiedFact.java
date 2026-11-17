package com.superskidder.kylinops.backend.agentruntime.memory;

import java.time.Instant;

public record VerifiedFact(
        String id,
        String tenantId,
        String statement,
        String verifiedBy,
        String verificationDigest,
        Instant createdAt
) {
}
