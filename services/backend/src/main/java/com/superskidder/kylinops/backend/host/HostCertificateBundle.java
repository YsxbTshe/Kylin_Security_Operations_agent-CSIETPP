package com.superskidder.kylinops.backend.host;

public record HostCertificateBundle(
        String hostname,
        String nodeId,
        String certSubject,
        String certExpiresAt,
        String certStatus,
        String caCertPem,
        String crlPem
) {
}
