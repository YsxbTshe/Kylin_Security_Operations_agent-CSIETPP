package com.superskidder.kylinops.backend.agentruntime.security;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalClaim;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalToken;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ExecutionMode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.RiskCode;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class Sm2ApprovalTokenService {
    public static final String TOKEN_PREFIX = "kylin-approval-v1.";
    public static final int TOKEN_VERSION = 1;
    private static final Duration MAX_VALIDITY = Duration.ofMinutes(5);

    public String issue(ApprovalClaim claim, String keyId, PrivateKey privateKey) {
        validateClaim(claim);
        if (keyId == null || keyId.isBlank()) throw new IllegalArgumentException("keyId is required");
        byte[] signature = Sm2WithSm3.sign(
                privateKey,
                KeyPurpose.APPROVAL_SIGNING,
                DeterministicProtobuf.serialize(claim)
        );
        ApprovalToken token = ApprovalToken.newBuilder()
                .setVersion(TOKEN_VERSION)
                .setKeyId(keyId)
                .setClaim(claim)
                .setSm2DerSignature(com.google.protobuf.ByteString.copyFrom(signature))
                .build();
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(DeterministicProtobuf.serialize(token));
    }

    public ApprovalClaim verify(String encoded, Map<String, PublicKey> keys, Instant now) {
        if (encoded == null || !encoded.startsWith(TOKEN_PREFIX)) {
            throw new IllegalArgumentException("invalid approval token prefix");
        }
        ApprovalToken token;
        try {
            token = ApprovalToken.parseFrom(Base64.getUrlDecoder().decode(encoded.substring(TOKEN_PREFIX.length())));
        } catch (IllegalArgumentException | InvalidProtocolBufferException exception) {
            throw new IllegalArgumentException("invalid approval token encoding", exception);
        }
        if (token.getVersion() != TOKEN_VERSION) {
            throw new IllegalArgumentException("unsupported approval token version");
        }
        validateClaim(token.getClaim());
        PublicKey publicKey = keys.get(token.getKeyId());
        if (publicKey == null) throw new IllegalArgumentException("unknown approval signing key");
        if (!Sm2WithSm3.verify(
                publicKey,
                KeyPurpose.APPROVAL_SIGNING,
                DeterministicProtobuf.serialize(token.getClaim()),
                token.getSm2DerSignature().toByteArray()
        )) {
            throw new IllegalArgumentException("invalid approval token signature");
        }
        Instant issuedAt = instant(token.getClaim().getIssuedAt());
        Instant expiresAt = instant(token.getClaim().getExpiresAt());
        if (now.isBefore(issuedAt.minusSeconds(30))) {
            throw new IllegalArgumentException("approval token is not active yet");
        }
        if (!now.isBefore(expiresAt)) {
            throw new IllegalArgumentException("approval token has expired");
        }
        return token.getClaim();
    }

    public boolean covers(ApprovalClaim approved, ApprovalClaim actual) {
        if (approved == null || actual == null) return false;
        return approved.getTenantId().equals(actual.getTenantId())
                && approved.getRunId().equals(actual.getRunId())
                && approved.getAgentInstanceId().equals(actual.getAgentInstanceId())
                && approved.getAgentInvocationId().equals(actual.getAgentInvocationId())
                && approved.getToolInvocationId().equals(actual.getToolInvocationId())
                && approved.getExecutionAttemptId().equals(actual.getExecutionAttemptId())
                && approved.getNodeId().equals(actual.getNodeId())
                && approved.getToolId().equals(actual.getToolId())
                && approved.getToolVersion().equals(actual.getToolVersion())
                && approved.getArgumentsSm3().equals(actual.getArgumentsSm3())
                && approved.getRiskCeilingValue() >= actual.getRiskCeilingValue()
                && approved.getExecutionMode() == actual.getExecutionMode()
                && approved.getLockVersion() == actual.getLockVersion()
                && approved.getKeyPurpose().equals(KeyPurpose.APPROVAL_SIGNING.wireName());
    }

    private void validateClaim(ApprovalClaim claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.getVersion() != TOKEN_VERSION) throw new IllegalArgumentException("unsupported claim version");
        if (!claim.getKeyPurpose().equals(KeyPurpose.APPROVAL_SIGNING.wireName())) {
            throw new IllegalArgumentException("wrong approval key purpose");
        }
        Map<String, String> required = Map.ofEntries(
                Map.entry("tenantId", claim.getTenantId()), Map.entry("runId", claim.getRunId()),
                Map.entry("agentInstanceId", claim.getAgentInstanceId()), Map.entry("agentInvocationId", claim.getAgentInvocationId()),
                Map.entry("toolInvocationId", claim.getToolInvocationId()), Map.entry("executionAttemptId", claim.getExecutionAttemptId()),
                Map.entry("nodeId", claim.getNodeId()), Map.entry("toolId", claim.getToolId()),
                Map.entry("toolVersion", claim.getToolVersion())
        );
        required.forEach((name, value) -> {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        });
        if (claim.getArgumentsSm3().size() != 32) throw new IllegalArgumentException("argumentsSm3 must be 32 bytes");
        if (claim.getRiskCeilingValue() < RiskCode.RISK_CODE_R0_VALUE
                || claim.getRiskCeilingValue() > RiskCode.RISK_CODE_R4_VALUE) {
            throw new IllegalArgumentException("risk ceiling must be R0 through R4");
        }
        if (claim.getExecutionMode() != ExecutionMode.EXECUTION_MODE_CAUTIOUS
                && claim.getExecutionMode() != ExecutionMode.EXECUTION_MODE_AUTO) {
            throw new IllegalArgumentException("approval mode must be CAUTIOUS or AUTO");
        }
        if (claim.getLockVersion() == 0) throw new IllegalArgumentException("lockVersion is required");
        if (!claim.hasIssuedAt() || !claim.hasExpiresAt()) throw new IllegalArgumentException("validity window is required");
        Duration validity = Duration.between(instant(claim.getIssuedAt()), instant(claim.getExpiresAt()));
        if (validity.isZero() || validity.isNegative() || validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException("approval validity must be positive and at most five minutes");
        }
    }

    private static Instant instant(Timestamp value) {
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }
}
