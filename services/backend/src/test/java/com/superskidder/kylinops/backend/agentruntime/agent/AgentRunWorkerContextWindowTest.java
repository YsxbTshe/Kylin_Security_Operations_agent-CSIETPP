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
import com.superskidder.kylinops.backend.agentruntime.model.ModelGatewayRequest;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6.1 上下文窗口链路：消息落库重建、超预算压缩最旧轮次、stable 段永驻最前。
 */
class AgentRunWorkerContextWindowTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);
    private static final Map<String, Object> TOOL_OUTPUT = Map.of(
            "status", "SUCCEEDED", "service", "nginx", "activeCount", 3);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private SimpleMeterRegistry metrics;
    private ContextSummarizer summarizer;
    private ModelGateway modelGateway;
    private NodeMcpGateway mcpGateway;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        metrics = new SimpleMeterRegistry();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        summarizer = new ContextSummarizer(AgentTestFixtures.MAPPER, metrics);
        modelGateway = mock(ModelGateway.class);
        mcpGateway = mock(NodeMcpGateway.class);
    }

    private AgentRunWorker worker(ContextWindowPolicy policy) {
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

        when(mcpGateway.callTool(any(), any(), any(), any())).thenReturn(
                new NodeMcpGateway.ToolOutcome(null, TOOL_OUTPUT, Map.of("rendered", true), 0));

        return new AgentRunWorker(true, 5, "R2", store,
                new ContextCompiler(AgentTestFixtures.MAPPER, metrics), policy, summarizer,
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

    private ModelGatewayResult toolCallResult(String callId) {
        return new ModelGatewayResult("", List.of(new LlmToolCall(
                callId, "kylin.service.status", "{\"service\":\"nginx\"}")), 10, 5, 0, 0, 1, 1, 0);
    }

    private ModelGatewayResult finalResult() {
        return new ModelGatewayResult("done", List.of(), 10, 5, 0, 0, 1, 1, 0);
    }

    @Test
    void overBudgetCompressesOldestRoundsInjectsSummaryAndDropsOriginalsFromPrompt() {
        // 窗口小到 stable+dynamic 预留后仅容得下少量消息 → 每轮最旧消息被压缩
        AgentRunWorker worker = worker(new ContextWindowPolicy(320, 0.9));
        when(modelGateway.complete(any(), any()))
                .thenReturn(toolCallResult("call-1"))
                .thenReturn(toolCallResult("call-2"))
                .thenReturn(toolCallResult("call-3"))
                .thenReturn(finalResult());
        String runId = newQueuedRun();

        worker.execute(store.leaseSpecific(runId));

        ArgumentCaptor<ModelGatewayRequest> captor = ArgumentCaptor.forClass(ModelGatewayRequest.class);
        verify(modelGateway, times(4)).complete(any(), captor.capture());
        ModelGatewayRequest last = captor.getAllValues().get(3);

        // stable 段永远最前且不被裁
        assertThat(last.allMessages()).isNotEmpty();
        assertThat(String.valueOf(last.allMessages().get(0).get("content")))
                .contains("Kylin security operations Task Agent");
        // 摘要段注入：含工具名与结果状态；原始消息全部不再进 prompt
        List<Map<String, Object>> extra = last.extraMessages();
        assertThat(extra).hasSize(1);
        assertThat(extra.get(0).get("role")).isEqualTo("system");
        String summary = String.valueOf(extra.get(0).get("content"));
        assertThat(summary).contains("kylin.service.status").contains("SUCCEEDED");
        assertThat(extra.toString()).doesNotContain("call-1").doesNotContain("call-2").doesNotContain("call-3");

        // 落库形态：原始消息标记 compressed，摘要帧存在，run 正常完成
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select count(*) from context_frame where run_id = ? and frame_kind = 'MESSAGE' and compressed = true",
                Integer.class, runId)).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "select count(*) from context_frame where run_id = ? and frame_kind = 'SUMMARY'",
                Integer.class, runId)).isEqualTo(3);
        assertThat(store.latestSummaryText(runId)).contains("kylin.service.status");
        // 压缩指标：次数与节省 token 数
        assertThat(summarizer.compressionCount()).isEqualTo(3.0);
        assertThat(summarizer.tokensSaved()).isPositive();
    }

    @Test
    void persistedMessagesAreRebuiltIntoPromptOnWakeOrRestart() {
        AgentRunWorker worker = worker(new ContextWindowPolicy(32_000, 0.6));
        when(modelGateway.complete(any(), any())).thenReturn(finalResult());
        String runId = newQueuedRun();
        LeasedAgentRun leased = store.leaseSpecific(runId);
        // 模拟上一进程已落库的两轮消息（进程重启后仅存库内副本）
        Map<String, Object> assistant = Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("id", "call-0", "type", "function",
                        "function", Map.of("name", "kylin.service.status", "arguments", "{}"))));
        Map<String, Object> tool = Map.of("role", "tool", "tool_call_id", "call-0",
                "name", "kylin.service.status", "content", "{\"status\":\"SUCCEEDED\"}");
        store.appendRunMessage(leased, 1, assistant);
        store.appendRunMessage(leased, 2, tool);

        worker.execute(leased);

        ArgumentCaptor<ModelGatewayRequest> captor = ArgumentCaptor.forClass(ModelGatewayRequest.class);
        verify(modelGateway).complete(any(), captor.capture());
        List<Map<String, Object>> extra = captor.getValue().extraMessages();
        assertThat(extra).hasSize(2);
        assertThat(extra.get(0)).isEqualTo(assistant);
        assertThat(extra.get(1)).isEqualTo(tool);
        // 未触发压缩
        assertThat(summarizer.compressionCount()).isZero();
    }
}
