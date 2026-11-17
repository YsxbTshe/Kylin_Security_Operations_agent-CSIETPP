package com.superskidder.kylinops.backend.agentruntime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.auth.AuthenticatedUser;
import com.superskidder.kylinops.backend.common.ApiExceptionHandler;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PlanController 最小集成测试：standalone MockMvc + 真实 PlanService（H2），
 * 覆盖路由、Bean 校验、ApiResponse 包装与 AppException 状态码映射。
 */
class PlanControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> SOURCE_SCOPE = Map.of("nodeId", "node-1", "service", "sshd");
    private static final Map<String, Object> SOURCE_GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private MockMvc mockMvc;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = PlanTestDatabase.create();
        ObjectMapper mapper = new ObjectMapper();
        store = new AgentRuntimeStore(jdbc, mapper, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        PlanService planService = new PlanService(jdbc, mapper, store);
        AccessControlService accessControl = mock(AccessControlService.class);
        when(accessControl.currentUser()).thenReturn(new AuthenticatedUser(
                PlanTestDatabase.USER_ID, "alpha", "Alpha User", null, null, true, false,
                PlanTestDatabase.TENANT_ID, List.of(PlanTestDatabase.TENANT_ID),
                List.of("admin"), Set.of("agent:read", "assistant:use")));
        when(accessControl.currentOrganizationId()).thenReturn(PlanTestDatabase.TENANT_ID);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PlanController(planService, accessControl))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        runId = store.createWriteRun(PlanTestDatabase.TENANT_ID, PlanTestDatabase.USER_ID,
                "harden sshd configuration", "CAUTIOUS", SOURCE_SCOPE, SOURCE_GRANT, null, null).runId();
    }

    private String planBase() {
        return "/api/v2/agent-runs/" + runId + "/plan";
    }

    private String createVersion(String content) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("contentMarkdown", content));
        String response = mockMvc.perform(post(planBase())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).at("/data/id").asText();
    }

    @Test
    void createListAndGetVersionOverRest() throws Exception {
        String planId = createVersion("# Plan v1");

        mockMvc.perform(get(planBase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].createdBy").value(PlanTestDatabase.USER_ID));

        mockMvc.perform(get(planBase() + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(planId))
                .andExpect(jsonPath("$.data.contentMarkdown").value("# Plan v1"));

        mockMvc.perform(get(planBase() + "/99"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(planBase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("contentMarkdown", "  "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void amendOverRestEnforcesLatestBase() throws Exception {
        createVersion("# v1");
        String amendBody = MAPPER.writeValueAsString(Map.of("baseVersion", 1, "contentMarkdown", "# v2"));

        mockMvc.perform(post(planBase() + "/amend")
                        .contentType(MediaType.APPLICATION_JSON).content(amendBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(post(planBase() + "/amend")
                        .contentType(MediaType.APPLICATION_JSON).content(amendBody))
                .andExpect(status().isConflict());
    }

    @Test
    void launchOverRestBackfillsSourcePlanVersionAndRejectsScopeExpansion() throws Exception {
        String planId = createVersion("# launch");

        String launch = MAPPER.writeValueAsString(Map.of(
                "objective", "execute the plan",
                "executionMode", "CAUTIOUS",
                "targetScope", Map.of("nodeId", "node-1", "service", "sshd")));
        String response = mockMvc.perform(post(planBase() + "/1/launch")
                        .contentType(MediaType.APPLICATION_JSON).content(launch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = MAPPER.readTree(response).get("data");
        String launchedRunId = data.get("runId").asText();
        assertThat(jdbc.queryForObject("select source_plan_version_id from agent_run where id = ?",
                String.class, launchedRunId)).isEqualTo(planId);

        String expanded = MAPPER.writeValueAsString(Map.of(
                "objective", "escape the scope",
                "targetScope", Map.of("nodeId", "node-2")));
        mockMvc.perform(post(planBase() + "/1/launch")
                        .contentType(MediaType.APPLICATION_JSON).content(expanded))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(planBase() + "/42/launch")
                        .contentType(MediaType.APPLICATION_JSON).content(launch))
                .andExpect(status().isNotFound());
    }
}
