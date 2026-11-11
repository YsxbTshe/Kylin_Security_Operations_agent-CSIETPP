package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolExecutor;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecruitAgentToolTest {

    private final RecruitAgentTool tool = new RecruitAgentTool(new ObjectMapper());

    @Test
    void metadataMatchesContract() {
        assertThat(tool.toolId()).isEqualTo("kylin.runtime.recruit_agent");
        assertThat(tool.readOnly()).isFalse();
        assertThat(tool.riskFloor()).isEqualTo("R2");
        assertThat(tool.alwaysRequireApproval()).isFalse();
        assertThat(tool.idempotency()).isEqualTo(ToolIdempotency.NON_RETRYABLE);
        assertThat(tool.executor()).isEqualTo(ToolExecutor.RUNTIME);
        assertThat(tool.llmTool()).isNotNull();
    }

    @Test
    void goalAndAgentKeyAreTheMinimalValidArguments() {
        Map<String, Object> validated = tool.validateArguments(
                Map.of("goal", "inspect disk usage", "agent_key", "sense-collector"));
        assertThat(validated.get("goal")).isEqualTo("inspect disk usage");
        assertThat(validated.get("agent_key")).isEqualTo("sense-collector");
        assertThat(validated).doesNotContainKeys("spec_instructions", "budget", "output_schema");
    }

    /**
     * agent_key 必填：省略它曾经等于「这个子 Agent 没有身份、不受专家封套约束」——
     * 把是否接受约束交给被约束方自己决定。现在省略即拒绝招募。
     */
    @Test
    void goalWithoutAgentKeyIsRejected() {
        assertThatThrownBy(() -> tool.validateArguments(Map.of("goal", "inspect disk usage")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent_key");
    }

    @Test
    void missingGoalIsRejected() {
        assertThatThrownBy(() -> tool.validateArguments(Map.of("budget", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("goal");
    }

    @Test
    void blankGoalIsRejected() {
        assertThatThrownBy(() -> tool.validateArguments(Map.of("goal", "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> tool.validateArguments(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedGoalIsRejected() {
        String goal = "x".repeat(4_001);
        assertThatThrownBy(() -> tool.validateArguments(Map.of("goal", goal)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedFieldIsRejected() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("goal", "do something");
        arguments.put("grant", Map.of("tools", java.util.List.of()));
        assertThatThrownBy(() -> tool.validateArguments(arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grant");
    }

    @Test
    void nonStringGoalIsRejected() {
        assertThatThrownBy(() -> tool.validateArguments(Map.of("goal", 42)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonObjectBudgetIsRejected() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("goal", "do something");
        arguments.put("budget", "not-an-object");
        assertThatThrownBy(() -> tool.validateArguments(arguments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void fullArgumentsRoundTripIntoRequest() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("goal", "collect memory diagnostics");
        arguments.put("spec_instructions", "prefer read-only tools");
        arguments.put("budget", Map.of("maxToolInvocations", 3));
        arguments.put("output_schema", Map.of("type", "object"));
        arguments.put("agent_key", "sense-collector");

        RecruitAgentTool.RecruitRequest request = tool.requestFrom(arguments);
        assertThat(request.goal()).isEqualTo("collect memory diagnostics");
        assertThat(request.specInstructions()).isEqualTo("prefer read-only tools");
        assertThat(request.budget()).containsEntry("maxToolInvocations", 3);
        assertThat(request.outputSchema()).containsEntry("type", "object");
        assertThat(request.agentKey()).isEqualTo("sense-collector");
    }

    /** 声明专家身份：子 Agent 的每次工具调用此后都要过该专家的注册封套。 */
    @Test
    void agentKeyIsNormalisedIntoRequest() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("goal", "block the attacker source ip");
        arguments.put("agent_key", "  incident-responder  ");

        assertThat(tool.requestFrom(arguments).agentKey()).isEqualTo("incident-responder");
    }

    @Test
    void blankOrOversizedAgentKeyIsRejected() {
        Map<String, Object> blank = new HashMap<>();
        blank.put("goal", "do something");
        blank.put("agent_key", "   ");
        assertThatThrownBy(() -> tool.validateArguments(blank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent_key");

        Map<String, Object> oversized = new HashMap<>();
        oversized.put("goal", "do something");
        oversized.put("agent_key", "x".repeat(65));
        assertThatThrownBy(() -> tool.validateArguments(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent_key");
    }

    @Test
    void validateOutputExtractsIdentifiers() {
        Map<String, Object> output = tool.validateOutput(
                "{\"handoffId\":\"handoff-1\",\"agentInstanceId\":\"agent-1\",\"status\":\"OPEN\"}");
        assertThat(output.get("handoffId")).isEqualTo("handoff-1");
        assertThat(output.get("agentInstanceId")).isEqualTo("agent-1");
        assertThat(output.get("status")).isEqualTo("OPEN");
    }
}
