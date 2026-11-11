package com.superskidder.kylinops.backend.agentruntime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.HandoffService;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeToolDispatcher;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RuntimeToolDispatcher 对 skill.search / skill.load 分支的分流与错误码收敛测试
 * （模型 tool_call → dispatcher → SkillProgressiveDisclosure → SkillService 这一段）。
 */
class SkillRuntimeToolDispatchTest {

    private static final String TENANT = "org-alpha";
    private static final String SKILL_ID = "kylin.ops.disk-triage";

    private RuntimeToolDispatcher dispatcher;
    private SkillService service;
    private SkillImporter importer;
    private SkillSearchTool searchTool;
    private SkillLoadTool loadTool;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SkillTestDatabase.create();
        ObjectMapper objectMapper = new ObjectMapper();
        service = new SkillService(jdbcTemplate);
        importer = new SkillImporter(service);
        SkillProgressiveDisclosure disclosure = new SkillProgressiveDisclosure(service);
        searchTool = new SkillSearchTool(disclosure, service, objectMapper);
        loadTool = new SkillLoadTool(disclosure, service, objectMapper);
        dispatcher = new RuntimeToolDispatcher(mock(AgentRuntimeStore.class), mock(HandoffService.class), objectMapper,
                org.mockito.Mockito.mock(com.superskidder.kylinops.backend.registry.AgentRegistryService.class));
    }

    private LeasedAgentRun run() {
        return new LeasedAgentRun("run-1", TENANT, "user-alpha", "agent-root",
                "查一下磁盘排查手册", "CAUTIOUS", "{\"nodeId\":\"node-1\"}", "{}", "{}", 0L, null, "OFF");
    }

    @Test
    void searchDispatchCompletesWithoutSuspendingTheRun() {
        publishValidSkill();

        RuntimeToolDispatcher.DispatchOutcome outcome =
                dispatcher.dispatch(run(), searchTool, Map.of("query", "磁盘"));

        // 同步工具：不得把 run 挂起，否则执行循环白等一次租约超时。
        assertThat(outcome.runSuspended()).isFalse();
        assertThat(outcome.handoffId()).isNull();
        assertThat(outcome.structuredOutput()).containsEntry("resultCount", 1);
        assertThat(outcome.renderedOutput()).containsEntry("type", "skill-search");
    }

    @Test
    void loadDispatchReturnsBodyWithSourceAnnotation() {
        publishValidSkill();

        RuntimeToolDispatcher.DispatchOutcome outcome =
                dispatcher.dispatch(run(), loadTool, Map.of("skill_id", SKILL_ID));

        assertThat(outcome.runSuspended()).isFalse();
        assertThat(outcome.structuredOutput())
                .containsEntry("source", "skill://" + SKILL_ID + "/SKILL.md")
                .containsEntry("provenance", SkillLoadTool.PROVENANCE);
    }

    @Test
    void invalidArgumentsSurfaceAsStructuredToolErrors() {
        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), searchTool, Map.of("limit", 999))))
                .isEqualTo("SKILL_SEARCH_INVALID");
        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), loadTool, Map.of())))
                .isEqualTo("SKILL_LOAD_INVALID");
    }

    @Test
    void unpublishedSkillIsRejectedByTheDispatcher() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), loadTool, Map.of("skill_id", SKILL_ID))))
                .isEqualTo("SKILL_LOAD_REJECTED");
    }

    @Test
    void isolatedSkillIsRejectedByTheDispatcher() {
        publishValidSkill();
        service.isolate(TENANT, SKILL_ID);

        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), loadTool, Map.of("skill_id", SKILL_ID))))
                .isEqualTo("SKILL_LOAD_REJECTED");
    }

    @Test
    void pathTraversalGetsItsOwnErrorCode() {
        publishValidSkill();

        assertThat(errorCodeOf(() -> dispatcher.dispatch(run(), loadTool,
                Map.of("skill_id", SKILL_ID, "path", "../../etc/passwd"))))
                .isEqualTo("SKILL_PATH_REJECTED");
    }

    private String errorCodeOf(Runnable call) {
        try {
            call.run();
        } catch (NodeMcpGateway.ToolCallException exception) {
            assertThat(exception.retryable()).isFalse();
            return exception.errorCode();
        }
        throw new AssertionError("expected a ToolCallException");
    }

    private void publishValidSkill() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);
    }
}
