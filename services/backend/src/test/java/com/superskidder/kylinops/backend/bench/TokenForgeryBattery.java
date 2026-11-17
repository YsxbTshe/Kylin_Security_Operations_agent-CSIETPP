package com.superskidder.kylinops.backend.bench;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.superskidder.kylinops.backend.agentruntime.security.KeyPurpose;
import com.superskidder.kylinops.backend.agentruntime.security.Sm2ApprovalTokenService;
import com.superskidder.kylinops.backend.agentruntime.security.Sm2WithSm3;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalClaim;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ExecutionMode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.RiskCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 审批令牌伪造对抗电池：与 bench/agent/cases.json 的 token_forgery 组一一对应，
 * 全部打在生产 {@link Sm2ApprovalTokenService}（验签/时效）与 covers（13 个身份字段的覆盖复判）上。
 */
final class TokenForgeryBattery {

    private static final String KEY_ID = "approval-bench-1";

    private final Sm2ApprovalTokenService tokenService = new Sm2ApprovalTokenService();
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final Instant now = Instant.ofEpochSecond(1_800_000_000L);

    record Result(String caseId, String scenario, boolean rejected, String detail) {}

    TokenForgeryBattery(Path repositoryRoot) {
        Path vectors = repositoryRoot.resolve("contracts").resolve("test-vectors");
        try {
            this.privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_private.pem")));
            this.publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_public.pem")));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot load SM2 test vectors", exception);
        }
    }

    /** 逐场景执行；rejected=true 表示攻击被正确拒绝。 */
    List<Result> run() {
        List<Result> results = new ArrayList<>();
        results.add(new Result("TOK-01", "tampered_claim", tamperedClaimRejected(), "篡改签名尾部字节后验签失败"));
        results.add(new Result("TOK-02", "unknown_key", unknownKeyRejected(), "keyId 不在平台信任列表"));
        results.add(new Result("TOK-03", "expired", expiredRejected(), "有效期 5 分钟后重放被时效校验拒绝"));
        results.add(new Result("TOK-04", "malformed", malformedRejected(), "错误前缀/随机字节在解析层拒绝"));
        results.add(new Result("TOK-05", "coverage_mismatch", coverageMismatchRejected(), "参数 SM3 失配被覆盖复判拒绝（arguments_sm3 属 13 个身份字段之一）"));
        return results;
    }

    /** 合法令牌阳性对照：可验证且覆盖自身调用。 */
    boolean legitimateTokenVerifiable() {
        ApprovalClaim claim = claim("kylin.service.restart", new byte[32]);
        String token = tokenService.issue(claim, KEY_ID, privateKey);
        ApprovalClaim verified = tokenService.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(30));
        return tokenService.covers(verified, claim);
    }

    private boolean tamperedClaimRejected() {
        String token = tokenService.issue(claim("kylin.service.restart", new byte[32]), KEY_ID, privateKey);
        String forged = token.substring(0, token.length() - 4) + "AAAA";
        return verifyThrows(forged, now.plusSeconds(30));
    }

    private boolean unknownKeyRejected() {
        String token = tokenService.issue(claim("kylin.service.restart", new byte[32]), "rogue-key-1", privateKey);
        return verifyThrows(token, now.plusSeconds(30));
    }

    private boolean expiredRejected() {
        String token = tokenService.issue(claim("kylin.service.restart", new byte[32]), KEY_ID, privateKey);
        return verifyThrows(token, now.plusSeconds(301));
    }

    private boolean malformedRejected() {
        return verifyThrows("kylin-approval-v1.not-base64!!!", now)
                && verifyThrows("totally-wrong-prefix." + "AAAA".repeat(16), now);
    }

    private boolean coverageMismatchRejected() {
        ApprovalClaim approved = claim("kylin.service.restart", new byte[32]);
        String token = tokenService.issue(approved, KEY_ID, privateKey);
        ApprovalClaim verified = tokenService.verify(token, Map.of(KEY_ID, publicKey), now.plusSeconds(30));
        byte[] otherDigest = new byte[32];
        otherDigest[0] = (byte) 0xFF;
        ApprovalClaim actual = claim("kylin.service.restart", otherDigest);
        return !tokenService.covers(verified, actual);
    }

    private boolean verifyThrows(String token, Instant at) {
        try {
            tokenService.verify(token, Map.of(KEY_ID, publicKey), at);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private ApprovalClaim claim(String toolId, byte[] argumentsSm3) {
        return ApprovalClaim.newBuilder()
                .setVersion(1).setTenantId("org-alpha").setRunId("run-bench")
                .setAgentInstanceId("agent-1").setAgentInvocationId("model-call-1")
                .setToolInvocationId("tool-call-1").setExecutionAttemptId("attempt-1")
                .setNodeId("node-1").setToolId(toolId).setToolVersion("1.0.0")
                .setArgumentsSm3(ByteString.copyFrom(argumentsSm3)).setRiskCeiling(RiskCode.RISK_CODE_R3)
                .setExecutionMode(ExecutionMode.EXECUTION_MODE_CAUTIOUS).setLockVersion(7)
                .setKeyPurpose(KeyPurpose.APPROVAL_SIGNING.wireName())
                .setIssuedAt(timestamp(now)).setExpiresAt(timestamp(now.plusSeconds(300)))
                .build();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
