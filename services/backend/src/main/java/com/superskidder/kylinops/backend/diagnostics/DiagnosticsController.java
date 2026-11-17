package com.superskidder.kylinops.backend.diagnostics;

import com.superskidder.kylinops.backend.abac.AbacDecision;
import com.superskidder.kylinops.backend.abac.AbacRequest;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.policy.IntentAlignmentGuard;
import com.superskidder.kylinops.backend.policy.PolicyDecision;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部诊断/试判接口（供隐藏的「诊断工具」页调用）。全部只读、无副作用，用于验证安全护栏与授权边界：
 * <ul>
 *   <li>{@code /abac}：ABAC 资源访问试判（default-deny + DENY 覆盖）。</li>
 *   <li>{@code /policy}：PolicyEngine 注入/风险试判（R0–R5 + 命中的硬规则）。</li>
 *   <li>{@code /approval/{id}}：审批令牌绑定检视（13 个身份字段 + 消费/过期状态）。</li>
 * </ul>
 * 权限：读取级 registry:read（与诊断工具页一致）。这些端点不改变任何状态。
 */
@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticsController {

    private final AccessControlService accessControl;
    private final ResourceAccessService resourceAccessService;
    private final PolicyEngine policyEngine;
    private final IntentAlignmentGuard intentAlignmentGuard;
    private final JdbcTemplate jdbcTemplate;

    public DiagnosticsController(AccessControlService accessControl,
                                 ResourceAccessService resourceAccessService,
                                 PolicyEngine policyEngine,
                                 IntentAlignmentGuard intentAlignmentGuard,
                                 JdbcTemplate jdbcTemplate) {
        this.accessControl = accessControl;
        this.resourceAccessService = resourceAccessService;
        this.policyEngine = policyEngine;
        this.intentAlignmentGuard = intentAlignmentGuard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** ABAC 资源访问试判：给定角色 + 资源类型/模式 + 动作（+风险），返回 ALLOW/DENY 与命中的策略。 */
    @PostMapping("/abac")
    public ApiResponse<AbacDecision> abac(@RequestBody AbacProbeRequest request) {
        accessControl.requirePermission("registry:read");
        Map<String, String> context = request.risk() != null && !request.risk().isBlank()
                ? Map.of("risk", request.risk().trim().toUpperCase())
                : Map.of();
        AbacRequest abacRequest = new AbacRequest(
                request.userId() == null ? "" : request.userId().trim(),
                request.roles() == null ? List.of() : request.roles(),
                request.resourceType(), request.resourceId(), request.action(), context);
        return ApiResponse.success(resourceAccessService.check(accessControl.currentOrganizationId(), abacRequest));
    }

    /** 安全管线试判（与生产 DispatchGate 同序）：PolicyEngine.evaluate → IntentAlignmentGuard.align。 */
    @PostMapping("/policy")
    public ApiResponse<PolicyProbeResult> policy(@RequestBody PolicyProbeRequest request) {
        accessControl.requirePermission("registry:read");
        String objective = request.objective() == null ? "" : request.objective();
        String toolId = request.toolId() == null ? "" : request.toolId();
        PolicyDecision decision = intentAlignmentGuard.align(objective, toolId,
                policyEngine.evaluate(objective, toolId,
                        request.argumentsJson() == null || request.argumentsJson().isBlank() ? "{}" : request.argumentsJson()));
        List<PolicyHitView> hits = decision.policyHits().stream()
                .map(hit -> new PolicyHitView(hit.policyId(), hit.message(),
                        hit.riskCode() == null ? null : hit.riskCode().name()))
                .toList();
        return ApiResponse.success(new PolicyProbeResult(
                decision.riskCode().name(), decision.requiresApproval(), decision.denied(),
                decision.policyVersion(), hits));
    }

    /** 审批令牌绑定检视：给定审批 ID，展示令牌绑定的 13 个身份字段与消费/过期状态（说明「批准的是什么就只能执行什么」）。 */
    @GetMapping("/approval/{id}")
    public ApiResponse<ApprovalBindingView> approval(@PathVariable String id) {
        accessControl.requirePermission("registry:read");
        String tenantId = accessControl.currentOrganizationId();
        List<ApprovalBindingView> rows = jdbcTemplate.query("""
                select a.id, a.decision, a.run_id, a.agent_instance_id, a.node_id, a.tool_id, a.tool_version,
                       a.arguments_digest, a.risk_ceiling, a.execution_mode, a.tool_invocation_id,
                       a.agent_invocation_id, a.expires_at, a.decided_at, a.created_at,
                       exists(select 1 from execution_attempt ea
                              where ea.tool_invocation_id = a.tool_invocation_id) as consumed
                from approval a
                where a.id = ? and a.tenant_id = ?
                """, (rs, n) -> {
            Instant expiresAt = rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null;
            boolean consumed = rs.getBoolean("consumed");
            String decision = rs.getString("decision");
            boolean expired = expiresAt != null && expiresAt.isBefore(Instant.now());
            boolean active = "APPROVED".equals(decision) && !consumed && !expired;
            return new ApprovalBindingView(
                    rs.getString("id"), decision, tenantId, rs.getString("run_id"),
                    rs.getString("agent_instance_id"), rs.getString("node_id"), rs.getString("tool_id"),
                    rs.getString("tool_version"), rs.getString("arguments_digest"), rs.getString("risk_ceiling"),
                    rs.getString("execution_mode"), rs.getString("tool_invocation_id"),
                    rs.getString("agent_invocation_id"),
                    expiresAt == null ? null : expiresAt.toString(),
                    rs.getTimestamp("decided_at") != null ? rs.getTimestamp("decided_at").toInstant().toString() : null,
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant().toString() : null,
                    consumed, expired, active);
        }, id, tenantId);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "审批不存在（或不属于当前组织）");
        }
        return ApiResponse.success(rows.get(0));
    }

    public record AbacProbeRequest(String userId, List<String> roles, String resourceType,
                                   String resourceId, String action, String risk) {}

    public record PolicyProbeRequest(String objective, String toolId, String argumentsJson) {}

    public record PolicyHitView(String policyId, String message, String riskCode) {}

    public record PolicyProbeResult(String riskCode, boolean requiresApproval, boolean denied,
                                    String policyVersion, List<PolicyHitView> hits) {}

    public record ApprovalBindingView(String id, String decision, String tenantId, String runId,
                                      String agentInstanceId, String nodeId, String toolId, String toolVersion,
                                      String argumentsDigest, String riskCeiling, String executionMode,
                                      String toolInvocationId, String agentInvocationId, String expiresAt,
                                      String decidedAt, String createdAt,
                                      boolean consumed, boolean expired, boolean active) {}
}
