package com.superskidder.kylinops.backend.host;

public record HostCertificateReissueApproval(
        HostCertificateReissueRequest request,
        String bootstrapToken
) {
}
