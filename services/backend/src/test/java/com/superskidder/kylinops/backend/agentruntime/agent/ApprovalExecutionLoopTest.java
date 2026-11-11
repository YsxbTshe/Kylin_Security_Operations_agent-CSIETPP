package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
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
import com.superskidder.kylinops.backend.agentruntime.security.ApprovalKeyLoader;
import com.superskidder.kylinops.backend.agentruntime.security.ApprovalTokenLifecycle;
import com.superskidder.kylinops.backend.agentruntime.security.DispatchGate;
import com.superskidder.kylinops.backend.agentruntime.security.KeyPurpose;
import com.superskidder.kylinops.backend.agentruntime.security.PlatformKeyReferences;
import com.superskidder.kylinops.backend.agentruntime.security.Sm2ApprovalTokenService;
import com.superskidder.kylinops.backend.agentruntime.security.Sm2WithSm3;
import com.superskidder.kylinops.backend.agentruntime.tool.ServiceRestartTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ServiceStatusTool;
import com.superskidder.kylinops.backend.agentruntime.tool.ToolRegistry;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeDispatchRequest;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeExecutorV2Registry;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeInvocationResult;
import com.superskidder.kylinops.backend.crypto.Sm3;
import com.superskidder.kylinops.backend.llm.LlmModelConfig;
import com.superskidder.kylinops.backend.llm.LlmToolCall;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalClaim;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ExecutionMode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.RiskCode;
import com.superskidder.kylinops.backend.policy.PolicyDecision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 迭代 7.5 验收：审批闭环端到端接线（worker 建单 → 人工决定 → 确定性恢复执行）。
 *
 * 此前三处断链使 R3 写操作在新链路上永远无法执行：
 *   ① DispatchGate 返回 NEEDS_APPROVAL 后没有任何代码创建审批单；
 *   ② 决策时签发的令牌 execution_attempt_id 为空（Go 侧 validateClaim 必拒）且被丢弃；
 *   ③ 恢复执行时 callTool 恒传 null 令牌，且 SubmitInvocation 不携带 handoff_id
 *      （Go 侧 claim 的 agent_invocation_id 载体），特权助手九元组复判必失配。
 *
 * 本类验证接线后的完整闭环，并断言下发令牌可通过与 Go 特权助手同构的
 * 验签 + CoversInvocation 逐字段复判（Java/Go 字节兼容性由共享测试向量另行钉住）。
 */
class ApprovalExecutionLoopTest {

    private static final Path VECTORS = Path.of("..", "..", "contracts", "test-vectors");
    private static final String KEY_ID = "approval-test-1";
    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.restart"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private ApprovalService approvalService;
    private ModelGateway modelGateway;
    private NodeExecutorV2Registry nodeRegistry;
    private Sm2ApprovalTokenService tokenService;
    private PublicKey publicKey;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        approvalService = new ApprovalService(jdbc, store, mock(ResourceAccessService.class));
        modelGateway = mock(ModelGateway.class);
        nodeRegistry = mock(NodeExecutorV2Registry.class);
        tokenService = new Sm2ApprovalTokenService();
        publicKey = loadPublicKey();

