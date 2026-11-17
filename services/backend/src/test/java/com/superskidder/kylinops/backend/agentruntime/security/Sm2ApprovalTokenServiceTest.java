package com.superskidder.kylinops.backend.agentruntime.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Phase 7 迭代 7.1 验收基线 18.2（安全）——SM2 审批令牌签发/验签契约切片：
 * Java-Go 共享测试向量、12 元绑定（covers）、过期窗口（5 分钟 + 边界外拒绝）、
 * 密钥用途分离（审批签名 ≠ 目录签名）。
 */
class Sm2ApprovalTokenServiceTest {
    @Test
    void verifiesSharedJavaGoVector() throws Exception {
        Path vectors = Path.of("..", "..", "contracts", "test-vectors");
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_public.pem")));
        String token = new ObjectMapper().readTree(vectors.resolve("approval-token-v1.json").toFile())
                .path("token").asText();
        ApprovalClaim claim = new Sm2ApprovalTokenService().verify(
                token,
                Map.of("approval-test-1", publicKey),
                Instant.parse("2027-01-15T08:01:00Z")
        );
        assertTrue(claim.getRunId().equals("run-vector-1") && claim.getLockVersion() == 42);
    }

    @Test
    void issuesVerifiesAndBindsExactInvocation() throws Exception {
        Path vectors = Path.of("..", "..", "contracts", "test-vectors");
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_public.pem")));
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        ApprovalClaim claim = claim(now, "node-1");

        Sm2ApprovalTokenService service = new Sm2ApprovalTokenService();
        String token = service.issue(claim, "approval-test-1", privateKey);
        ApprovalClaim verified = service.verify(token, Map.of("approval-test-1", publicKey), now.plusSeconds(60));

        assertTrue(service.covers(verified, claim));
        assertFalse(service.covers(verified, claim(now, "node-2")));
        assertThrows(IllegalArgumentException.class,
                () -> service.verify(token, Map.of("approval-test-1", publicKey), now.plusSeconds(301)));
    }

    @Test
    void keyPurposeSeparatesApprovalFromCatalog() throws Exception {
        Path vectors = Path.of("..", "..", "contracts", "test-vectors");
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_public.pem")));
        byte[] payload = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = Sm2WithSm3.sign(privateKey, KeyPurpose.APPROVAL_SIGNING, payload);
        assertTrue(Sm2WithSm3.verify(publicKey, KeyPurpose.APPROVAL_SIGNING, payload, signature));
        assertFalse(Sm2WithSm3.verify(publicKey, KeyPurpose.CATALOG_SIGNING, payload, signature));
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
