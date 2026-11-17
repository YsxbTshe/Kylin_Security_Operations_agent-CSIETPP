package com.superskidder.kylinops.backend.host;

public record HostCertificateReissueRequest(
        String requestId,
        String hostname,
        String nodeId,
        String status,
        String requestedBy,
        String requestedAt,
        String reason,
        String decidedBy,
        String decidedAt,
        String decisionNote,
        String tokenExpiresAt,
        String tokenConsumedAt
) {
}
