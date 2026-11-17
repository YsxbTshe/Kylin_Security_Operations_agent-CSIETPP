package com.superskidder.kylinops.backend.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.auth.AuthenticatedUser;
import com.superskidder.kylinops.backend.common.ApiExceptionHandler;
import com.superskidder.kylinops.backend.security.AccessControlService;
import com.superskidder.kylinops.backend.security.RequestContext;
import com.superskidder.kylinops.backend.security.RequestContextHolder;
import java.sql.Connection;
import java.sql.SQLException;
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
 * 安全事件闭环的两个处置端点（triage / link-task）。
 *
 * <p>{@code beginTriage} 与 {@code linkTask} 此前有实现有单测但**没有任何生产调用方** ——
 * {@link IncidentController} 从未暴露它们，事件永远停在 NEW/TRIAGED，前端调用直接 404。
 * 本类按该 Controller 既有写端点的口径（{@code incident:respond} + 会话组织）验收补齐后的行为。
 */
class IncidentResponseEndpointsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALPHA = "org-alpha";
    /** 基线种子里 org-alpha 的 NEW 事件。 */
    private static final String OPEN_INCIDENT = "inc-demo-auth-001";
    /** 基线种子里已闭环的事件（终态，用于 fail-closed 断言）。 */
    private static final String RESOLVED_INCIDENT = "inc-demo-auth-000";

    private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbc = createDatabase();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new IncidentController(new IncidentService(jdbc), new AccessControlService()))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        login(Set.of("incident:read", "incident:respond"));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.clear();
    }

    @Test
    void triageReturnsResponsePlanAndMovesIncidentToTriaged() throws Exception {
        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/triage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incidentId").value(OPEN_INCIDENT))
                .andExpect(jsonPath("$.data.targetNodeId").value("node-kylin-01"))
                .andExpect(jsonPath("$.data.prompt").isNotEmpty());

        assertThat(statusOf(OPEN_INCIDENT)).isEqualTo("TRIAGED");
        assertThat(jdbc.queryForObject("select acknowledged_at from app_incident where id = ?",
                java.sql.Timestamp.class, OPEN_INCIDENT)).isNotNull();
    }

    @Test
    void linkTaskBindsAgentRunAndMovesIncidentToContaining() throws Exception {
        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/triage")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/link-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("taskId", "run-abc123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.status").value("CONTAINING"))
                .andExpect(jsonPath("$.data.summary.taskId").value("run-abc123"));

        assertThat(statusOf(OPEN_INCIDENT)).isEqualTo("CONTAINING");
    }

    @Test
    void linkTaskRejectsBlankTaskId() throws Exception {
        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/link-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("taskId", "  "))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/link-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(OPEN_INCIDENT)).isEqualTo("NEW");
    }

    /** 终态事件 fail-closed：既不能再研判，也不能再挂处置任务。 */
    @Test
    void terminalIncidentRejectsFurtherResponse() throws Exception {
        mockMvc.perform(post("/api/v1/incidents/" + RESOLVED_INCIDENT + "/triage"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/incidents/" + RESOLVED_INCIDENT + "/link-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("taskId", "run-abc123"))))
                .andExpect(status().isConflict());

        assertThat(statusOf(RESOLVED_INCIDENT)).isEqualTo("RESOLVED");
    }

    @Test
    void unknownIncidentIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/incidents/inc-does-not-exist/triage"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/incidents/inc-does-not-exist/link-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("taskId", "run-abc123"))))
                .andExpect(status().isNotFound());
    }

    /** 与该 Controller 现有写端点同口径：缺 incident:respond 一律 403。 */
    @Test
    void respondPermissionIsRequiredForBothEndpoints() throws Exception {
        login(Set.of("incident:read"));

        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/triage"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/link-task")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("taskId", "run-abc123"))))
                .andExpect(status().isForbidden());

        assertThat(statusOf(OPEN_INCIDENT)).isEqualTo("NEW");
    }

    /** 会话组织决定可见范围：他人组织的事件即使 id 正确也查不到。 */
    @Test
    void otherOrganizationIncidentIsNotReachable() throws Exception {
        RequestContextHolder.set(new RequestContext("token-test", new AuthenticatedUser(
                "user-admin", "admin", "平台管理员", null, null, true, false,
                "org-beta", List.of("org-beta"), List.of("org_admin"),
                Set.of("incident:read", "incident:respond"))));

        mockMvc.perform(post("/api/v1/incidents/" + OPEN_INCIDENT + "/triage"))
                .andExpect(status().isNotFound());

        assertThat(statusOf(OPEN_INCIDENT)).isEqualTo("NEW");
    }

    private String statusOf(String incidentId) {
        return jdbc.queryForObject("select status from app_incident where id = ?", String.class, incidentId);
    }

    private void login(Set<String> permissions) {
        RequestContextHolder.set(new RequestContext("token-test", new AuthenticatedUser(
                "user-admin", "admin", "平台管理员", null, null, true, false,
                ALPHA, List.of(ALPHA), List.of("org_admin"), permissions)));
    }

    private static JdbcTemplate createDatabase() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:incident-endpoints-" + UUID.randomUUID()
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
                    "db/migration/V8__agent_approval_permission.sql",
            }) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(migration));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("incident endpoints test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}
