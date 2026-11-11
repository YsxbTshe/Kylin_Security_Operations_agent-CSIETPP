package com.superskidder.kylinops.backend.agentruntime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.superskidder.kylinops.backend.abac.AbacDecision;
import com.superskidder.kylinops.backend.abac.AbacPolicyEngine;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.policy.IntentAlignmentGuard;
import com.superskidder.kylinops.backend.policy.PolicyDecision;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import com.superskidder.kylinops.backend.policy.RiskCode;
import com.superskidder.kylinops.backend.registry.AgentAuthorization;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 7 迭代 7.1 验收基线 18.2（安全）——控制面 DispatchGate 切片。
 *
 * 18.2 要求「R5、ABAC、AgentGrant、SkillScope、NodeLocalPolicy 均不能被 Approval 绕过」
 * 以及「权限撤销后调用被拒」。DispatchGate 是控制面唯一的下发门：
 *   - Run grant（AgentGrant）收窄/撤销 → GRANT_EXCLUDES_TOOL / GRANT_READ_ONLY / GRANT_EXCLUDES_NODE；
 *   - ABAC 资源策略被撤销（check 返回拒绝）→ ABAC_DENIED；
 *   - R5 与 PLAN 模式写操作在任何审批姿态下都直接拒绝（审批不能为其开路）。
 * 节点端对应的攻击面（伪造/过期/重放令牌、PLAN 外部写）由 Go advbench 对抗电池覆盖。
 */
class DispatchGateAcceptanceTest {

    private static final String TENANT_ID = "org-alpha";

    private final PolicyEngine policyEngine = mock(PolicyEngine.class);
    private final AbacPolicyEngine abacPolicyEngine = mock(AbacPolicyEngine.class);
    private final ResourceAccessService resourceAccessService = mock(ResourceAccessService.class);
    private final AgentRegistryService agentRegistryService = mock(AgentRegistryService.class);
    private final DispatchGate gate = new DispatchGate(policyEngine,
            new IntentAlignmentGuard(toolNames -> List.of()), abacPolicyEngine, resourceAccessService,
            agentRegistryService);

    private DispatchGate.DispatchRequest request(RiskCode risk, boolean readOnly, String executionMode,
                                                 List<String> grantToolIds, boolean grantReadOnly,
                                                 List<String> grantNodeIds) {
        return new DispatchGate.DispatchRequest(
                TENANT_ID, "run-1", "user-alpha", List.of(),
                "restart nginx", "node-1", "kylin.service.restart", "{\"service\":\"nginx\"}",
                readOnly, false, /* riskFloor */ "R0", executionMode, grantToolIds, grantReadOnly, grantNodeIds,
                "AVAILABLE", Instant.now(), 5, "R2");
    }

    private DispatchGate.DispatchRequest requestWithPosture(String guardStatus, Instant observedAt) {
        return new DispatchGate.DispatchRequest(
                TENANT_ID, "run-1", "user-alpha", List.of(),
                "restart nginx", "node-1", "kylin.service.restart", "{\"service\":\"nginx\"}",
                false, false, /* riskFloor */ "R0", "CAUTIOUS", List.of(), false, List.of(),
                guardStatus, observedAt, 5, "R2");
    }

