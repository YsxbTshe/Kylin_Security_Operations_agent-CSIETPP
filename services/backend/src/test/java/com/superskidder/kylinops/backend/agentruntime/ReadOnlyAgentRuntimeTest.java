package com.superskidder.kylinops.backend.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGateway;
import com.superskidder.kylinops.backend.agentruntime.model.ModelGatewayResult;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEvent;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.tool.ServiceStatusTool;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeExecutorV2Registry;
import com.superskidder.kylinops.backend.auth.AuthService;
import com.superskidder.kylinops.backend.auth.LoginRequest;
import com.superskidder.kylinops.backend.crypto.Sm3;
import com.superskidder.kylinops.backend.llm.LlmToolCall;
import com.superskidder.kylinops.backend.nodeexecutor.v2.AckKind;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse;
import com.superskidder.kylinops.backend.nodeexecutor.v2.InvocationAck;
import com.superskidder.kylinops.backend.nodeexecutor.v2.InvocationProtocolStatus;
import com.superskidder.kylinops.backend.nodeexecutor.v2.InvocationResult;
import com.superskidder.kylinops.backend.nodeexecutor.v2.SubmitInvocation;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test",
        "app.host-agent.grpc.enabled=false",
        "app.agent-runtime.enabled=false",
        "app.agent-runtime.tool-timeout-seconds=5"
})
class ReadOnlyAgentRuntimeTest {

    private static final String RESULT_JSON = """
            {"service":"nginx","activeState":"active","subState":"running","loadState":"loaded","unitFileState":"enabled"}
            """.trim();

    @LocalServerPort
    int port;

    @Autowired
    NodeExecutorV2Registry registry;

    @Autowired
    AgentRuntimeStore store;

    @Autowired
    AgentRunWorker worker;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AuthService authService;

    @MockitoBean
    ModelGateway modelGateway;

    @BeforeEach
    void connectFakeNodeAndEnableModel() {
        jdbcTemplate.update("""
                update app_platform_llm_config set provider_type = 'OPENAI_COMPATIBLE', base_url = 'http://unused',
                    api_key = 'test', model = 'test-model', enabled = true, timeout_ms = 1000,
                    retry_count = 0, thinking_levels = 'OFF', thinking_default = 'OFF', updated_at = current_timestamp
                where id = 'default'
                """);
        // DispatchGate 走 ABAC fail-closed：没有显式 ALLOW 策略时工具下发一律被拒。
        // 为测试主体 user-admin 发放 agent-tool execute 策略，让只读切片能走完下发链路。
        jdbcTemplate.update("delete from app_resource_policy where id = 'rp-test-user-admin-agent-tool'");
        jdbcTemplate.update("""
                insert into app_resource_policy (id, organization_id, subject_type, subject_id, resource_type,
                    resource_pattern, action, effect, condition_json, created_at)
                values ('rp-test-user-admin-agent-tool', 'org-alpha', 'USER', 'user-admin', 'agent-tool',
                    '*', 'execute', 'ALLOW', null, CURRENT_TIMESTAMP)
                """);
        registry.connect("org-alpha", "node-kylin-01", new FakeNodeObserver(registry), 0, 0);
        // 第 1 轮模型返回工具调用，第 2 轮返回无工具调用的终态回复，
        // 使 AgentRunWorker 的 agentic 循环在执行完一次工具调用后正常收敛。
        when(modelGateway.complete(any(), any()))
                .thenReturn(new ModelGatewayResult("",
                        List.of(new LlmToolCall("call-1", ServiceStatusTool.TOOL_ID, "{\"service\":\"nginx\"}")),
                        120, 20, 0, 0, 5, 10, 0))
                .thenReturn(new ModelGatewayResult("nginx 服务运行正常", List.of(), 30, 8, 0, 0, 4, 6, 0));
    }

