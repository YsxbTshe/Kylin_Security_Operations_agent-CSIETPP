package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.AgentRunWorker;
import com.superskidder.kylinops.backend.agentruntime.ApprovalService;
import com.superskidder.kylinops.backend.agentruntime.checkpoint.RunCheckpointService;
import com.superskidder.kylinops.backend.agentruntime.connector.ConnectorToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.context.ContextCompiler;
import com.superskidder.kylinops.backend.agentruntime.context.ContextSummarizer;
import com.superskidder.kylinops.backend.agentruntime.context.ContextWindowPolicy;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.memory.MemoryService;
import com.superskidder.kylinops.backend.agentruntime.memory.MemoryStore;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGateway;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGatewayResult;
import com.superskidder.kylinops.backend.agentruntime.model.RoutedModel;
import com.superskidder.kylinops.backend.agentruntime.model.SimpleModelRouter;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.security.DispatchGate;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ToolRegistry;
import com.superskidder.kylinops.backend.llm.LlmModelConfig;
import com.superskidder.kylinops.backend.llm.LlmToolCall;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AgentRunWorker 执行记录挂钩：工具执行终态落 execution_record（成功/失败状态正确），
 * run 成功收尾只提炼候选；红线——普通 COMPLETED run 不产生 verified_fact。
 */
class AgentRunWorkerMemoryHookTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private ModelGateway modelGateway;
    private NodeMcpGateway mcpGateway;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        modelGateway = mock(ModelGateway.class);
        mcpGateway = mock(NodeMcpGateway.class);
    }

    private AgentRunWorker worker() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        SimpleModelRouter router = mock(SimpleModelRouter.class);
        RoutedModel routed = mock(RoutedModel.class);
        LlmModelConfig config = mock(LlmModelConfig.class);
        when(config.providerType()).thenReturn("test-provider");
        when(config.model()).thenReturn("stub-model");
        when(routed.config()).thenReturn(config);
        when(routed.configDigest()).thenReturn("cfg-digest");
        when(routed.reasoningLevel()).thenReturn("OFF");
        when(routed.reason()).thenReturn("test-route");
        when(router.route(any(), any(), any(), any())).thenReturn(routed);

        ToolRegistry registry = mock(ToolRegistry.class);
        NodeTool tool = mock(NodeTool.class);
        when(tool.toolId()).thenReturn("kylin.service.status");
        when(tool.readOnly()).thenReturn(true);
        when(tool.alwaysRequireApproval()).thenReturn(false);
        when(registry.lookup("kylin.service.status")).thenReturn(Optional.of(tool));
        when(registry.allTools()).thenReturn(List.of());
        when(registry.catalogEntriesForGrant(any())).thenReturn(List.of());
        when(registry.catalogDigest()).thenReturn("catalog-digest");

        DispatchGate gate = mock(DispatchGate.class);
        DispatchGate.Decision allowed = mock(DispatchGate.Decision.class);
        when(allowed.outcome()).thenReturn(DispatchGate.Decision.Outcome.ALLOWED);
        when(gate.evaluate(any())).thenReturn(allowed);

        return new AgentRunWorker(true, 5, "R2", store,
                new ContextCompiler(AgentTestFixtures.MAPPER, metrics),
                new ContextWindowPolicy(32_000, 0.6),
                new ContextSummarizer(AgentTestFixtures.MAPPER, metrics),
                router, modelGateway, mcpGateway, registry, gate,
                new ApprovalService(jdbc, store, mock(ResourceAccessService.class)),
                mock(RuntimeToolDispatcher.class), mock(ConnectorToolDispatcher.class),
                mock(SubAgentLifecycleService.class),
                new MemoryService(new MemoryStore(jdbc), AgentTestFixtures.MAPPER),
                new RunCheckpointService(jdbc, AgentTestFixtures.MAPPER),
                AgentTestFixtures.MAPPER);
    }

    private String newQueuedRun() {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        return runId;
    }

    private ModelGatewayResult toolCallResult() {
        return new ModelGatewayResult("", List.of(new LlmToolCall(
                "call-1", "kylin.service.status", "{\"service\":\"nginx\"}")), 10, 5, 0, 0, 1, 1, 0);
    }

    private ModelGatewayResult finalResult() {
        return new ModelGatewayResult("done", List.of(), 10, 5, 0, 0, 1, 1, 0);
    }

    @Test
    void successfulToolExecutionIsRecordedAndRunCompletionYieldsOnlyCandidate() {
        when(mcpGateway.callTool(any(), any(), any(), any())).thenReturn(
                new NodeMcpGateway.ToolOutcome(null, Map.of("status", "SUCCEEDED"), Map.of(), 0));
        when(modelGateway.complete(any(), any())).thenReturn(toolCallResult()).thenReturn(finalResult());
        String runId = newQueuedRun();

        worker().execute(store.leaseSpecific(runId));

        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        Map<String, Object> record = jdbc.queryForMap(
                "select * from execution_record where run_id = ?", runId);
        assertThat(record.get("TOOL_ID")).isEqualTo("kylin.service.status");
        assertThat(record.get("SUCCESS")).isEqualTo(true);
        assertThat(record.get("OUTCOME_SUMMARY")).isEqualTo("SUCCEEDED");
        assertThat(record.get("TENANT_ID")).isEqualTo(AgentTestDatabase.TENANT_ID);

        // run 成功收尾提炼候选，但红线：不产生任何 verified_fact
        Integer candidates = jdbc.queryForObject(
                "select count(*) from memory_candidate where tenant_id = ?",
                Integer.class, AgentTestDatabase.TENANT_ID);
        assertThat(candidates).isEqualTo(1);
        assertThat(jdbc.queryForObject("select statement from memory_candidate", String.class))
                .contains(runId);
        Integer facts = jdbc.queryForObject("select count(*) from verified_fact", Integer.class);
        assertThat(facts).isZero();
    }

    @Test
    void failedToolExecutionIsRecordedWithoutRunCandidateOrFact() {
        when(mcpGateway.callTool(any(), any(), any(), any()))
                .thenThrow(new NodeMcpGateway.ToolCallException("NODE_TOOL_FAILED", "unit not found", false));
        when(modelGateway.complete(any(), any())).thenReturn(toolCallResult()).thenReturn(finalResult());
        String runId = newQueuedRun();

        worker().execute(store.leaseSpecific(runId));

        Map<String, Object> record = jdbc.queryForMap(
                "select * from execution_record where run_id = ?", runId);
        assertThat(record.get("TOOL_ID")).isEqualTo("kylin.service.status");
        assertThat(record.get("SUCCESS")).isEqualTo(false);
        assertThat(String.valueOf(record.get("OUTCOME_SUMMARY"))).contains("unit not found");

        // 含失败的 run 收尾不提炼 run 候选；本例只有一次执行也不触发连续成功候选
        Integer candidates = jdbc.queryForObject("select count(*) from memory_candidate", Integer.class);
        assertThat(candidates).isZero();
        Integer facts = jdbc.queryForObject("select count(*) from verified_fact", Integer.class);
        assertThat(facts).isZero();
    }
}
