package com.superskidder.kylinops.backend.agentruntime.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.AbacPolicyEngine;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolExecutor;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.agentruntime.security.DispatchGate;
import com.superskidder.kylinops.backend.policy.PolicyToolCatalog;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** ConnectorInvokeTool 参数边界 + 元数据 + DispatchGate 链路覆盖（审批默认开启）。 */
class ConnectorInvokeToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectorInvokeTool tool;
    private JdbcTemplate jdbc;
    private DispatchGate dispatchGate;

    @BeforeEach
    void setUp() {
        tool = new ConnectorInvokeTool(MAPPER);
        jdbc = ConnectorTestDatabase.create();
        // app_resource_policy 表已由新基线 V1__baseline.sql 创建；此处仅补本测试的放行策略。
        jdbc.update("""
                insert into app_resource_policy (id, organization_id, subject_type, subject_id,
                    resource_type, resource_pattern, action, effect, condition_json, created_at)
                values (?, ?, 'USER', 'user-alpha', 'agent-tool', 'kylin.connector.invoke',
                    'execute', 'ALLOW', null, CURRENT_TIMESTAMP)
                """, "rp-connector-invoke", ConnectorTestDatabase.TENANT_ID);
        // DispatchGate 在工具维度之后还要过一次 HOST 维度 ABAC（ADR-003 §2.1，fail-closed）：
        // 补一条本测试主体对目标节点的 operate 放行，否则命中默认拒绝而非本用例要断言的门禁。
        jdbc.update("""
                insert into app_resource_policy (id, organization_id, subject_type, subject_id,
                    resource_type, resource_pattern, action, effect, condition_json, created_at)
                values (?, ?, 'USER', 'user-alpha', 'HOST', '*', 'operate', 'ALLOW', null, CURRENT_TIMESTAMP)
                """, "rp-connector-host-operate", ConnectorTestDatabase.TENANT_ID);
        PolicyToolCatalog toolCatalog = new PolicyToolCatalog(jdbc, MAPPER);
        PolicyEngine policyEngine = new PolicyEngine(MAPPER, toolCatalog, null);
        dispatchGate = new DispatchGate(policyEngine,
                new com.superskidder.kylinops.backend.policy.IntentAlignmentGuard(toolCatalog),
                new AbacPolicyEngine(MAPPER),
                new ResourceAccessService(jdbc, MAPPER),
                new com.superskidder.kylinops.backend.registry.AgentRegistryService(jdbc, MAPPER));
    }

    @Test
    void metadataMarksExternalSideEffectAsApprovalRequired() {
        assertThat(tool.toolId()).isEqualTo("kylin.connector.invoke");
        assertThat(tool.executor()).isEqualTo(ToolExecutor.CONNECTOR);
        assertThat(tool.riskFloor()).isEqualTo("R3");
        assertThat(tool.alwaysRequireApproval()).isTrue();
        assertThat(tool.idempotency()).isEqualTo(ToolIdempotency.NON_RETRYABLE);
        assertThat(tool.maxAttempts()).isEqualTo(1);
        assertThat(tool.readOnly()).isFalse();
        assertThat(tool.llmTool()).isNotNull();
    }

    @Test
    void validateArgumentsBoundaries() {
        assertThatThrownBy(() -> tool.validateArguments(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector_id");
        assertThatThrownBy(() -> tool.validateArguments(Map.of("connector_id", "webhook", "extra", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field");
        for (String bad : List.of("Webhook", "web-hook", "", "x".repeat(65))) {
            assertThatThrownBy(() -> tool.validateArguments(Map.of("connector_id", bad)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tool.validateArguments(Map.of("connector_id", 42)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(
                Map.of("connector_id", "webhook", "path", "/x".repeat(2000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> tool.validateArguments(
                Map.of("connector_id", "webhook", "query", Map.of("k", Map.of("nested", 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar");
        assertThatThrownBy(() -> tool.validateArguments(
                Map.of("connector_id", "webhook", "body", "not-an-object")))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> validated = tool.validateArguments(Map.of(
                "connector_id", "http_read", "path", "/hosts", "query", Map.of("page", 2)));
        assertThat(validated).containsEntry("connector_id", "http_read")
                .containsEntry("path", "/hosts");
        assertThat(validated.get("query")).isEqualTo(Map.of("page", "2"));
    }

    @Test
    void validateOutputParsesStructuredResultAndRejectsUnknownFields() {
        Map<String, Object> output = tool.validateOutput(
                "{\"connectorId\":\"webhook\",\"target\":\"http://h/x\",\"status\":200,"
                        + "\"body\":\"ok\",\"truncated\":false,\"durationMs\":12,\"authorization\":\"***\"}");
        assertThat(output).containsEntry("connectorId", "webhook")
                .containsEntry("status", 200)
                .containsEntry("authorization", "***");

        assertThatThrownBy(() -> tool.validateOutput("{\"connectorId\":\"w\",\"status\":200,\"leak\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field");
        assertThatThrownBy(() -> tool.validateOutput("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dispatchGateRequiresApprovalForConnectorInvoke() {
        DispatchGate.Decision decision = dispatchGate.evaluate(dispatchRequest("CAUTIOUS", List.of(), false));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.NEEDS_APPROVAL);
    }

    @Test
    void dispatchGateGrantControlsCoverConnectorInvoke() {
        DispatchGate.Decision excluded = dispatchGate.evaluate(
                dispatchRequest("CAUTIOUS", List.of("kylin.service.status"), false));
        assertThat(excluded.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(excluded.rejectionCode()).isEqualTo("GRANT_EXCLUDES_TOOL");

        DispatchGate.Decision readOnlyGrant = dispatchGate.evaluate(
                dispatchRequest("CAUTIOUS", List.of(), true));
        assertThat(readOnlyGrant.outcome()).isEqualTo(DispatchGate.Decision.Outcome.REJECTED);
        assertThat(readOnlyGrant.rejectionCode()).isEqualTo("GRANT_READ_ONLY");
    }

    /**
     * AUTO 模式不得自动放行连接器外呼（会产生外部副作用，自报 R3）。
     *
     * <p>此处走**真实** PolicyEngine + PolicyToolCatalog：kylin.connector.invoke 不在
     * 目录的已知表里，命中 {@code describeTool} 的 fail-closed 兜底（requiresApproval=true、
     * riskCode=R3），因此生效风险 R3 超出 AUTO 上限 R2，必须转人工审批。
     * 这条锁的是「目录外工具默认按 R3 处理」这个性质本身——若有人把兜底改宽松，这里会红。
     */
    @Test
    void autoModeMustNotAutoApproveConnectorInvoke() {
        assertThat(tool.riskFloor()).isEqualTo("R3");

        DispatchGate.Decision decision = dispatchGate.evaluate(dispatchRequest("AUTO", List.of(), false));

        assertThat(decision.outcome()).isEqualTo(DispatchGate.Decision.Outcome.NEEDS_APPROVAL);
        assertThat(decision.autoApproved()).isFalse();
        assertThat(decision.riskCode().name()).isEqualTo("R3");
    }

    private DispatchGate.DispatchRequest dispatchRequest(String executionMode, List<String> grantToolIds,
                                                         boolean grantReadOnly) {
        return new DispatchGate.DispatchRequest(
                ConnectorTestDatabase.TENANT_ID, "run-connector-1", "user-alpha", List.of(),
                "notify on-call about disk pressure", "node-1", tool.toolId(), "{}",
                tool.readOnly(), tool.alwaysRequireApproval(), tool.riskFloor(), executionMode,
                grantToolIds, grantReadOnly, List.of(),
                "AVAILABLE", Instant.now(), 5, "R2");
    }
}
