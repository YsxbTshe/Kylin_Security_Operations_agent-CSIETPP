package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** RuntimeToolDispatcher 的 H2 端到端测试：招募成功路径 + 三道硬门禁 + 动态规格不落库。 */
class RuntimeToolDispatcherTest {

    private static final Map<String, Object> PARENT_GRANT = Map.of(
            "tools", List.of("kylin.service.status", "kylin.workspace.read"),
            "nodes", List.of("node-1"),
            "readOnly", false
    );
    private static final Map<String, Object> PARENT_BUDGET = Map.of(
            "maxModelInvocations", 10,
            "maxToolInvocations", 20,
            "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000,
            "maxWallClockSeconds", 600
    );
    private static final Map<String, Object> PARENT_USAGE = Map.of(
            "modelInvocations", 4,
            "toolInvocations", 8,
            "inputTokens", 2_000,
            "outputTokens", 1_000
    );

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private RuntimeToolDispatcher dispatcher;
    private RecruitAgentTool tool;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        // lease_owner 与 AgentTestFixtures.insertRun 保持一致，transitionToSubAgentWaiting 才能命中
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        HandoffService handoffService = new HandoffService(jdbc, AgentTestFixtures.MAPPER, 0.5);
        dispatcher = new RuntimeToolDispatcher(store, handoffService, AgentTestFixtures.MAPPER, new AgentRegistryService(jdbc, AgentTestFixtures.MAPPER));
        tool = new RecruitAgentTool(AgentTestFixtures.MAPPER);
    }

    @Test
    void recruitSuccessCreatesChildInstanceHandoffAndRun() throws Exception {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        RuntimeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(parentRun, tool,
                Map.of("goal", "triage disk pressure on node-1", "agent_key", "sense-collector"));

        assertThat(outcome.handoffId()).isNotBlank();
        assertThat(outcome.agentInstanceId()).isNotBlank();
        assertThat(outcome.childRunId()).isNotBlank();
        // 招募把父 run 转入 WAITING/SUB_AGENT：如实回报挂起，执行循环据此结束本轮。
        assertThat(outcome.runSuspended()).isTrue();
        assertThat(outcome.structuredOutput()).containsEntry("status", "OPEN");
        assertThat(outcome.renderedOutput()).containsEntry("type", "recruit-agent");

        // 子 agent_instance 挂在父 run 下（handoff 复合外键要求共享 run_id），depth+1
        AgentRuntimeStore.AgentInstanceView child =
                store.findAgentInstance(outcome.agentInstanceId()).orElseThrow();
        assertThat(child.runId()).isEqualTo(runId);
        assertThat(child.depth()).isEqualTo(1);
        assertThat(child.status()).isEqualTo("QUEUED");
        Map<String, Object> instanceRow = jdbc.queryForMap(
                "select parent_agent_instance_id, definition_snapshot_json from agent_instance where id = ?",
                outcome.agentInstanceId());
        assertThat(instanceRow.get("parent_agent_instance_id")).isEqualTo(rootInstanceId);
        assertThat(String.valueOf(instanceRow.get("definition_snapshot_json")))
                .contains("\"source\":\"dynamic\"")
                .contains(RecruitAgentTool.DEFAULT_DYNAMIC_INSTRUCTIONS);

        // handoff 落库且为 OPEN
        Map<String, Object> handoffRow = jdbc.queryForMap("select * from handoff where id = ?", outcome.handoffId());
        assertThat(handoffRow.get("status")).isEqualTo("OPEN");
        assertThat(handoffRow.get("run_id")).isEqualTo(runId);
        assertThat(handoffRow.get("parent_agent_instance_id")).isEqualTo(rootInstanceId);
        assertThat(handoffRow.get("child_agent_instance_id")).isEqualTo(outcome.agentInstanceId());
        assertThat(handoffRow.get("objective")).isEqualTo("triage disk pressure on node-1");

        // 子 agent_run：QUEUED、父 run 关联、root 指向子实例；grant 继承父级、预算按 0.5 衰减
        Map<String, Object> childRunRow = jdbc.queryForMap(
                "select * from agent_run where id = ?", outcome.childRunId());
        assertThat(childRunRow.get("status")).isEqualTo("QUEUED");
        assertThat(childRunRow.get("parent_run_id")).isEqualTo(runId);
        assertThat(childRunRow.get("root_agent_instance_id")).isEqualTo(outcome.agentInstanceId());
        Map<String, Object> childGrant = AgentTestFixtures.MAPPER.readValue(
                (String) childRunRow.get("grant_snapshot_json"), new TypeReference<>() {});
        assertThat(childGrant.get("tools")).isEqualTo(PARENT_GRANT.get("tools"));
        Map<String, Object> childBudget = AgentTestFixtures.MAPPER.readValue(
                (String) childRunRow.get("budget_json"), new TypeReference<>() {});
        assertThat(childBudget)
                .containsEntry("maxModelInvocations", 3)
                .containsEntry("maxToolInvocations", 6)
                .containsEntry("maxInputTokens", 4_000)
                .containsEntry("maxOutputTokens", 2_000)
                .containsEntry("maxWallClockSeconds", 300);

        // 父 run 置 WAITING/SUB_AGENT
        Map<String, Object> parentRow = jdbc.queryForMap("select * from agent_run where id = ?", runId);
        assertThat(parentRow.get("status")).isEqualTo("WAITING");
        assertThat(parentRow.get("waiting_reason")).isEqualTo("SUB_AGENT");
        assertThat(parentRow.get("lease_owner")).isNull();
    }

    /**
     * 招募必须声明一个已注册且启用的专家：写一个不存在的 key，招募当场失败，
     * 子 Agent 根本不会被创建（noSideEffects 断言这一点）。
     *
     * <p>拒绝信息里要列出可用专家——模型此前只能从工具描述里看到示例 key，猜错就反复
     * 失败；把可选项放进错误信息，模型下一轮能自己纠正。
     */
    @Test
    void recruitWithUnregisteredExpertIsRejectedAndListsAvailableOnes() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool,
                Map.of("goal", "do something", "agent_key", "no-such-expert")))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("SUB_AGENT_UNKNOWN_EXPERT");

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool,
                Map.of("goal", "do something", "agent_key", "no-such-expert")))
                .hasMessageContaining("sense-collector");

        assertThat(noSideEffects(runId)).isTrue();
    }

    /** 已停用的专家同样不能被招募——启停开关必须对下发链路真实有效。 */
    @Test
    void recruitWithDisabledExpertIsRejected() {
        jdbc.update("update app_agent_registry set enabled = false where agent_key = ?", "sense-collector");
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool,
                Map.of("goal", "do something", "agent_key", "sense-collector")))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("SUB_AGENT_UNKNOWN_EXPERT");

        assertThat(noSideEffects(runId)).isTrue();
    }

    @Test
    void dynamicSpecWithInstructionsLeavesNoDefinitionTrace() throws Exception {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("goal", "collect diagnostics");
        arguments.put("agent_key", "sense-collector");
        arguments.put("spec_instructions", "Triage disk usage and report back.");
        RuntimeToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(parentRun, tool, arguments);

        // 动态规格只进快照，不写 definition 表
        Integer definitions = jdbc.queryForObject("select count(*) from agent_definition", Integer.class);
        Integer versions = jdbc.queryForObject("select count(*) from agent_definition_version", Integer.class);
        assertThat(definitions).isZero();
        assertThat(versions).isZero();

        String snapshot = jdbc.queryForObject(
                "select definition_snapshot_json from agent_instance where id = ?",
                String.class, outcome.agentInstanceId());
        assertThat(snapshot).contains("Triage disk usage and report back.");
    }

    @Test
    void depthBeyondTwoIsRejected() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String deepInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 2, "RUNNING");
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, deepInstanceId, PARENT_GRANT, PARENT_BUDGET);

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool, Map.of("goal", "too deep", "agent_key", "sense-collector")))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("SUB_AGENT_DEPTH_EXCEEDED");

        Integer depth3 = jdbc.queryForObject(
                "select count(*) from agent_instance where run_id = ? and depth = 3", Integer.class, runId);
        assertThat(depth3).isZero();
        assertThat(noSideEffects(runId)).isTrue();
    }

    @Test
    void fifthActiveSubAgentIsRejected() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        for (int i = 0; i < 4; i++) {
            AgentTestFixtures.insertInstance(jdbc, runId, rootInstanceId, 1, i % 2 == 0 ? "QUEUED" : "RUNNING");
        }
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool, Map.of("goal", "fifth sub agent", "agent_key", "sense-collector")))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("SUB_AGENT_ACTIVE_LIMIT_EXCEEDED");

        assertThat(store.countSubAgents(runId, true)).isEqualTo(4);
        assertThat(noSideEffects(runId)).isTrue();
    }

    @Test
    void ninthTotalSubAgentIsRejected() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        for (int i = 0; i < 8; i++) {
            AgentTestFixtures.insertInstance(jdbc, runId, rootInstanceId, 1, "COMPLETED");
        }
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool, Map.of("goal", "ninth sub agent", "agent_key", "sense-collector")))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("SUB_AGENT_TOTAL_LIMIT_EXCEEDED");

        assertThat(noSideEffects(runId)).isTrue();
    }

    /**
     * 验收基线 18.3 绕过向量：recruit_agent 入参是封闭集（goal/spec_instructions/budget/output_schema），
     * 模型试图在参数里夹带未声明的 grant 提权字段必须被拒绝——子 Agent 权限只能衰减、不能通过输入放大。
     */
    @Test
    void undeclaredGrantFieldInRecruitArgumentsIsRejected() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool, Map.of(
                "goal", "escalate", "grant", Map.of("readOnly", false, "tools", List.of("*")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field");

        assertThat(noSideEffects(runId)).isTrue();
    }

    /**
     * 验收基线 18.3 绕过向量：用「先招募再让子 Agent 快速终态」的方式腾名额必须失败——
     * 总数上限按累计口径统计（COMPLETED/FAILED/CANCELLED 一律计入），终态不能释放配额。
     */
    @Test
    void terminalChildrenStillCountTowardTotalLimit() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        String[] terminalStatuses = {"COMPLETED", "FAILED", "CANCELLED"};
        for (int i = 0; i < 8; i++) {
            AgentTestFixtures.insertInstance(jdbc, runId, rootInstanceId, 1, terminalStatuses[i % 3]);
        }
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);
        assertThat(store.countSubAgents(runId, true)).isZero();

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool, Map.of("goal", "respawn after terminals", "agent_key", "sense-collector")))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("SUB_AGENT_TOTAL_LIMIT_EXCEEDED");

        assertThat(noSideEffects(runId)).isTrue();
    }

    @Test
    void excessiveBudgetIsRejectedWithoutSideEffects() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        LeasedAgentRun parentRun = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);

        assertThatThrownBy(() -> dispatcher.dispatch(parentRun, tool,
                Map.of("goal", "greedy", "agent_key", "sense-collector", "budget", Map.of("maxToolInvocations", 99))))
                .isInstanceOf(NodeMcpGateway.ToolCallException.class)
                .extracting(e -> ((NodeMcpGateway.ToolCallException) e).errorCode())
                .isEqualTo("SUB_AGENT_BUDGET_EXCEEDED");

        assertThat(noSideEffects(runId)).isTrue();
    }

    /** 拒绝路径不得留下 handoff / 子 run，父 run 也不得离开 RUNNING。 */
    private boolean noSideEffects(String runId) {
        Integer handoffs = jdbc.queryForObject("select count(*) from handoff where run_id = ?", Integer.class, runId);
        Integer childRuns = jdbc.queryForObject(
                "select count(*) from agent_run where parent_run_id = ?", Integer.class, runId);
        String parentStatus = jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId);
        return handoffs == 0 && childRuns == 0 && "RUNNING".equals(parentStatus);
    }
}
