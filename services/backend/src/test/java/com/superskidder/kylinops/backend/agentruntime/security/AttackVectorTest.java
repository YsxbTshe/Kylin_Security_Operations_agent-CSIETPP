package com.superskidder.kylinops.backend.agentruntime.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalClaim;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ExecutionMode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.RiskCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 7 迭代 7.1 验收基线 18.2（安全）——SM2 审批令牌攻击向量切片：
 * 伪造签名、未知密钥、过期、跨 run/跨节点/参数篡改、执行模式与风险等级篡改、
 * lockVersion 篡改全部必须被拒。控制面门控见 DispatchGateAcceptanceTest，
 * 审批仓库决策见 ApprovalRepositoryAcceptanceTest，节点端攻击面见 Go advbench 对抗电池。
 */
class AttackVectorTest {

    private static final Path VECTORS = Path.of("..", "..", "contracts", "test-vectors");
    private static final String KEY_ID = "approval-test-1";

    @Test
    void forgeryWithWrongKeyIsRejected() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        String token = service.issue(claim(now, "node-1"), KEY_ID, privateKey);

        String forged = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(IllegalArgumentException.class,
                () -> service.verify(forged, Map.of(KEY_ID, publicKey), now.plusSeconds(60)));
    }

    @Test
    void replayWithExpiredTokenIsRejected() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        String token = service.issue(claim(now, "node-1"), KEY_ID, privateKey);

        assertThrows(IllegalArgumentException.class,
                () -> service.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(301)));
    }

    @Test
    void crossNodeBindingIsEnforced() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        ApprovalClaim original = claim(now, "node-1");
        String token = service.issue(original, KEY_ID, privateKey);
        ApprovalClaim verified = service.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(60));

        assertFalse(service.covers(verified, claim(now, "node-2")));
        assertTrue(service.covers(verified, original));
    }

    @Test
    void crossRunBindingIsEnforced() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        ApprovalClaim original = claim(now, "node-1");
        String token = service.issue(original, KEY_ID, privateKey);
        ApprovalClaim verified = service.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(60));

        ApprovalClaim differentRun = original.toBuilder().setRunId("run-999").build();
        assertFalse(service.covers(verified, differentRun));
    }

    @Test
    void parameterTamperingViaArgumentsSm3IsDetected() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        ApprovalClaim original = claim(now, "node-1");
        String token = service.issue(original, KEY_ID, privateKey);
        ApprovalClaim verified = service.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(60));

        byte[] tamperedDigest = new byte[32];
        tamperedDigest[0] = (byte) 0xFF;
        ApprovalClaim tampered = original.toBuilder()
                .setArgumentsSm3(ByteString.copyFrom(tamperedDigest))
                .build();
        assertFalse(service.covers(verified, tampered));
    }

    @Test
    void executionModeTamperingIsDetected() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        ApprovalClaim original = claim(now, "node-1");
        String token = service.issue(original, KEY_ID, privateKey);
        ApprovalClaim verified = service.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(60));

        assertEquals(ExecutionMode.EXECUTION_MODE_CAUTIOUS, verified.getExecutionMode());
        ApprovalClaim modeSwitched = original.toBuilder()
                .setExecutionMode(ExecutionMode.EXECUTION_MODE_AUTO)
                .build();
        assertFalse(service.covers(verified, modeSwitched));
    }

    @Test
    void unknownKeyIdIsRejected() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        String token = service.issue(claim(now, "node-1"), KEY_ID, privateKey);

        assertThrows(IllegalArgumentException.class,
                () -> service.verify(token, Map.of("wrong-key-id", publicKey), now.plusSeconds(60)));
    }

    @Test
    void lockVersionTamperingIsDetected() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        ApprovalClaim original = claim(now, "node-1");
        String token = service.issue(original, KEY_ID, privateKey);
        ApprovalClaim verified = service.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(60));

        ApprovalClaim bumpedLock = original.toBuilder().setLockVersion(999).build();
        assertFalse(service.covers(verified, bumpedLock));
    }

    @Test
    void riskCeilingDowngradeIsDetected() throws Exception {
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(VECTORS.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        ApprovalClaim original = claim(now, "node-1");
        String token = service.issue(original, KEY_ID, privateKey);
        ApprovalClaim verified = service.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(60));

        assertEquals(RiskCode.RISK_CODE_R3, verified.getRiskCeiling());
        ApprovalClaim escalated = original.toBuilder().setRiskCeiling(RiskCode.RISK_CODE_R4).build();
        assertFalse(service.covers(verified, escalated));
    }

    private ApprovalClaim claim(Instant now, String nodeId) {
        return ApprovalClaim.newBuilder()
                .setVersion(1).setTenantId("org-alpha").setRunId("run-1")
                .setAgentInstanceId("agent-1").setAgentInvocationId("model-call-1")
                .setToolInvocationId("tool-call-1").setExecutionAttemptId("attempt-1")
                .setNodeId(nodeId).setToolId("kylin.service.restart").setToolVersion("1.0.0")
                .setArgumentsSm3(ByteString.copyFrom(new byte[32])).setRiskCeiling(RiskCode.RISK_CODE_R3)
                .setExecutionMode(ExecutionMode.EXECUTION_MODE_CAUTIOUS).setLockVersion(7)
                .setKeyPurpose(KeyPurpose.APPROVAL_SIGNING.wireName())
                .setIssuedAt(timestamp(now)).setExpiresAt(timestamp(now.plusSeconds(300)))
                .build();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
