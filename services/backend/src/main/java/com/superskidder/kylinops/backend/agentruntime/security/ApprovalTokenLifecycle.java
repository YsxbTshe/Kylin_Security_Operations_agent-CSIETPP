package com.superskidder.kylinops.backend.agentruntime.security;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalClaim;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ExecutionMode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.RiskCode;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ApprovalTokenLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTokenLifecycle.class);
    private static final Duration TOKEN_VALIDITY = Duration.ofMinutes(5);
    private static final Duration RENEWAL_THRESHOLD = Duration.ofSeconds(60);

    private final Sm2ApprovalTokenService tokenService;
    private final PlatformKeyReferences keyReferences;
    private final ApprovalKeyLoader keyLoader;

    public ApprovalTokenLifecycle(Sm2ApprovalTokenService tokenService,
                                  PlatformKeyReferences keyReferences,
                                  ApprovalKeyLoader keyLoader) {
        this.tokenService = tokenService;
        this.keyReferences = keyReferences;
        this.keyLoader = keyLoader;
    }

    public IssuedToken issueToken(String tenantId, String runId, String agentInstanceId,
                                   String agentInvocationId, String toolInvocationId,
                                   String executionAttemptId, String nodeId, String toolId,
                                   String toolVersion, byte[] argumentsSm3, String riskCode,
                                   String executionMode, long lockVersion) {
        Instant now = Instant.now();
        ApprovalClaim claim = ApprovalClaim.newBuilder()
                .setVersion(Sm2ApprovalTokenService.TOKEN_VERSION)
                .setTenantId(tenantId)
                .setRunId(runId)
                .setAgentInstanceId(agentInstanceId)
                .setAgentInvocationId(agentInvocationId)
                .setToolInvocationId(toolInvocationId)
                .setExecutionAttemptId(executionAttemptId)
                .setNodeId(nodeId)
                .setToolId(toolId)
                .setToolVersion(toolVersion)
                .setArgumentsSm3(ByteString.copyFrom(argumentsSm3))
                .setRiskCeiling(riskCodeFor(riskCode))
                .setExecutionMode(executionModeFor(executionMode))
                .setLockVersion(lockVersion)
                .setKeyPurpose(KeyPurpose.APPROVAL_SIGNING.wireName())
                .setIssuedAt(timestamp(now))
                .setExpiresAt(timestamp(now.plus(TOKEN_VALIDITY)))
                .build();

        PlatformKeyReferences.KeyReference keyRef = keyReferences.forPurpose(KeyPurpose.APPROVAL_SIGNING);
        PrivateKey privateKey = keyLoader.loadPrivateKey(keyRef.privateKeyRef());
        String encoded = tokenService.issue(claim, keyRef.keyId(), privateKey);
        return new IssuedToken(encoded, keyRef.keyId(), now.plus(TOKEN_VALIDITY));
    }

    public boolean needsRenewal(Instant expiresAt) {
        return expiresAt != null && Instant.now().plus(RENEWAL_THRESHOLD).isAfter(expiresAt);
    }

    private RiskCode riskCodeFor(String riskCode) {
        if (riskCode == null) return RiskCode.RISK_CODE_R2;
        return switch (riskCode.trim().toUpperCase()) {
            case "R0" -> RiskCode.RISK_CODE_R0;
            case "R1" -> RiskCode.RISK_CODE_R1;
            case "R2" -> RiskCode.RISK_CODE_R2;
            case "R3" -> RiskCode.RISK_CODE_R3;
            case "R4" -> RiskCode.RISK_CODE_R4;
            default -> RiskCode.RISK_CODE_R2;
        };
    }

    private ExecutionMode executionModeFor(String mode) {
        if (mode == null) return ExecutionMode.EXECUTION_MODE_CAUTIOUS;
        return switch (mode.trim().toUpperCase()) {
            case "AUTO" -> ExecutionMode.EXECUTION_MODE_AUTO;
            case "CAUTIOUS" -> ExecutionMode.EXECUTION_MODE_CAUTIOUS;
            default -> ExecutionMode.EXECUTION_MODE_CAUTIOUS;
        };
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    public record IssuedToken(String encoded, String keyId, Instant expiresAt) {}
}
