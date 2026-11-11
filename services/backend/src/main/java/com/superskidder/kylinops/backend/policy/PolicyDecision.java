package com.superskidder.kylinops.backend.policy;

import java.util.List;

public record PolicyDecision(
        RiskCode riskCode,
        boolean requiresApproval,
        boolean denied,
        List<PolicyHit> policyHits,
        String policyVersion
) {
    public PolicyDecision(RiskCode riskCode, boolean requiresApproval, boolean denied, List<PolicyHit> policyHits) {
        this(riskCode, requiresApproval, denied, policyHits, "builtin-v1");
    }

    public PolicyDecision {
        policyVersion = policyVersion == null || policyVersion.isBlank() ? "builtin-v1" : policyVersion.trim();
        policyHits = policyHits == null ? List.of() : List.copyOf(policyHits);
    }
}
