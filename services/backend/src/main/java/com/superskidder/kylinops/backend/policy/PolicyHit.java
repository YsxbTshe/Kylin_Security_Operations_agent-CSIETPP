package com.superskidder.kylinops.backend.policy;

public record PolicyHit(
        String policyId,
        String message,
        RiskCode riskCode
) {
}
