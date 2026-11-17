package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
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
 * RUNTIME 工具执行后的续跑判据。
 *
 * <p>此前执行循环在 RUNTIME 分支 dispatch 成功后**无条件**把 run 当作"等待中"并结束本轮，
 * 但只有招募子 Agent 这类工具才真的会 {@code transitionToWaiting}；knowledge_search 是同步的，
 * 结果当场就有。结果是 run 既不在 WAITING 也不推进，只能干等下一次轮询/租约超时——
 * 现场表现为"查知识库静默停顿约 60 秒"。
 */
class RuntimeToolContinuationTest {

    private static final String KNOWLEDGE_TOOL = "kylin.runtime.knowledge_search";
    private static final String RECRUIT_TOOL = "kylin.runtime.recruit_agent";
    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of(KNOWLEDGE_TOOL, RECRUIT_TOOL), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    /** 与生产同形的 RUNTIME 工具（NodeTool + RuntimeTool 标记）。 */
    private interface MockRuntimeTool extends NodeTool, RuntimeTool {}

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private ModelGateway modelGateway;
    private RuntimeToolDispatcher runtimeToolDispatcher;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        modelGateway = mock(ModelGateway.class);
        runtimeToolDispatcher = mock(RuntimeToolDispatcher.class);
    }

    /** 同步 RUNTIME 工具（知识检索）：run 不进等待态，本次执行内直接跑完下一轮并收敛。 */
    @Test
    void synchronousRuntimeToolContinuesToTheNextRoundWithoutWaiting() {
        when(runtimeToolDispatcher.dispatch(any(), any(), any())).thenReturn(
                RuntimeToolDispatcher.DispatchOutcome.completed(
                        Map.of("available", true, "resultCount", 1), Map.of("type", "knowledge-search")));
        when(modelGateway.complete(any(), any()))
                .thenReturn(toolCallResult("call-1", KNOWLEDGE_TOOL))
                .thenReturn(finalResult());
        LeasedAgentRun run = newQueuedRun();

        worker().execute(run);

        assertThat(status(run)).isEqualTo("COMPLETED");
        assertThat(waitingReason(run)).isNull();
        // 一次 execute 里跑满了"工具轮 + 收敛轮"，没有停在等待态白等下一次轮询。
        verify(modelGateway, times(2)).complete(any(), any());
    }

    /** 对照：真正会挂起的 RUNTIME 工具（招募子 Agent）仍然结束本轮，等子 run 唤醒。 */
    @Test
    void suspendingRuntimeToolStillParksTheRun() {
        when(runtimeToolDispatcher.dispatch(any(), any(), any())).thenReturn(
                RuntimeToolDispatcher.DispatchOutcome.suspended("handoff-1", "agent-child", "run-child",
                        Map.of("status", "OPEN"), Map.of("type", "recruit-agent")));
        when(modelGateway.complete(any(), any()))
                .thenReturn(toolCallResult("call-1", RECRUIT_TOOL))
                .thenReturn(finalResult());
        LeasedAgentRun run = newQueuedRun();

        worker().execute(run);

        assertThat(status(run)).isNotIn("COMPLETED", "FAILED", "CANCELLED");
        verify(modelGateway, times(1)).complete(any(), any());
    }

    private String status(LeasedAgentRun run) {
        return jdbc.queryForObject("select status from agent_run where id = ?", String.class, run.id());
    }

    private String waitingReason(LeasedAgentRun run) {
        return jdbc.queryForObject("select waiting_reason from agent_run where id = ?", String.class, run.id());
    }

    private LeasedAgentRun newQueuedRun() {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        return store.leaseSpecific(runId);
    }

    private ModelGatewayResult toolCallResult(String callId, String toolName) {
        return new ModelGatewayResult("", List.of(new LlmToolCall(callId, toolName, "{\"query\":\"重启流程\"}")),
                10, 5, 0, 0, 1, 1, 0);
    }

    private ModelGatewayResult finalResult() {
        return new ModelGatewayResult("done", List.of(), 10, 5, 0, 0, 1, 1, 0);
    }

    private AgentRunWorker worker() {
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
        registry(registry, KNOWLEDGE_TOOL);
        registry(registry, RECRUIT_TOOL);
        when(registry.allTools()).thenReturn(List.of());
        when(registry.catalogEntriesForGrant(any())).thenReturn(List.of());
        when(registry.catalogDigest()).thenReturn("catalog-digest");

        DispatchGate gate = mock(DispatchGate.class);
        DispatchGate.Decision allowed = mock(DispatchGate.Decision.class);
        when(allowed.outcome()).thenReturn(DispatchGate.Decision.Outcome.ALLOWED);
        when(gate.evaluate(any())).thenReturn(allowed);

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new AgentRunWorker(true, 5, "R2", store,
                new ContextCompiler(AgentTestFixtures.MAPPER, metrics),
                new ContextWindowPolicy(32_000, 0.6),
                new ContextSummarizer(AgentTestFixtures.MAPPER, metrics),
                router, modelGateway, mock(NodeMcpGateway.class), registry, gate,
                new ApprovalService(jdbc, store, mock(ResourceAccessService.class)),
                runtimeToolDispatcher, mock(ConnectorToolDispatcher.class),
                mock(SubAgentLifecycleService.class),
                new MemoryService(new MemoryStore(jdbc), AgentTestFixtures.MAPPER),
                new RunCheckpointService(jdbc, AgentTestFixtures.MAPPER), AgentTestFixtures.MAPPER);
    }

    private void registry(ToolRegistry registry, String toolId) {
        MockRuntimeTool tool = mock(MockRuntimeTool.class);
        when(tool.toolId()).thenReturn(toolId);
        when(tool.readOnly()).thenReturn(true);
        when(registry.lookup(toolId)).thenReturn(Optional.of(tool));
    }
}
