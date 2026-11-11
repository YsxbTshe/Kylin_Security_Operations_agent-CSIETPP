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
 * Phase 6.2 Checkpoint：每轮模型调用前写入；终态删除；
 * 恢复时从 checkpoint 的 round_no 继续（已用轮次不重复计入）。
 */
class AgentRunCheckpointTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.status", "kylin.recruit.agent"),
            "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    /** 同时是 RuntimeTool 的工具（走 runtimeToolDispatcher 分支，可制造非终态退出）。 */
    private interface RecruitingRuntimeTool extends NodeTool, RuntimeTool {}

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private RunCheckpointService checkpoints;
    private ModelGateway modelGateway;
    private RuntimeToolDispatcher runtimeToolDispatcher;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        checkpoints = new RunCheckpointService(jdbc, AgentTestFixtures.MAPPER);
        modelGateway = mock(ModelGateway.class);
        runtimeToolDispatcher = mock(RuntimeToolDispatcher.class);
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
        NodeTool statusTool = mock(NodeTool.class);
        when(statusTool.toolId()).thenReturn("kylin.service.status");
        when(statusTool.readOnly()).thenReturn(true);
        when(registry.lookup("kylin.service.status")).thenReturn(Optional.of(statusTool));
        RecruitingRuntimeTool recruitTool = mock(RecruitingRuntimeTool.class);
        when(recruitTool.toolId()).thenReturn("kylin.recruit.agent");
        when(registry.lookup("kylin.recruit.agent")).thenReturn(Optional.of(recruitTool));
        when(registry.allTools()).thenReturn(List.of());
        when(registry.catalogEntriesForGrant(any())).thenReturn(List.of());
        when(registry.catalogDigest()).thenReturn("catalog-digest");

        DispatchGate gate = mock(DispatchGate.class);
        DispatchGate.Decision allowed = mock(DispatchGate.Decision.class);
        when(allowed.outcome()).thenReturn(DispatchGate.Decision.Outcome.ALLOWED);
        when(gate.evaluate(any())).thenReturn(allowed);

        NodeMcpGateway mcpGateway = mock(NodeMcpGateway.class);
        when(mcpGateway.callTool(any(), any(), any(), any())).thenReturn(
                new NodeMcpGateway.ToolOutcome(null, Map.of("status", "SUCCEEDED"), Map.of("rendered", true), 0));

        // 招募类 RUNTIME 工具会把父 run 挂起（suspended）——本类正是靠它制造非终态退出。
        when(runtimeToolDispatcher.dispatch(any(), any(), any())).thenReturn(
                RuntimeToolDispatcher.DispatchOutcome.suspended("handoff-1", "agent-child", "run-child",
                        Map.of("status", "OPEN"), Map.of("rendered", true)));

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new AgentRunWorker(true, 5, "R2", store,
                new ContextCompiler(AgentTestFixtures.MAPPER, metrics),
                new ContextWindowPolicy(32_000, 0.6),
                new ContextSummarizer(AgentTestFixtures.MAPPER, metrics),
                router, modelGateway, mcpGateway, registry, gate,
                new ApprovalService(jdbc, store, mock(ResourceAccessService.class)),
                runtimeToolDispatcher, mock(ConnectorToolDispatcher.class),
                mock(SubAgentLifecycleService.class),
                new MemoryService(new MemoryStore(jdbc), AgentTestFixtures.MAPPER),
                checkpoints, AgentTestFixtures.MAPPER);
    }

    private LeasedAgentRun newQueuedRun() {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        return store.leaseSpecific(runId);
    }

    private ModelGatewayResult toolCallResult(String callId, String toolName) {
        return new ModelGatewayResult("", List.of(new LlmToolCall(callId, toolName, "{\"service\":\"nginx\"}")),
                10, 5, 0, 0, 1, 1, 0);
    }

    private ModelGatewayResult finalResult() {
        return new ModelGatewayResult("done", List.of(), 10, 5, 0, 0, 1, 1, 0);
    }

    @Test
    void checkpointRoundTripUpsertsSameRowAndDeleteRemovesIt() {
        LeasedAgentRun run = newQueuedRun();
        checkpoints.save(run, 2, "tool-abc");

        RunCheckpointService.RunCheckpoint loaded = checkpoints.load(run.id()).orElseThrow();
        assertThat(loaded.roundNo()).isEqualTo(2);
        assertThat(loaded.lastToolInvocationId()).isEqualTo("tool-abc");
        assertThat(loaded.agentInstanceId()).isEqualTo(run.rootAgentInstanceId());
        assertThat(loaded.checkpointJson()).contains("\"roundNo\":2").contains("tool-abc");

        checkpoints.save(run, 5, null);
        assertThat(checkpoints.load(run.id()).orElseThrow().roundNo()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from agent_run_checkpoint where run_id = ?",
                Integer.class, run.id())).isEqualTo(1);

        assertThat(checkpoints.delete(run.id())).isEqualTo(1);
        assertThat(checkpoints.load(run.id())).isEmpty();
    }

    @Test
    void checkpointIsWrittenBeforeModelCallAndDeletedOnTerminal() {
        when(modelGateway.complete(any(), any()))
                .thenReturn(toolCallResult("call-1", "kylin.recruit.agent"))
                .thenReturn(finalResult());
        AgentRunWorker worker = worker();
        LeasedAgentRun run = newQueuedRun();

        // 第 1 轮：工具走 RUNTIME 分支 → 非终态退出，检查点留存（模拟等待/重启前的最后写入）
        worker.execute(run);
        RunCheckpointService.RunCheckpoint checkpoint = checkpoints.load(run.id()).orElseThrow();
        assertThat(checkpoint.roundNo()).isZero();
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, run.id()))
                .isNotIn("COMPLETED", "FAILED", "CANCELLED");

        // 第 2 次执行（唤醒/重启）：重建历史并从检查点继续；终态后检查点被删除
        worker.execute(run);
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, run.id()))
                .isEqualTo("COMPLETED");
        assertThat(checkpoints.load(run.id())).isEmpty();
        verify(modelGateway, times(2)).complete(any(), any());
    }

    @Test
    void restoredCheckpointRoundIsNotRecountedIntoTheRoundBudget() {
        AgentRunWorker worker = worker();
        LeasedAgentRun run = newQueuedRun();
        // 模拟上一进程已用完全部 8 轮：检查点 round_no = MAX
        checkpoints.save(run, 8, "tool-last");
        when(modelGateway.complete(any(), any())).thenReturn(finalResult());

        worker.execute(run);

        verify(modelGateway, never()).complete(any(), any());
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, run.id()))
                .isEqualTo("COMPLETED");
        assertThat(checkpoints.load(run.id())).isEmpty();
    }

    @Test
    void resumeFromMidCheckpointExecutesOnlyRemainingRounds() {
        AgentRunWorker worker = worker();
        LeasedAgentRun run = newQueuedRun();
        checkpoints.save(run, 7, null);
        // 若从头执行，恒定返回工具调用会跑满 8 轮；从检查点恢复只应剩第 8 轮
        when(modelGateway.complete(any(), any()))
                .thenReturn(toolCallResult("call-9", "kylin.service.status"));

        worker.execute(run);

        verify(modelGateway, times(1)).complete(any(), any());
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, run.id()))
                .isEqualTo("COMPLETED");
    }
}
