package com.superskidder.kylinops.backend.agentruntime.security;

import com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalClaim;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 审批令牌绑定器：把"一次人工批准"精确绑定到"一次实际工具执行"。
 *
 * <p>绑定语义（ADR-002）由两半组成，本类是其控制面权威入口：
 * <ul>
 *   <li><b>铸造侧</b>：批准只授予一个有界执行窗口；worker 认领唯一 attempt 后，
 *       先校验实际参数的 SM3 摘要与批准时逐字节一致（批准的是什么就只能执行什么），
 *       再铸造覆盖「租户/run/实例/调用/attempt/节点/工具/版本/参数摘要/风险上限/
 *       执行模式/锁版本」的 SM2-with-SM3 令牌。摘要失配直接拒绝，不铸造。</li>
 *   <li><b>验证侧</b>：给定已验签的批准 claim 与实际调用 claim，逐字段复判覆盖关系
 *       （风险只允许小于等于批准上限，其余字段必须精确相等）。端侧 privhelper
 *       持有同构的独立实现，本方法供控制面自检与基准评测使用。</li>
 * </ul>
 */
@Component
public class ApprovalTokenBinder {

    public static final String APPROVAL_BINDING_MISMATCH = "APPROVAL_BINDING_MISMATCH";

    private final ApprovalTokenLifecycle tokenLifecycle;
    private final Sm2ApprovalTokenService tokenService;

    public ApprovalTokenBinder(ApprovalTokenLifecycle tokenLifecycle, Sm2ApprovalTokenService tokenService) {
        this.tokenLifecycle = tokenLifecycle;
        this.tokenService = tokenService;
    }

    /**
     * 校验参数摘要与批准时一致后，为该 attempt 铸造精确绑定的 SM2 审批令牌。
     *
     * @throws BindingMismatchException 实际参数与批准时的 SM3 摘要不一致
     */
    public ApprovalTokenLifecycle.IssuedToken bind(BindRequest request) {
        String actualDigest = com.superskidder.kylinops.backend.crypto.Sm3.hex(request.canonicalArguments());
        if (!actualDigest.equals(request.approvedArgumentsDigest())) {
            throw new BindingMismatchException("Arguments no longer match the approved request");
        }
        return tokenLifecycle.issueToken(
                request.tenantId(), request.runId(), request.agentInstanceId(),
                request.agentInvocationId(), request.toolInvocationId(), request.executionAttemptId(),
                request.nodeId(), request.toolId(), request.toolVersion(),
                com.superskidder.kylinops.backend.crypto.Sm3.rawHash(
                        request.canonicalArguments().getBytes(StandardCharsets.UTF_8)),
                request.riskCeiling(), request.executionMode(), request.lockVersion());
    }

    /** 验签 + 时效校验，返回令牌内的批准 claim。任何失败抛 {@link IllegalArgumentException}。 */
    public ApprovalClaim verify(String encodedToken, Map<String, PublicKey> keys, Instant now) {
        return tokenService.verify(encodedToken, keys, now);
    }

    /** 已批准 claim 是否逐字段覆盖实际调用 claim。 */
    public boolean covers(ApprovalClaim approved, ApprovalClaim actual) {
        return tokenService.covers(approved, actual);
    }

    public record BindRequest(
            String tenantId,
            String runId,
            String agentInstanceId,
            String agentInvocationId,
            String toolInvocationId,
            String executionAttemptId,
            String nodeId,
            String toolId,
            String toolVersion,
            String canonicalArguments,
            String approvedArgumentsDigest,
            String riskCeiling,
            String executionMode,
            long lockVersion
    ) {}

    public static final class BindingMismatchException extends RuntimeException {
        public BindingMismatchException(String message) {
            super(message);
        }
    }
}
