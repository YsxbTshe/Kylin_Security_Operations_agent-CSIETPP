package com.superskidder.kylinops.backend.abac;

/** ABAC 判定结果。allowed=false 即拒绝，reason/matchedPolicyId 供审计与排障。 */
public record AbacDecision(boolean allowed, String reason, String matchedPolicyId) {

    public static AbacDecision allow(String policyId) {
        return new AbacDecision(true, "matched allow policy " + policyId, policyId);
    }

    public static AbacDecision deny(String reason, String policyId) {
        return new AbacDecision(false, reason, policyId);
    }
}
