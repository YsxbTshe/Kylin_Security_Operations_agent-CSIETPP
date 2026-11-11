package com.superskidder.kylinops.backend.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.ResourceAccessService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.auth.AuthenticatedUser;
import com.superskidder.kylinops.backend.common.ApiExceptionHandler;
import com.superskidder.kylinops.backend.security.AccessControlService;
import com.superskidder.kylinops.backend.security.RequestContext;
import com.superskidder.kylinops.backend.security.RequestContextHolder;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ApprovalController 的租户边界与权限门禁集成测试。
 *
 * <p>此前四个端点都以 {@code tenantId != null ? tenantId : 当前组织} 取租户，且整类
 * 零次 requirePermission：org-alpha 的任意登录用户带 {@code ?tenantId=org-beta}
 * 即可读取并审批他人组织的 R3/R4 写操作。本类用真实 {@link AccessControlService}
 * （读 ThreadLocal 会话）+ 真实 {@link ApprovalService}（H2 上跑生产迁移，含 V6 的
 * agent-approval ABAC 策略）验收：请求参数再也顶不掉会话组织，且缺权限即 403。
 */
class ApprovalControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALPHA = "org-alpha";
    private static final String BETA = "org-beta";
    /** 会话主体：org-alpha 的审批人（基线用户，可作 decided_by_user_id）。 */
    private static final String APPROVER_ID = "user-admin";
    /** 审批发起人：与审批人不同，避免撞上「不可自批」约束。 */
    private static final String REQUESTER_ID = "user-secops";

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private String alphaApprovalId;
    private String betaApprovalId;

    @BeforeEach
    void setUp() {
        jdbc = createDatabase();
        ObjectMapper mapper = new ObjectMapper();
        AgentRuntimeStore store = new AgentRuntimeStore(jdbc, mapper, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        ApprovalService approvalService = new ApprovalService(jdbc, store,
                new ResourceAccessService(jdbc, mapper));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ApprovalController(approvalService, store, new AccessControlService()))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        seedToolPackage();
        alphaApprovalId = seedPendingApproval(approvalService, ALPHA);
        betaApprovalId = seedPendingApproval(approvalService, BETA);
        login(Set.of("agent:read", "agent:approve"));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.clear();
    }

    /** 会话是 org-alpha 的审批人；roleCodes 决定 ABAC（org_admin 上限 R3）。 */
    private void login(Set<String> permissions) {
        RequestContextHolder.set(new RequestContext("token-test", new AuthenticatedUser(
                APPROVER_ID, "admin", "平台管理员", null, null, true, false,
                ALPHA, List.of(ALPHA), List.of("org_admin"), permissions)));
    }

    @Test
    void listPendingIgnoresTenantIdParameterAndOnlyReturnsSessionOrganization() throws Exception {
        // tenantId 参数已被删除：即使显式带上他人组织，返回的仍只有自己组织的待审。
        mockMvc.perform(get("/api/v2/approvals").param("tenantId", BETA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(alphaApprovalId))
                .andExpect(jsonPath("$[0].tenantId").value(ALPHA));
    }

    @Test
    void detailCannotReachOtherOrganizationApproval() throws Exception {
        mockMvc.perform(get("/api/v2/approvals/" + alphaApprovalId).param("tenantId", BETA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(ALPHA));

        mockMvc.perform(get("/api/v2/approvals/" + betaApprovalId).param("tenantId", BETA))
                .andExpect(status().isNotFound());
    }

    @Test
    void decideCannotApproveOtherOrganizationApproval() throws Exception {
        mockMvc.perform(post("/api/v2/approvals/" + betaApprovalId + "/decide")
                        .param("tenantId", BETA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("approve", true, "reason", "cross-tenant"))))
                .andExpect(status().isNotFound());
        assertThat(decisionOf(betaApprovalId)).isEqualTo("PENDING");

        // 自己组织的单子仍可正常决策（ABAC：org_admin 上限 R3 覆盖 R3 审批）。
        mockMvc.perform(post("/api/v2/approvals/" + alphaApprovalId + "/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("approve", true, "reason", "ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByUserId").value(APPROVER_ID));
    }

    @Test
    void batchDecideCannotApproveOtherOrganizationApproval() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of(
                "approvalIds", List.of(betaApprovalId, alphaApprovalId),
                "approve", true, "reason", "batch"));

        mockMvc.perform(post("/api/v2/approvals/batch-decide")
                        .param("tenantId", BETA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                // 跨组织的那一单在本组织查不到，只有自己组织的被决策。
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(alphaApprovalId));

        assertThat(decisionOf(betaApprovalId)).isEqualTo("PENDING");
        assertThat(decisionOf(alphaApprovalId)).isEqualTo("APPROVED");
    }

    @Test
    void readEndpointsRejectUserWithoutAgentReadPermission() throws Exception {
        login(Set.of("agent:approve"));

        mockMvc.perform(get("/api/v2/approvals")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v2/approvals/" + alphaApprovalId)).andExpect(status().isForbidden());
    }

    @Test
    void decisionEndpointsRejectUserWithoutApprovalPermission() throws Exception {
        login(Set.of("agent:read"));
        String decideBody = MAPPER.writeValueAsString(Map.of("approve", true, "reason", "no permission"));
        String batchBody = MAPPER.writeValueAsString(Map.of(
                "approvalIds", List.of(alphaApprovalId), "approve", true, "reason", "no permission"));

        mockMvc.perform(post("/api/v2/approvals/" + alphaApprovalId + "/decide")
                        .contentType(MediaType.APPLICATION_JSON).content(decideBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v2/approvals/batch-decide")
                        .contentType(MediaType.APPLICATION_JSON).content(batchBody))
                .andExpect(status().isForbidden());

        assertThat(decisionOf(alphaApprovalId)).isEqualTo("PENDING");
    }

    private String decisionOf(String approvalId) {
        return jdbc.queryForObject("select decision from approval where id = ?", String.class, approvalId);
    }

    /** 为某组织铺一条 run/instance/tool_invocation 链路，返回其 PENDING 审批单 id。 */
    private String seedPendingApproval(ApprovalService approvalService, String tenantId) {
        Timestamp now = Timestamp.from(Instant.now());
        String runId = "run-" + tenantId;
        String instanceId = "agent-" + tenantId;
        String invocationId = "tool-" + tenantId;
        jdbc.update("""
                insert into agent_run (
                    id, tenant_id, human_principal_id, objective, execution_mode,
                    target_scope_json, target_scope_digest, grant_version, grant_snapshot_json,
                    status, waiting_reason, input_revision, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, 'restart nginx', 'CAUTIOUS', '{"nodeId":"node-1"}', 'digest-target',
                    1, '{}', 'WAITING', 'APPROVAL', 0, '{}', '{}', ?, ?)
                """, runId, tenantId, REQUESTER_ID, now, now);
        jdbc.update("""
                insert into agent_instance (
                    id, run_id, tenant_id, parent_agent_instance_id, instance_name, avatar_seed,
                    definition_snapshot_json, objective, depth, status, reasoning_level,
                    grant_snapshot_json, grant_digest, budget_json, usage_json, created_at, updated_at
                ) values (?, ?, ?, null, 'Fixture Agent', 'fixture', '{}', 'restart nginx', 0, 'RUNNING',
                    'OFF', '{}', 'digest-grant', '{}', '{}', ?, ?)
                """, instanceId, runId, tenantId, now, now);
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        jdbc.update("""
                insert into tool_invocation (
                    id, run_id, tenant_id, agent_instance_id, tool_package_version_id, tool_id,
                    tool_version, executor, target_node_id, arguments_json, arguments_digest,
                    input_schema_digest, output_schema_digest, grant_digest, risk_code, read_only,
                    always_require_approval, idempotency_type, max_attempts, status, input_revision, queued_at
                ) values (?, ?, ?, ?, 'tpv-kylin-service-1', 'kylin.service.restart', '1.0.0', 'NODE',
                    'node-1', '{"service":"nginx"}', 'digest-args', 'digest-in', 'digest-out',
                    'digest-grant', 'R3', false, true, 'AT_MOST_ONCE', 1, 'QUEUED', 0, ?)
                """, invocationId, runId, tenantId, instanceId, now);
        return approvalService.createApproval(tenantId, runId, invocationId, "node-1",
                "kylin.service.restart", "1.0.0", "{\"service\":\"nginx\"}", "R3", "CAUTIOUS",
                instanceId, null, REQUESTER_ID);
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

    /** 按生产 Flyway 顺序在 H2 上执行全部迁移（V6 提供 agent-approval 的 ABAC 策略）。 */
    private static JdbcTemplate createDatabase() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:approval-controller-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            for (String migration : new String[] {
                    "db/migration/V1__baseline.sql",
                    "db/migration/V2__node_session_native_guard.sql",
                    "db/migration/V3__perception_readonly_tools.sql",
                    "db/migration/V4__honest_write_output_schema.sql",
                    "db/migration/V5__agent_tool_abac_policies.sql",
                    "db/migration/V6__agent_approval_abac_policies.sql",
                    "db/migration/V7__user_must_change_password.sql",
            }) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("approval controller test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}
