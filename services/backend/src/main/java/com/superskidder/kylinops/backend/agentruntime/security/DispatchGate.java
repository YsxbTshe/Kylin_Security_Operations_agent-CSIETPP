package com.superskidder.kylinops.backend.agentruntime.security;

import com.superskidder.kylinops.backend.abac.AbacDecision;
import com.superskidder.kylinops.backend.abac.AbacPolicyEngine;
import com.superskidder.kylinops.backend.abac.AbacRequest;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.abac.ResourcePolicy;
import com.superskidder.kylinops.backend.policy.IntentAlignmentGuard;
import com.superskidder.kylinops.backend.policy.NativeGuardGate;
import com.superskidder.kylinops.backend.policy.PolicyDecision;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import com.superskidder.kylinops.backend.policy.PolicyHit;
import com.superskidder.kylinops.backend.policy.RiskCode;
import com.superskidder.kylinops.backend.registry.AgentAuthorization;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DispatchGate {

    /** 专家 Agent 越出注册封套（工具不在允许集 / 风险超上限 / 已停用 / 未注册）。 */
    public static final String REJECT_AGENT_ENVELOPE_DENIED = "AGENT_ENVELOPE_DENIED";

    private final PolicyEngine policyEngine;
    private final IntentAlignmentGuard intentAlignmentGuard;
    private final AbacPolicyEngine abacPolicyEngine;
    private final ResourceAccessService resourceAccessService;
    private final AgentRegistryService agentRegistryService;

    public DispatchGate(PolicyEngine policyEngine, IntentAlignmentGuard intentAlignmentGuard,
                        AbacPolicyEngine abacPolicyEngine,
                        ResourceAccessService resourceAccessService,
                        AgentRegistryService agentRegistryService) {
        this.policyEngine = policyEngine;
        this.intentAlignmentGuard = intentAlignmentGuard;
        this.abacPolicyEngine = abacPolicyEngine;
        this.resourceAccessService = resourceAccessService;
        this.agentRegistryService = agentRegistryService;
    }

    public Decision evaluate(DispatchRequest request) {
        if (!request.grantToolIds().isEmpty() && !request.grantToolIds().contains(request.toolId())) {
            return Decision.rejected("GRANT_EXCLUDES_TOOL",
                    "Run grant does not permit tool: " + request.toolId());
        }
        if (request.grantReadOnly() && !request.readOnly()) {
            return Decision.rejected("GRANT_READ_ONLY",
                    "Run grant is read-only but tool requires write");
        }

        PolicyDecision policyDecision = intentAlignmentGuard.align(
                request.objective(), request.toolId(), request.readOnly(),
                policyEngine.evaluate(request.objective(), request.toolId(), request.argumentsJson()));

        if (policyDecision.denied()) {
            boolean intentMismatch = policyDecision.policyHits().stream()
                    .map(PolicyHit::policyId)
                    .anyMatch(IntentAlignmentGuard.POLICY_INTENT_MISMATCH::equals);
            if (intentMismatch) {
                return Decision.rejected(IntentAlignmentGuard.POLICY_INTENT_MISMATCH,
                        "User intent is advisory but tool performs a write: " + request.toolId(),
                        policyDecision);
            }
            return Decision.rejected("POLICY_DENIED",
                    "Policy engine denied tool: " + request.toolId(),
                    policyDecision);
        }

        // 生效风险 = max(策略裁定, 工具自报下限)。
        //
        // 当前**不是**在修一个已知漏洞：PolicyEngine.evaluateSoftRules 里那句
        // `default -> R0` 看着像 fail-open，但实际不可达——manifestRisk 走
        // PolicyToolCatalog.describeTool，对目录外的工具返回一个 fail-closed 的
        // 兜底定义（readOnly=false / requiresApproval=true / riskCode=R3）。
        // 也就是说未知工具今天已经被判 R3，不会被 AUTO（上限 R2）自动放行。
        //
        // 这里取 max 是把该性质变成**显式且局部可验证的不变量**：riskFloor 是地板，
        // 策略只能把风险往上抬（敏感路径、安全姿态升级等软规则），永远不能把工具
        // 自报的下限往下压。它防的是未来的退化——例如有人给 describeTool 换一个
        // 更宽松的兜底、或新增一个不经 PolicyToolCatalog 的 ToolDefinitionProvider，
        // 那时这道地板仍在。行为上对当前全部工具是 no-op（467 项测试可证）。
        //
        // 下游全部判定（R5 硬拒 / PLAN 禁写 / ABAC 风险条件 / host operate-read /
        // Native Guard R3+ 门 / AUTO 上限）都读这个变量，因此一处收口即可。
        RiskCode risk = effectiveRisk(policyDecision.riskCode(), request.toolRiskFloor());
        if (risk == RiskCode.R5) {
            return Decision.rejected("R5_FORBIDDEN",
                    "R5 risk tools are unconditionally forbidden",
                    policyDecision);
        }

        if ("PLAN".equalsIgnoreCase(request.executionMode()) && risk.ordinal() >= RiskCode.R2.ordinal()) {
            return Decision.rejected("PLAN_MODE_FORBIDS_WRITE",
                    "PLAN execution mode forbids risk " + risk + " operations",
                    policyDecision);
        }

        if (!request.grantNodeIds().isEmpty() && !request.grantNodeIds().contains(request.nodeId())) {
            return Decision.rejected("GRANT_EXCLUDES_NODE",
                    "Run grant does not permit node: " + request.nodeId());
        }

        AbacDecision abacDecision = resourceAccessService.check(
                request.tenantId(),
                new AbacRequest(request.humanPrincipalId(), request.roles(),
                        "agent-tool", request.toolId(), "execute",
                        Map.of("risk", risk.name(), "nodeId", request.nodeId())));
        if (!abacDecision.allowed()) {
            return Decision.rejected("ABAC_DENIED",
                    "ABAC denied: " + abacDecision.reason(),
                    policyDecision);
        }

        // Agent IAM：专家注册表的能力封套（ADR-003 §2.3）。
        //
        // 与前后两步是**正交**的三个问题：ABAC 工具维问「这个**人**能不能用这把工具」，
        // 本步问「这个**专家 Agent** 能不能用这把工具」，ABAC 主机维问「能不能对**这台机器**动手」。
        // 三者串联，任一 DENY 即拒——即使 Sub Agent 被提示注入攻陷，也只能在注册封套内行动。
        //
        // 位置理由（为什么恰好夹在两维 ABAC 之间）：
        //   · 必须在**工具维 ABAC 之后**：人的授权是 Agent 授权的上界。发起人自己就没有
        //     这把工具的权限时，应当报 ABAC_DENIED（人的问题），而不是把责任推给 Agent 封套；
        //     拒绝码要指向正确的责任方，否则运维会去改注册表而不是改人的角色。
        //     同时本步要用 effectiveRisk，它在 R5/PLAN 判定处才算出。
        //   · 必须在**主机维 ABAC 之前**：能力封套是与目标主机无关的**身份属性**，比
        //     「能不能对这台机器动手」更内层、更廉价，也更稳定——换一台主机重试不会改变
        //     封套结论。若放在主机维之后，越封套的调用会先被 HOST 策略吸收成
        //     ABAC_DENIED_HOST，掩盖真正原因。
        //
        // agentKey 为空 → **不判定**。根 Agent（人直接发起）与存量 run 本来就没有注册身份，
        // 把「无身份」当作「拒绝」会一次性打断现有全部链路。fail-closed 的落点是
        // 「有身份就必须在封套内」；authorize 自身对未注册/停用/越界/超风险一律拒。
        String agentKey = request.agentKey() == null ? "" : request.agentKey().trim();
        if (!agentKey.isEmpty()) {
            AgentAuthorization envelope = agentRegistryService.authorize(
                    request.tenantId(), agentKey, request.toolId(), risk.name());
            if (!envelope.allowed()) {
                return Decision.rejected(REJECT_AGENT_ENVELOPE_DENIED,
                        "Agent capability envelope denied [" + agentKey + "]: " + envelope.reason(),
                        policyDecision);
            }
        }

        // 资源级 ABAC 的第二维：目标主机（ADR-003 §2.1）。
        //
        // 工具维度只回答「这个人能不能用这把工具」，回答不了「能不能对这台机器用」。
        // 此前 nodeId 仅作为 context 传入，而 AbacPolicyEngine.applies 只比对
        // subject/resourceType/resourcePattern/action/condition —— 从不读 context，
        // 于是库里全部 HOST 型策略（如 rp-secops-prod-db-deny：对 prod-db-* 的 operate
        // 显式拒绝）都是永不生效的死策略。这里把 nodeId 提升为**被判定的资源本身**。
        //
        // 动作口径以 V1__baseline 的 HOST 策略种子为准：写（R2 及以上）为 operate，
        // 只读为 read。风险随 context 下传，供策略的 maxRisk 条件收紧。
        // 与工具维度串联：任一维度 DENY 即拒；两维都无匹配 ALLOW 也拒（fail-closed）。
        String hostAction = risk.ordinal() >= RiskCode.R2.ordinal() ? "operate" : "read";
        AbacDecision hostDecision = resourceAccessService.check(
                request.tenantId(),
                new AbacRequest(request.humanPrincipalId(), request.roles(),
                        "HOST", request.nodeId(), hostAction,
                        Map.of("risk", risk.name(), "toolId", request.toolId())));
        if (!hostDecision.allowed()) {
            return Decision.rejected("ABAC_DENIED_HOST",
                    "ABAC denied host " + request.nodeId() + " for action " + hostAction
                            + ": " + hostDecision.reason(),
                    policyDecision);
        }

        if (risk.ordinal() >= RiskCode.R3.ordinal()) {
            NativeGuardGate.Availability guardAvailability = NativeGuardGate.availability(
                    request.nativeGuardStatus(), request.nativeGuardCheckedAt(),
                    Instant.now(), request.nativeGuardMaxAgeMinutes());
            if (guardAvailability != NativeGuardGate.Availability.FRESH) {
                return Decision.rejected("NATIVE_GUARD_" + guardAvailability,
                        "Native Guard posture is " + guardAvailability + " for R3+ operation",
                        policyDecision);
            }
            // 姿态新鲜但明确 CRITICAL（KYSEC/auditd/安全开关处于危险状态）：控制面先拒，
            // 与端侧 guardrail 的姿态联动判据一致（那里仍会独立复判，不依赖这里）。
            if ("CRITICAL".equalsIgnoreCase(request.nativeGuardStatus().trim())) {
                return Decision.rejected("NATIVE_GUARD_CRITICAL",
                        "Native Guard posture is CRITICAL; R3+ operation is denied",
                        policyDecision);
            }
        }

        if (policyDecision.requiresApproval() || request.alwaysRequireApproval()) {
            if ("AUTO".equalsIgnoreCase(request.executionMode())
                    && risk.ordinal() <= autoRiskCeiling(request.autoRiskCeiling())) {
                return Decision.allowed(risk, policyDecision, true);
            }
            return Decision.needsApproval(risk, policyDecision);
        }

        return Decision.allowed(risk, policyDecision, false);
    }

    /**
     * 生效风险取「策略裁定」与「工具自报下限」的较大者。
     *
     * <p>riskFloor 缺失或无法解析时按 R0 处理（{@link RiskCode#normalize} 的约定），
     * 等价于「不抬高」，因此对没有声明 riskFloor 的工具行为完全不变。
     */
    private RiskCode effectiveRisk(RiskCode policyRisk, String toolRiskFloor) {
        RiskCode policy = policyRisk == null ? RiskCode.R0 : policyRisk;
        RiskCode floor;
        try {
            floor = RiskCode.normalize(toolRiskFloor);
        } catch (Exception exception) {
            // 无法解析的 riskFloor 不能当作 R0 放过：工具声明了却写错，按最高风险处理。
            return RiskCode.R5;
        }
        return policy.ordinal() >= floor.ordinal() ? policy : floor;
    }

    private int autoRiskCeiling(String ceiling) {
        if (ceiling == null || ceiling.isBlank()) {
            return RiskCode.R2.ordinal();
        }
        try {
            return RiskCode.valueOf(ceiling.trim().toUpperCase()).ordinal();
        } catch (Exception exception) {
            return RiskCode.R2.ordinal();
        }
    }

    public record DispatchRequest(
            String tenantId,
            String runId,
            String humanPrincipalId,
            List<String> roles,
            String objective,
            String nodeId,
            String toolId,
            String argumentsJson,
            boolean readOnly,
            boolean alwaysRequireApproval,
            /** 工具自报的风险下限（NodeTool.riskFloor()）；与策略裁定取 max，见 evaluate。 */
            String toolRiskFloor,
            String executionMode,
            List<String> grantToolIds,
            boolean grantReadOnly,
            List<String> grantNodeIds,
            String nativeGuardStatus,
            Instant nativeGuardCheckedAt,
            long nativeGuardMaxAgeMinutes,
            String autoRiskCeiling,
            /**
             * 发起本次调用的专家 Agent 在注册表中的 key（{@code app_agent_registry.agent_key}）。
             * <b>可空</b>：根 Agent 与存量 run 没有注册身份，此时不做能力封套判定。
             */
            String agentKey
    ) {

        /**
         * 无注册身份的下发请求（根 Agent / 存量 run / 不经注册表的调用方）。
         *
         * <p>保留旧的 19 参数形状，等价于 {@code agentKey = null}，即「不做注册表判定」。
         * 这不是为了少改几处调用点——而是让「没有专家身份的调用行为与接入前逐字节一致」
         * 成为一条**编译期就能看见**的约定，而不是散落在各调用点的一串 null。
         */
        public DispatchRequest(
                String tenantId, String runId, String humanPrincipalId, List<String> roles,
                String objective, String nodeId, String toolId, String argumentsJson,
                boolean readOnly, boolean alwaysRequireApproval, String toolRiskFloor,
                String executionMode, List<String> grantToolIds, boolean grantReadOnly,
                List<String> grantNodeIds, String nativeGuardStatus, Instant nativeGuardCheckedAt,
                long nativeGuardMaxAgeMinutes, String autoRiskCeiling
        ) {
            this(tenantId, runId, humanPrincipalId, roles, objective, nodeId, toolId, argumentsJson,
                    readOnly, alwaysRequireApproval, toolRiskFloor, executionMode, grantToolIds,
                    grantReadOnly, grantNodeIds, nativeGuardStatus, nativeGuardCheckedAt,
                    nativeGuardMaxAgeMinutes, autoRiskCeiling, null);
        }
    }

    public record Decision(
            Outcome outcome,
            RiskCode riskCode,
            String rejectionCode,
            String rejectionReason,
            PolicyDecision policyDecision,
            boolean autoApproved
    ) {
        public enum Outcome { ALLOWED, NEEDS_APPROVAL, REJECTED }

        public static Decision allowed(RiskCode risk, PolicyDecision policy, boolean autoApproved) {
            return new Decision(Outcome.ALLOWED, risk, null, null, policy, autoApproved);
        }

        public static Decision needsApproval(RiskCode risk, PolicyDecision policy) {
            return new Decision(Outcome.NEEDS_APPROVAL, risk, null, null, policy, false);
        }

        public static Decision rejected(String code, String reason) {
            return new Decision(Outcome.REJECTED, RiskCode.R5, code, reason, null, false);
        }

        public static Decision rejected(String code, String reason, PolicyDecision policy) {
            RiskCode risk = policy != null ? policy.riskCode() : RiskCode.R5;
            return new Decision(Outcome.REJECTED, risk, code, reason, policy, false);
        }
    }
}
