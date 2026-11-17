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
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
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
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.agentruntime.security.DispatchGate;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ToolRegistry;
import com.superskidder.kylinops.backend.crypto.Sm3;
import com.superskidder.kylinops.backend.llm.LlmModelConfig;
import com.superskidder.kylinops.backend.llm.LlmToolCall;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6.2 恢复不重放：已完成的非幂等工具（AT_MOST_ONCE/NON_RETRYABLE）
 * 按 run+tool+参数摘要命中 SUCCEEDED 调用后直接复用结果，不再下发。
 */
class AgentRunReplayGuardTest {

    private static final String TOOL_ID = "kylin.service.stop";
    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of(TOOL_ID), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);
    private static final Map<String, Object> ARGUMENTS = Map.of("service", "nginx");

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
        seedToolPackage();
    }

    private void seedToolPackage() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into tool_package (id, package_key, title, enabled, created_at, updated_at)
                values ('tp-kylin-service', 'kylin-service', 'Kylin Service Tools', true, ?, ?)
                """, now, now);
        jdbc.update("""
                insert into tool_package_version (
                    id, package_id, version, manifest_json, manifest_digest, schema_digest,
                    implementation_digest, signature_algorithm, signing_key_id, signature_der,
                    status, created_at
                ) values ('tpv-kylin-service-1', 'tp-kylin-service', '1.0.0', '{}', 'digest-manifest',
                    'digest-schema', 'digest-impl', 'SM2', 'key-1', 'sig', 'APPROVED', ?)
                """, now);
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
        NodeTool stopTool = mock(NodeTool.class);
        when(stopTool.toolId()).thenReturn(TOOL_ID);
        when(stopTool.readOnly()).thenReturn(false);
        when(stopTool.idempotency()).thenReturn(ToolIdempotency.AT_MOST_ONCE);
        when(stopTool.validateArguments(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(registry.lookup(TOOL_ID)).thenReturn(Optional.of(stopTool));
        when(registry.allTools()).thenReturn(List.of());
        when(registry.catalogEntriesForGrant(any())).thenReturn(List.of());
        when(registry.catalogDigest()).thenReturn("catalog-digest");

        DispatchGate gate = mock(DispatchGate.class);
        DispatchGate.Decision allowed = mock(DispatchGate.Decision.class);
        when(allowed.outcome()).thenReturn(DispatchGate.Decision.Outcome.ALLOWED);
        when(gate.evaluate(any())).thenReturn(allowed);

        when(mcpGateway.callTool(any(), any(), any(), any())).thenReturn(
                new NodeMcpGateway.ToolOutcome(null, Map.of("stopped", true), Map.of("rendered", true), 0));

        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        return new AgentRunWorker(true, 5, "R2", store,
                new ContextCompiler(AgentTestFixtures.MAPPER, metrics),
                new ContextWindowPolicy(32_000, 0.6),
                new ContextSummarizer(AgentTestFixtures.MAPPER, metrics),
                router, modelGateway, mcpGateway, registry, gate,
                new ApprovalService(jdbc, store, mock(ResourceAccessService.class)),
                mock(RuntimeToolDispatcher.class), mock(ConnectorToolDispatcher.class),
                mock(SubAgentLifecycleService.class),
                new MemoryService(new MemoryStore(jdbc), AgentTestFixtures.MAPPER),
                new RunCheckpointService(jdbc, AgentTestFixtures.MAPPER), AgentTestFixtures.MAPPER);
    }

    private LeasedAgentRun newRunningRun() {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        return store.leaseSpecific(runId);
    }

    private String insertToolInvocation(LeasedAgentRun run, String status, String resultJson) {
        String toolInvocationId = "tool-" + status.toLowerCase();
        String argumentsJson = AgentTestFixtures.json(ARGUMENTS);
        String argumentsDigest = Sm3.hex(CanonicalJson.canonicalize(argumentsJson));
        jdbc.update("""
                insert into tool_invocation (
                    id, run_id, tenant_id, agent_instance_id, tool_package_version_id, tool_id,
                    tool_version, executor, target_node_id, arguments_json, arguments_digest,
                    input_schema_digest, output_schema_digest, grant_digest, risk_code, read_only,
                    always_require_approval, idempotency_type, max_attempts, status, input_revision,
                    result_json, queued_at, completed_at
                ) values (?, ?, ?, ?, 'tpv-kylin-service-1', ?, '1.0.0', 'NODE', 'node-1', ?, ?,
                    'digest-in', 'digest-out', 'digest-grant', 'R2', false, false, 'AT_MOST_ONCE', 1,
                    ?, 0, ?, ?, ?)
                """, toolInvocationId, run.id(), run.tenantId(), run.rootAgentInstanceId(), TOOL_ID,
                argumentsJson, argumentsDigest, status, resultJson,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return toolInvocationId;
    }

    private void stubModelToolCallThenFinal() {
        when(modelGateway.complete(any(), any()))
                .thenReturn(new ModelGatewayResult("", List.of(new LlmToolCall(
                        "call-1", TOOL_ID, "{\"service\":\"nginx\"}")), 10, 5, 0, 0, 1, 1, 0))
                .thenReturn(new ModelGatewayResult("done", List.of(), 10, 5, 0, 0, 1, 1, 0));
    }

    private String runMessagesText(String runId) {
        List<String> manifests = jdbc.queryForList("""
                select segment_manifest_json from context_frame
                where run_id = ? and frame_kind = 'MESSAGE' order by sequence_no asc
                """, String.class, runId);
        return String.join("\n", manifests);
    }

    @Test
    void succeededNonIdempotentToolIsReusedAndNeverDispatchedAgain() {
        AgentRunWorker worker = worker();
        LeasedAgentRun run = newRunningRun();
        String completedInvocation = insertToolInvocation(run, "SUCCEEDED", "{\"stopped\":true}");
        stubModelToolCallThenFinal();

        worker.execute(run);

        // 调度决策：已完成非幂等工具不再执行
        verify(mcpGateway, never()).callTool(any(), any(), any(), any());
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, run.id()))
                .isEqualTo("COMPLETED");
        // 工具消息复用已落库结果，并携带被复用调用 id
        String messages = runMessagesText(run.id());
        assertThat(messages).contains(completedInvocation).contains("\\\"replayed\\\":false");
        assertThat(messages).contains("\\\"stopped\\\":true");
    }

    @Test
    void failedNonIdempotentToolIsNotReusedAndStillDispatches() {
        AgentRunWorker worker = worker();
        LeasedAgentRun run = newRunningRun();
        insertToolInvocation(run, "FAILED", null);
        stubModelToolCallThenFinal();

        worker.execute(run);

        verify(mcpGateway, times(1)).callTool(any(), any(), any(), any());
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, run.id()))
                .isEqualTo("COMPLETED");
    }
}