    @Test
    void executesOneModelOneMcpOneGrpcAndFinishesWithTrustedRenderer() {
        AgentRuntimeStore.CreatedRun created = store.createReadOnlyRun("org-alpha", "user-admin",
                "查询 node-kylin-01 上 nginx 服务状态", "node-kylin-01", "nginx", null, "OFF");
        LeasedAgentRun leased = store.leaseSpecific(created.runId());

        worker.execute(leased);

        AgentRuntimeStore.RunView completed = store.findRun("org-alpha", created.runId()).orElseThrow();
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.outcome()).isEqualTo("SUCCEEDED");
        // agentic 循环契约：工具派发包裹在一条模型调用记录内，终态还需一轮无工具调用的模型收敛，
        // 因此一次工具调用共落库 3 条 completeModelInvocation 计量。
        assertThat(completed.usage()).containsEntry("modelInvocations", 3).containsEntry("toolInvocations", 1);
        assertThat(completed.finalResult()).containsEntry("directDisplay", true)
                .containsEntry("modelRewriteCount", 0)
                .containsEntry("path", "task-agent>mcp>grpc-v2>trusted-renderer");
        List<String> eventTypes = store.eventsAfter("org-alpha", created.runId(), 0, 100).stream()
                .map(event -> event.eventType()).toList();
        assertThat(eventTypes).containsExactly(
                "RUN_CREATED", "RUN_STARTED", "CONTEXT_COMPILED", "MODEL_INVOCATION_STARTED",
                "MODEL_INVOCATION_COMPLETED", "MODEL_INVOCATION_STARTED", "TOOL_INVOCATION_QUEUED",
                "NODE_INVOCATION_ACKNOWLEDGED", "TOOL_INVOCATION_SUCCEEDED", "MODEL_INVOCATION_COMPLETED",
                "MODEL_INVOCATION_STARTED", "MODEL_INVOCATION_COMPLETED", "RUN_COMPLETED");
        assertThat(eventTypes).noneMatch(type -> type.contains("PLAN") || type.contains("SUB_AGENT") || type.contains("RAG"));
        // 此处原先断言 outbox_event 里有等量副本。outbox 无消费者且绕过保留期清理，
        // 已随迁移 V9 整体下线；审计的权威源是 agent_run_event 的 SM3 哈希链，改断言链完整。
        assertThat(store.verifyChain("org-alpha", created.runId()).valid()).isTrue();
        assertThat(store.verifyChain("org-alpha", created.runId()).eventCount()).isEqualTo(eventTypes.size());
    }

    @Test
    void propagatesExplicitModelProfileAndReasoningIntoTheInvocation() {
        jdbcTemplate.update("""
                insert into model_profile (
                    id, tenant_id, llm_config_id, profile_name, routing_description,
                    cache_policy, priority, enabled, created_at, updated_at
                ) values ('profile-explicit', 'org-alpha', 'default', 'Explicit test profile',
                          'service status queries', 'AUTOMATIC_PREFIX', 10, true,
                          current_timestamp, current_timestamp)
                """);
        AgentRuntimeStore.CreatedRun created = store.createReadOnlyRun("org-alpha", "user-admin",
                "查询 node-kylin-01 上 nginx 服务状态", "node-kylin-01", "nginx", "profile-explicit", "HIGH");

        worker.execute(store.leaseSpecific(created.runId()));

        List<Map<String, Object>> invocations = jdbcTemplate.queryForList("""
                select ai.model_profile_id, inv.reasoning_level
                from agent_instance ai join agent_invocation inv on inv.agent_instance_id = ai.id
                where ai.run_id = ?
                """, created.runId());
        assertThat(invocations).isNotEmpty().allSatisfy(invocation -> assertThat(invocation)
                .containsEntry("MODEL_PROFILE_ID", "profile-explicit")
                .containsEntry("REASONING_LEVEL", "OFF"));
    }

    @Test
    void reclaimsAnExpiredLeaseWithoutAllowingConcurrentLeaseOwners() {
        AgentRuntimeStore.CreatedRun created = store.createReadOnlyRun("org-alpha", "user-admin",
                "查询 node-kylin-01 上 nginx 服务状态", "node-kylin-01", "nginx", null, "OFF");

        LeasedAgentRun first = store.leaseNext().orElseThrow();
        assertThat(first.id()).isEqualTo(created.runId());
        assertThat(store.leaseNext()).isEmpty();
        jdbcTemplate.update("update agent_run set lease_until = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), created.runId());
        LeasedAgentRun reclaimed = store.leaseNext().orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(created.runId());
        assertThat(store.eventsAfter("org-alpha", created.runId(), 0, 100).stream()
                .filter(event -> event.eventType().equals("RUN_STARTED"))).hasSize(2);
        store.failRun(reclaimed, "TEST_CLEANUP", "lease recovery verified");
    }

    /**
     * 验收基线 18.3：输出必须通过 outputSchema。节点即使按协议「成功」返回，
     * 只要结果不满足工具 outputSchema（缺必填字段），run 也必须判失败
     * （CONTRACT_VALIDATION_FAILED），Agent 不得把该输出当作成功继续决策。
     */
    @Test
    void nodeOutputViolatingOutputSchemaFailsTheRun() {
        // 缺 activeState/subState/loadState/unitFileState 必填字段——协议成功、契约失败。
        String schemaViolatingResult = "{\"service\":\"nginx\"}";
        registry.connect("org-alpha", "node-kylin-01",
                new FakeNodeObserver(registry, schemaViolatingResult), 0, 0);

        AgentRuntimeStore.CreatedRun created = store.createReadOnlyRun("org-alpha", "user-admin",
                "查询 node-kylin-01 上 nginx 服务状态", "node-kylin-01", "nginx", null, "OFF");
        worker.execute(store.leaseSpecific(created.runId()));

        AgentRuntimeStore.RunView failed = store.findRun("org-alpha", created.runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.outcome()).isEqualTo("NOT_ACHIEVED");
        assertThat(failed.finalResult()).containsEntry("errorCode", "CONTRACT_VALIDATION_FAILED");
        assertThat(store.eventsAfter("org-alpha", created.runId(), 0, 100).stream()
                .map(AgentRunEvent::eventType))
                .contains("TOOL_INVOCATION_SUCCEEDED") // 协议层成功如实落库……
                .contains("RUN_FAILED")                // ……但契约校验使 run 以失败收尾
                .doesNotContain("RUN_COMPLETED");
    }

    /**
     * 验收基线 18.4：SSE 使用 Last-Event-ID 完整回放，不重复/遗漏。
     * 事件仓库侧的完整性切片另见 EventStreamReplayAcceptanceTest。
     */
    @Test
    void sseReplaysStrictlyAfterLastEventIdFromPostgres() throws Exception {
        AgentRuntimeStore.CreatedRun created = store.createReadOnlyRun("org-alpha", "user-admin",
                "查询 node-kylin-01 上 nginx 服务状态", "node-kylin-01", "nginx", null, "OFF");
        worker.execute(store.leaseSpecific(created.runId()));
        String token = authService.login(new LoginRequest("admin", "admin123")).token();

        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v2/agent-runs/" + created.runId() + "/events"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", "5")
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("id:5\n").contains("id:6\n", "id:9\n",
                "event:TOOL_INVOCATION_QUEUED", "event:RUN_COMPLETED");

        HttpResponse<String> caughtUp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v2/agent-runs/" + created.runId() + "/events"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", "13")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(caughtUp.statusCode()).isEqualTo(200);
        assertThat(caughtUp.body()).isEmpty();
    }

    private static final class FakeNodeObserver implements StreamObserver<ControlStreamResponse> {
        private final NodeExecutorV2Registry registry;
        private final String resultJson;

        private FakeNodeObserver(NodeExecutorV2Registry registry) {
            this(registry, RESULT_JSON);
        }

        private FakeNodeObserver(NodeExecutorV2Registry registry, String resultJson) {
            this.registry = registry;
            this.resultJson = resultJson;
        }

        @Override
        public void onNext(ControlStreamResponse response) {
            if (!response.hasSubmit()) {
                return;
            }
            SubmitInvocation submit = response.getSubmit();
            registry.acknowledge(InvocationAck.newBuilder()
                    .setToolInvocationId(submit.getToolInvocationId())
                    .setExecutionAttemptId(submit.getExecutionAttemptId())
                    .setKind(AckKind.ACK_KIND_RECEIVED)
                    .setPersistedAt(com.google.protobuf.Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond()).build())
                    .build());
            registry.result(InvocationResult.newBuilder()
                    .setToolInvocationId(submit.getToolInvocationId())
                    .setExecutionAttemptId(submit.getExecutionAttemptId())
                    .setStatus(InvocationProtocolStatus.INVOCATION_PROTOCOL_STATUS_SUCCEEDED)
                    .setStructuredResultJson(resultJson)
                    .setResultSm3(ByteString.copyFrom(HexFormat.of().parseHex(Sm3.hex(resultJson))))
                    .setMessage("completed")
                    .setCompletedAt(com.google.protobuf.Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond()).build())
                    .build());
        }

        @Override public void onError(Throwable throwable) { }
        @Override public void onCompleted() { }
    }
}