    /**
     * riskFloor 是地板：策略把风险判低时，以工具自报的下限为准。
     *
     * <p>这不是在修一个已知漏洞——今天 PolicyToolCatalog.describeTool 对目录外的工具
     * 已经 fail-closed 到 R3，所以生产上不存在「策略判 R0 而工具自报 R3」的组合。
     * 这条测试锁的是 DispatchGate 自身的局部不变量：**无论上游策略给出什么，
     * 生效风险都不会低于工具自报的 riskFloor**。上游兜底一旦被改宽松（换 provider、
     * 改 describeTool），这条会立刻红，而不是等到 AUTO 模式静默放行一次写操作。
     */
    @Test
    void toolRiskFloorRaisesEffectiveRiskWhenPolicyGradesLower() {
        stubPolicy(RiskCode.R0, false);

        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                TENANT_ID, "run-1", "user-alpha", List.of(),
                "invoke external connector", "node-1", "kylin.connector.invoke", "{}",
                /* readOnly */ false, /* alwaysRequireApproval */ true, /* riskFloor */ "R3",
                "AUTO", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, /* autoRiskCeiling */ "R2"));

        // 策略说 R0（0 ≤ AUTO 上限 R2）本会被自动批准；地板抬到 R3 后必须转人工。
        assertThat(decision.riskCode()).isEqualTo(RiskCode.R3);
        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.NEEDS_APPROVAL);
        assertThat(decision.autoApproved()).isFalse();
    }

    /** 反向：策略判得比 riskFloor 高时以策略为准（地板不封顶）。 */
    @Test
    void policyRiskWinsWhenHigherThanToolRiskFloor() {
        stubPolicy(RiskCode.R4, true);

        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                TENANT_ID, "run-1", "user-alpha", List.of(),
                "restart nginx", "node-1", "kylin.service.restart", "{}",
                false, true, /* riskFloor */ "R0", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2"));

        assertThat(decision.riskCode()).isEqualTo(RiskCode.R4);
        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.NEEDS_APPROVAL);
    }

    /**
     * 专家注册表接入后的**不误伤**不变量：{@code agentKey} 为空时，注册表**一次都不被查**。
     *
     * <p>只断言「结论没变」是不够的——注册表恰好放行也能让结论不变。这里直接钉死交互次数：
     * 根 Agent 与存量 run 没有注册身份，判定链必须与接入前逐字节一致，
     * 任何"顺手查一下"的实现都会让这条红。
     */
    @Test
    void registryIsNotConsultedWhenAgentKeyIsAbsent() {
        stubPolicy(RiskCode.R1, false);

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R1, false, "CAUTIOUS", List.of(), false, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);
        verifyNoInteractions(agentRegistryService);
    }

    /** agentKey 非空即判定，且拒绝时带上 authorize 给出的原因（不吞掉判据）。 */
    @Test
    void agentOutsideItsRegisteredEnvelopeIsRejected() {
        stubPolicy(RiskCode.R1, false);
        when(agentRegistryService.authorize(TENANT_ID, "sense-collector", "kylin.service.restart", "R1"))
                .thenReturn(AgentAuthorization.deny("工具不在该 Agent 能力封套内: kylin.service.restart"));

        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                TENANT_ID, "run-1", "user-alpha", List.of(),
                "restart nginx", "node-1", "kylin.service.restart", "{\"service\":\"nginx\"}",
                false, false, /* riskFloor */ "R0", "CAUTIOUS", List.of(), false, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2", "sense-collector"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo(DispatchGate.REJECT_AGENT_ENVELOPE_DENIED);
        assertThat(decision.rejectionReason()).contains("sense-collector").contains("能力封套");
    }

    private void stubPolicy(RiskCode risk, boolean requiresApproval) {
        when(policyEngine.evaluate(any(), any(), any()))
                .thenReturn(new PolicyDecision(risk, requiresApproval, false, List.of()));
        when(resourceAccessService.check(any(), any())).thenReturn(new AbacDecision(true, "", null));
    }

    /** 迭代 7.6：节点从未上报 Native Guard 姿态（心跳缺失）→ R3+ 按 UNKNOWN fail-closed 拒绝。 */
    @Test
    void missingGuardPostureRejectsHighRiskWrite() {
        stubPolicy(RiskCode.R3, true);

        DispatchGate.Decision decision = gate.evaluate(requestWithPosture(null, null));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("NATIVE_GUARD_UNKNOWN");
    }

    /** 迭代 7.6：姿态新鲜但 CRITICAL（KYSEC/auditd/安全开关危险）→ 控制面先拒 R3+。 */
    @Test
    void criticalGuardPostureRejectsHighRiskWrite() {
        stubPolicy(RiskCode.R3, true);

        DispatchGate.Decision decision = gate.evaluate(requestWithPosture("CRITICAL", Instant.now()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("NATIVE_GUARD_CRITICAL");
    }

    /** 18.2：ABAC 资源策略被撤销（权限撤销）后，调用必须被拒，审批不能绕过。 */
    @Test
    void revokedAbacPermissionRejectsDispatch() {
        stubPolicy(RiskCode.R2, false);
        when(resourceAccessService.check(any(), any()))
                .thenReturn(new AbacDecision(false, "resource policy revoked", null));

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R2, false, "CAUTIOUS", List.of(), false, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("ABAC_DENIED");
    }

    /** 18.2：Run grant 收窄后不再包含目标工具 → 拒绝（AgentGrant 撤销向量）。 */
    @Test
    void grantRevocationExcludesTool() {
        stubPolicy(RiskCode.R2, false);

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R2, false, "CAUTIOUS", List.of("kylin.service.status"), false, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("GRANT_EXCLUDES_TOOL");
    }

    /** 18.2：只读 grant 下的写工具调用必须被拒。 */
    @Test
    void readOnlyGrantRejectsWriteTool() {
        stubPolicy(RiskCode.R2, false);

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R2, false, "CAUTIOUS", List.of(), true, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("GRANT_READ_ONLY");
    }

    /** 18.2：Run grant 限定了节点集合，越界节点必须被拒。 */
    @Test
    void grantNodeExclusionRejects() {
        stubPolicy(RiskCode.R2, false);

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R2, false, "CAUTIOUS", List.of(), false, List.of("node-2")));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("GRANT_EXCLUDES_NODE");
    }

    /** 18.2：R5 一律拒绝——即使策略标记为「需要审批」，审批也不能为 R5 开路。 */
    @Test
    void r5CannotBeBypassedByApproval() {
        stubPolicy(RiskCode.R5, true);

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R5, false, "CAUTIOUS", List.of(), false, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("R5_FORBIDDEN");
    }

    /** 18.2：PLAN 模式写操作在控制面 100% 拒绝（节点端由 advbench plan_mode_write 组覆盖）。 */
    @Test
    void planModeForbidsWriteOnControlPlane() {
        stubPolicy(RiskCode.R2, false);

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R2, false, "PLAN", List.of(), false, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("PLAN_MODE_FORBIDS_WRITE");
    }

    /** 18.2 阳性对照：策略引擎拒绝时，控制面不得放行。 */
    @Test
    void policyEngineDenialIsFinal() {
        when(policyEngine.evaluate(any(), any(), any()))
                .thenReturn(new PolicyDecision(RiskCode.R2, false, true, List.of()));

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R2, false, "CAUTIOUS", List.of(), false, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("POLICY_DENIED");
    }

    /** 18.2 阳性对照：R3+ CAUTIOUS 写操作必须进入人工审批，而不是直接放行。 */
    @Test
    void r3CautiousRequiresHumanApproval() {
        stubPolicy(RiskCode.R3, true);

        DispatchGate.Decision decision = gate.evaluate(
                request(RiskCode.R3, false, "CAUTIOUS", List.of(), false, List.of()));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.NEEDS_APPROVAL);
    }
}