        jdbc.update("""
                insert into app_user_account (id, username, display_name, password, enabled)
                values ('user-beta', 'beta', 'Beta Approver', 'sm3-hash', true)
                """);
        // V1 基线已播种 toolpkgver-service-restart-v1（10 个写工具随基线注册），无需另建。
        runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "QUEUED");
        jdbc.update("update agent_run set root_agent_instance_id = ?, lease_owner = null where id = ?",
                instanceId, runId);
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

        ToolRegistry registry = new ToolRegistry(List.of(
                new ServiceRestartTool(AgentTestFixtures.MAPPER)));

        DispatchGate gate = mock(DispatchGate.class);
        PolicyDecision policy = new PolicyDecision(
                com.superskidder.kylinops.backend.policy.RiskCode.R3, true, false, List.of(), "builtin-v1");
        when(gate.evaluate(any())).thenReturn(
                DispatchGate.Decision.needsApproval(com.superskidder.kylinops.backend.policy.RiskCode.R3, policy));

        NodeMcpGateway mcpGateway = new NodeMcpGateway(store, nodeRegistry,
                new ServiceStatusTool(AgentTestFixtures.MAPPER),
                new com.superskidder.kylinops.backend.agentruntime.security.ApprovalTokenBinder(
                        tokenLifecycle(), tokenService),
                AgentTestFixtures.MAPPER, 30);

        return new AgentRunWorker(true, 5, "R2", store,
                new ContextCompiler(AgentTestFixtures.MAPPER, metrics),
                new ContextWindowPolicy(32_000, 0.6),
                new ContextSummarizer(AgentTestFixtures.MAPPER, metrics),
                router, modelGateway, mcpGateway, registry, gate,
                approvalService,
                mock(RuntimeToolDispatcher.class), mock(ConnectorToolDispatcher.class),
                mock(SubAgentLifecycleService.class),
                new MemoryService(new MemoryStore(jdbc), AgentTestFixtures.MAPPER),
                new RunCheckpointService(jdbc, AgentTestFixtures.MAPPER),
                AgentTestFixtures.MAPPER);
    }

    private ApprovalTokenLifecycle tokenLifecycle() {
        Map<KeyPurpose, PlatformKeyReferences.KeyReference> keys = new EnumMap<>(KeyPurpose.class);
        for (KeyPurpose purpose : KeyPurpose.values()) {
            keys.put(purpose, new PlatformKeyReferences.KeyReference(KEY_ID, "test-private", "test-public"));
        }
        ApprovalKeyLoader loader = new ApprovalKeyLoader() {
            @Override public PrivateKey loadPrivateKey(String reference) {
                return Sm2WithSm3.parsePrivateKey(readVector("TEST_ONLY_sm2_private.pem"));
            }
            @Override public PublicKey loadPublicKey(String reference) {
                return Sm2WithSm3.parsePublicKey(readVector("TEST_ONLY_sm2_public.pem"));
            }
        };
        return new ApprovalTokenLifecycle(tokenService, new PlatformKeyReferences(keys), loader);
    }

    private PublicKey loadPublicKey() {
        return Sm2WithSm3.parsePublicKey(readVector("TEST_ONLY_sm2_public.pem"));
    }

    private static byte[] readVector(String name) {
        try {
            return Files.readAllBytes(VECTORS.resolve(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private ModelGatewayResult restartToolCall() {
        return new ModelGatewayResult("", List.of(new LlmToolCall(
                "call-1", "kylin.service.restart", "{\"service\":\"nginx\"}")), 10, 5, 0, 0, 1, 1, 0);
    }

    private ModelGatewayResult finalAnswer() {
        return new ModelGatewayResult("done", List.of(), 10, 5, 0, 0, 1, 1, 0);
    }

    private String pendingApprovalId() {
        return jdbc.queryForObject(
                "select id from approval where run_id = ? and decision = 'PENDING'", String.class, runId);
    }

    @Test
    void approvedWriteExecutesExactlyOnceWithNodeVerifiableToken() {
        when(modelGateway.complete(any(), any())).thenReturn(restartToolCall()).thenReturn(finalAnswer());
        when(nodeRegistry.dispatch(any())).thenReturn(new NodeInvocationResult(true,
                "{\"output\":\"restarted nginx\"}", "", "completed", false));
        AgentRunWorker worker = worker();

        // ① 第一次执行：R3 写需要审批 → 建单 + WAITING/APPROVAL，不落占位工具结果。
        worker.execute(store.leaseSpecific(runId));
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("select waiting_reason from agent_run where id = ?",
                String.class, runId)).isEqualTo("APPROVAL");
        String approvalId = pendingApprovalId();
        String placeholderInvocationId = jdbc.queryForObject(
                "select tool_invocation_id from approval where id = ?", String.class, approvalId);
        assertThat(jdbc.queryForObject("select status from tool_invocation where id = ?",
                String.class, placeholderInvocationId)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("""
                select count(*) from context_frame
                where run_id = ? and frame_kind = 'MESSAGE' and message_role = 'tool'
                """, Integer.class, runId)).isZero();
        verify(nodeRegistry, never()).dispatch(any());

        // ② 第二身份批准 → run 唤醒。
        approvalService.decide(AgentTestDatabase.TENANT_ID, approvalId, "user-beta",
                List.of("approver"), true, "proceed");
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("QUEUED");

        // ③ 恢复执行：确定性地执行「批准的那一次调用」，带可验签令牌下发。
        worker.execute(store.leaseSpecific(runId));

        ArgumentCaptor<NodeDispatchRequest> captor = ArgumentCaptor.forClass(NodeDispatchRequest.class);
        verify(nodeRegistry).dispatch(captor.capture());
        NodeDispatchRequest dispatched = captor.getValue();
        assertThat(dispatched.toolInvocationId()).isEqualTo(placeholderInvocationId);
        assertThat(dispatched.approvalToken()).isNotBlank();
        assertThat(dispatched.agentInvocationId()).isNotBlank();

        // ④ 令牌验签 + 与实际下发调用的逐字段复判（与 Go 特权助手 CoversInvocation 同构）。
        ApprovalClaim verified = tokenService.verify(dispatched.approvalToken(),
                Map.of(KEY_ID, publicKey), Instant.now());
        ApprovalClaim actual = ApprovalClaim.newBuilder()
                .setVersion(1)
                .setTenantId(dispatched.tenantId())
                .setRunId(dispatched.runId())
                .setAgentInstanceId(dispatched.agentInstanceId())
                .setAgentInvocationId(dispatched.agentInvocationId())
                .setToolInvocationId(dispatched.toolInvocationId())
                .setExecutionAttemptId(dispatched.executionAttemptId())
                .setNodeId(dispatched.nodeId())
                .setToolId(dispatched.toolId())
                .setToolVersion(dispatched.toolVersion())
                .setArgumentsSm3(ByteString.copyFrom(Sm3.rawHash(
                        dispatched.canonicalArgumentsJson().getBytes(StandardCharsets.UTF_8))))
                .setRiskCeiling(RiskCode.RISK_CODE_R3)
                .setExecutionMode(ExecutionMode.EXECUTION_MODE_CAUTIOUS)
                .setLockVersion(dispatched.lockVersion())
                .setKeyPurpose(KeyPurpose.APPROVAL_SIGNING.wireName())
                .build();
        assertThat(tokenService.covers(verified, actual)).isTrue();

        // ⑤ 消费一次性：批准绑定已消费、run 正常收尾、审批单不再可执行。
        assertThat(jdbc.queryForObject("""
                select count(*) from execution_attempt where tool_invocation_id = ?
                """, Integer.class, placeholderInvocationId)).isEqualTo(1);
        assertThat(approvalService.findLatestByBinding(AgentTestDatabase.TENANT_ID, runId, "node-1",
                "kylin.service.restart", dispatched.argumentsDigest()).orElseThrow().consumed()).isTrue();
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void rejectedWriteNeverDispatchesAndModelIsInformed() {
        when(modelGateway.complete(any(), any())).thenReturn(restartToolCall()).thenReturn(finalAnswer());
        AgentRunWorker worker = worker();

        worker.execute(store.leaseSpecific(runId));
        String approvalId = pendingApprovalId();
        String placeholderInvocationId = jdbc.queryForObject(
                "select tool_invocation_id from approval where id = ?", String.class, approvalId);

        approvalService.decide(AgentTestDatabase.TENANT_ID, approvalId, "user-beta",
                List.of("approver"), false, "not during business hours");
        worker.execute(store.leaseSpecific(runId));

        verify(nodeRegistry, never()).dispatch(any());
        assertThat(jdbc.queryForObject("select status from tool_invocation where id = ?",
                String.class, placeholderInvocationId)).isEqualTo("REJECTED");
        Integer rejectedResults = jdbc.queryForObject("""
                select count(*) from context_frame
                where run_id = ? and frame_kind = 'MESSAGE' and message_role = 'tool'
                  and segment_manifest_json like '%approval_rejected%'
                """, Integer.class, runId);
        assertThat(rejectedResults).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void pendingApprovalIsNotDuplicatedOnSpuriousWakeup() {
        when(modelGateway.complete(any(), any())).thenReturn(restartToolCall()).thenReturn(finalAnswer());
        AgentRunWorker worker = worker();

        worker.execute(store.leaseSpecific(runId));
        // 模拟误唤醒（无决策）：恢复路径应识别 PENDING、回到 WAITING，且不重复建单。
        jdbc.update("update agent_run set status = 'QUEUED', waiting_reason = null where id = ?", runId);
        worker.execute(store.leaseSpecific(runId));

        assertThat(jdbc.queryForObject("select count(*) from approval where run_id = ?",
                Integer.class, runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId))
                .isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("select waiting_reason from agent_run where id = ?",
                String.class, runId)).isEqualTo("APPROVAL");
        verify(nodeRegistry, never()).dispatch(any());
    }
}
