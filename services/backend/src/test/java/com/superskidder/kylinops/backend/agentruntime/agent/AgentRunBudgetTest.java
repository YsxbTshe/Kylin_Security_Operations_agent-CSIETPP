package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * 迭代 7.6：预算五元组真实执行（ADR-001 §2.9）。
 * 此前 validateBudget 只做「预算 ≥ 1」的存在性校验，轮次/调用/墙钟/停滞/写次数从不扣减，
 * MAX_AGENTIC_ROUNDS=8 是唯一硬上限。本类验收：
 *   - 模型调用预算耗尽 → 轮首即停（BUDGET_EXCEEDED），不再触发模型调用；
 *   - 写预算耗尽 → 写调用不执行、run 以 WRITE_BUDGET_EXCEEDED 终止；
 *   - 连续停滞轮（证据指纹无新内容）触顶 → STAGNATED 终止。
 */
class AgentRunBudgetTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private ModelGateway modelGateway;
    private NodeMcpGateway mcpGateway;
    private NodeTool tool;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        modelGateway = mock(ModelGateway.class);
        mcpGateway = mock(NodeMcpGateway.class);
    }

    private AgentRunWorker worker(boolean toolReadOnly) {
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
        tool = mock(NodeTool.class);
        when(tool.toolId()).thenReturn("kylin.service.status");
        when(tool.readOnly()).thenReturn(toolReadOnly);
        when(tool.alwaysRequireApproval()).thenReturn(false);
        when(registry.lookup("kylin.service.status")).thenReturn(Optional.of(tool));
        when(registry.allTools()).thenReturn(List.of());
        when(registry.catalogEntriesForGrant(any())).thenReturn(List.of());
        when(registry.catalogDigest()).thenReturn("catalog-digest");

        DispatchGate gate = mock(DispatchGate.class);
        DispatchGate.Decision allowed = mock(DispatchGate.Decision.class);
        when(allowed.outcome()).thenReturn(DispatchGate.Decision.Outcome.ALLOWED);
        when(allowed.autoApproved()).thenReturn(false);
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

    private String newRun(Map<String, Object> budget, Map<String, Object> usage) {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, budget, usage);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ?, lease_owner = null where id = ?",
                instanceId, runId);
        return runId;
    }

    private Map<String, Object> budget(long maxModel, long maxTool, long maxWrite, long maxStagnant) {
        return Map.of("maxModelInvocations", maxModel, "maxToolInvocations", maxTool,
                "maxInputTokens", 10_000, "maxOutputTokens", 5_000, "maxWallClockSeconds", 600,
                "maxWriteActions", maxWrite, "maxStagnantRounds", maxStagnant);
    }

    private ModelGatewayResult statusToolCall() {
        return new ModelGatewayResult("", List.of(new LlmToolCall(
                "call-1", "kylin.service.status", "{\"service\":\"nginx\"}")), 10, 5, 0, 0, 1, 1, 0);
    }

    /** 模型调用预算耗尽：轮首即停，不再触发任何模型调用；stopReason 落 RUN_FAILED。 */
    @Test
    void exhaustedModelInvocationBudgetStopsBeforeModelCall() {
        String runId = newRun(budget(3, 20, 10, 3),
                Map.of("modelInvocations", 3, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0));

        worker(true).execute(store.leaseSpecific(runId));

        verify(modelGateway, never()).complete(any(), any());
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select final_result_json from agent_run where id = ?",
                String.class, runId)).contains("BUDGET_EXCEEDED");
    }

    /** 写预算耗尽：写调用不执行（零 dispatch），run 以 WRITE_BUDGET_EXCEEDED 终止。 */
    @Test
    void exhaustedWriteBudgetBlocksWriteAndStopsRun() {
        when(modelGateway.complete(any(), any())).thenReturn(statusToolCall());
        String runId = newRun(budget(10, 20, 2, 3),
                Map.of("modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0,
                        "outputTokens", 0, "writeActions", 2));

        worker(false).execute(store.leaseSpecific(runId));

        verify(mcpGateway, never()).callTool(any(), any(), any(), any());
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select final_result_json from agent_run where id = ?",
                String.class, runId)).contains("WRITE_BUDGET_EXCEEDED");
        // 拒绝执行的写调用要把预算错误回填给模型消息流（审计可见）。
        assertThat(jdbc.queryForObject("""
                select count(*) from context_frame
                where run_id = ? and frame_kind = 'MESSAGE' and message_role = 'tool'
                  and segment_manifest_json like '%write_budget_exceeded%'
                """, Integer.class, runId)).isEqualTo(1);
    }

    /** 停滞：连续 N 轮证据指纹无新内容（同工具同参同结果）即 STAGNATED 终止。 */
    @Test
    void stagnantRoundsBeyondLimitStopRun() {
        when(modelGateway.complete(any(), any())).thenReturn(statusToolCall());
        when(mcpGateway.callTool(any(), any(), any(), any())).thenReturn(
                new NodeMcpGateway.ToolOutcome(null, Map.of("status", "SUCCEEDED"), Map.of(), 0));
        String runId = newRun(budget(10, 20, 10, 2),
                Map.of("modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0));

        worker(true).execute(store.leaseSpecific(runId));

        // 第 1 轮产生新指纹；第 2、3 轮完全重复 → 连续 2 轮停滞触顶。
        verify(modelGateway, times(3)).complete(any(), any());
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select final_result_json from agent_run where id = ?",
                String.class, runId)).contains("STAGNATED");
    }
}
