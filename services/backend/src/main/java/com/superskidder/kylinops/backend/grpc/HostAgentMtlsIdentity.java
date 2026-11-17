package com.superskidder.kylinops.backend.grpc;

import java.time.Instant;

public record HostAgentMtlsIdentity(
        String organizationId,
        String nodeId,
        String serialNumber,
        String subject,
        Instant expiresAt
) {
}
