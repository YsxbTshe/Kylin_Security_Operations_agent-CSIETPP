package com.superskidder.kylinops.backend.agentruntime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.AbacDecision;
import com.superskidder.kylinops.backend.abac.AbacPolicyEngine;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillLoadTool;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillSearchTool;
import com.superskidder.kylinops.backend.agentruntime.vfs.VfsReadTool;
import com.superskidder.kylinops.backend.policy.IntentAlignmentGuard;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import com.superskidder.kylinops.backend.policy.PolicyToolCatalog;
import com.superskidder.kylinops.backend.policy.RiskCode;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 新增的三个只读 RUNTIME 工具确实过 DispatchGate，而且是以 R0 过的。
 *
 * <p>这条测试锁的是一个容易被忽略的耦合：{@code PolicyToolCatalog.describeTool} 对
 * **目录外**的工具 fail-closed 到 R3 + requiresApproval（那是给端侧上报的未知插件工具准备的）。
 * 如果控制面自己的 RUNTIME 工具漏了目录声明，一次纯读检索会被判 R3——既要求 Native Guard
 * 姿态新鲜，又要转人工审批，而 RUNTIME 工具在审批分支上会去写 tool_invocation
 * （外键指向 tool_package_version，运行时工具没有这行），表现为整条 run 失败。
 * 所以"读工具被判 R0"不是宽松，而是这三个工具能用的前提；漏声明会在这里立刻变红。
 */
class RuntimeReadOnlyToolGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PolicyToolCatalog toolCatalog = new PolicyToolCatalog(null, MAPPER);
    private final ResourceAccessService resourceAccessService = mock(ResourceAccessService.class);
    // agentKey 为空的请求不会触碰注册表封套判定（见 DispatchGate 的 agentKey 注释）。
    private final DispatchGate gate = new DispatchGate(
            new PolicyEngine(MAPPER, toolCatalog, null),
            new IntentAlignmentGuard(toolCatalog),
            new AbacPolicyEngine(MAPPER),
            resourceAccessService,
            mock(AgentRegistryService.class));

    @Test
    void readOnlyRuntimeToolsPassTheGateAsR0WithoutApproval() {
        when(resourceAccessService.check(any(), any())).thenReturn(new AbacDecision(true, "", null));

        for (String toolId : List.of(SkillSearchTool.TOOL_ID, SkillLoadTool.TOOL_ID, VfsReadTool.TOOL_ID)) {
            DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                    "org-alpha", "run-1", "user-alpha", List.of("security_operator"),
                    "查一下磁盘排查手册", "node-1", toolId, "{\"skill_id\":\"kylin.ops.disk-triage\"}",
                    /* readOnly */ true, /* alwaysRequireApproval */ false, /* riskFloor */ "R0",
                    "CAUTIOUS", List.of(), /* grantReadOnly */ true, List.of(),
                    // 姿态陈旧：R0 不该受 Native Guard 新鲜度门约束，这里顺带钉住这一点。
                    "AVAILABLE", Instant.now().minusSeconds(86_400), 5, "R2"));

            assertThat(decision.outcome())
                    .as("%s dispatch outcome", toolId)
                    .isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);
            assertThat(decision.riskCode()).as("%s risk", toolId).isEqualTo(RiskCode.R0);
            assertThat(decision.autoApproved()).as("%s autoApproved", toolId).isFalse();
        }
    }

    @Test
    void readOnlyRuntimeToolsSurviveAReadOnlyGrantAndPlanMode() {
        when(resourceAccessService.check(any(), any())).thenReturn(new AbacDecision(true, "", null));

        // PLAN 模式只读：R2 及以上被禁；这三个工具是 R0，计划阶段也能用来查手册。
        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                "org-alpha", "run-1", "user-alpha", List.of("security_operator"),
                "先做只读诊断", "node-1", VfsReadTool.TOOL_ID,
                "{\"uri\":\"skill://kylin.ops.disk-triage/SKILL.md\"}",
                true, false, "R0", "PLAN",
                List.of(SkillSearchTool.TOOL_ID, SkillLoadTool.TOOL_ID, VfsReadTool.TOOL_ID),
                true, List.of("node-1"),
                "AVAILABLE", Instant.now(), 5, "R2"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.ALLOWED);
        assertThat(decision.riskCode()).isEqualTo(RiskCode.R0);
    }

    @Test
    void grantExclusionStillBlocksThem() {
        when(resourceAccessService.check(any(), any())).thenReturn(new AbacDecision(true, "", null));

        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                "org-alpha", "run-1", "user-alpha", List.of("security_operator"),
                "查一下磁盘排查手册", "node-1", SkillLoadTool.TOOL_ID, "{}",
                true, false, "R0", "CAUTIOUS",
                List.of("kylin.service.status"), true, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("GRANT_EXCLUDES_TOOL");
    }

    @Test
    void abacDenialStillBlocksThem() {
        when(resourceAccessService.check(any(), any()))
                .thenReturn(new AbacDecision(false, "no matching policy", null));

        DispatchGate.Decision decision = gate.evaluate(new DispatchGate.DispatchRequest(
                "org-alpha", "run-1", "user-viewer", List.of("viewer"),
                "查一下磁盘排查手册", "node-1", SkillSearchTool.TOOL_ID, "{}",
                true, false, "R0", "CAUTIOUS", List.of(), true, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2"));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(decision.rejectionCode()).isEqualTo("ABAC_DENIED");
    }
}
