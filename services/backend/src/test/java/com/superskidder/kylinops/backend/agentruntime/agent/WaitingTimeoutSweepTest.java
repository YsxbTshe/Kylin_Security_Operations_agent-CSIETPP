package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.quota.TenantQuotaService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6.2 WAITING 超时清扫：超过租户 run_wait_timeout 的 WAITING run 置 CANCELLED，
 * 级联清理未完成子 run 与 OPEN handoff；未超时的不动。
 */
class WaitingTimeoutSweepTest {

    private static final Map<String, Object> PARENT_GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> PARENT_BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private HandoffService handoffService;
    private RuntimeToolDispatcher dispatcher;
    private SubAgentLifecycleService lifecycle;
    private RecruitAgentTool tool;
    private TenantQuotaService quotaService;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        handoffService = new HandoffService(jdbc, AgentTestFixtures.MAPPER, 0.5);
        dispatcher = new RuntimeToolDispatcher(store, handoffService, AgentTestFixtures.MAPPER, new AgentRegistryService(jdbc, AgentTestFixtures.MAPPER));
        lifecycle = new SubAgentLifecycleService(store, handoffService, AgentTestFixtures.MAPPER);
        tool = new RecruitAgentTool(AgentTestFixtures.MAPPER);
        quotaService = new TenantQuotaService(jdbc, lifecycle, true);
    }

    private record WaitingParent(String runId, String childRunId, String handoffId) {}

    /** 招募一个子代理，使父进入 WAITING/SUB_AGENT。 */
    private WaitingParent waitingParent() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, ZERO_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", rootInstanceId, runId);
        LeasedAgentRun leased = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);
        RuntimeToolDispatcher.DispatchOutcome outcome =
                dispatcher.dispatch(leased, tool, Map.of("goal", "long running child", "agent_key", "sense-collector"));
        assertThat(runStatus(runId)).isEqualTo("WAITING");
        return new WaitingParent(runId, outcome.childRunId(), outcome.handoffId());
    }

    private void backdateWaiting(String runId, Instant updatedAt) {
        jdbc.update("update agent_run set updated_at = ? where id = ?", Timestamp.from(updatedAt), runId);
    }

    @Test
    void waitingBeyondDefaultTimeoutIsCancelledWithCascade() {
        WaitingParent parent = waitingParent();
        backdateWaiting(parent.runId(), Instant.now().minusSeconds(86_400 + 60));

        TenantQuotaService.SweepResult result = quotaService.cancelTimedOutWaitingRuns(Instant.now());

        assertThat(result.cancelledRunIds()).containsExactly(parent.runId());
        assertThat(runStatus(parent.runId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select outcome from agent_run where id = ?",
                String.class, parent.runId())).isEqualTo("NOT_ACHIEVED");
        assertThat(jdbc.queryForObject("select final_result_json from agent_run where id = ?",
                String.class, parent.runId())).contains("WAIT_TIMEOUT");
        // 级联清理：未完成子 run 与 OPEN handoff 一并取消
        assertThat(runStatus(parent.childRunId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from handoff where id = ?",
                String.class, parent.handoffId())).isEqualTo("CANCELLED");
    }

    @Test
    void waitingWithinTimeoutIsLeftAlone() {
        WaitingParent parent = waitingParent();

        TenantQuotaService.SweepResult result = quotaService.cancelTimedOutWaitingRuns(Instant.now());

        assertThat(result.cancelledRunIds()).isEmpty();
        assertThat(runStatus(parent.runId())).isEqualTo("WAITING");
        assertThat(runStatus(parent.childRunId())).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select status from handoff where id = ?",
                String.class, parent.handoffId())).isEqualTo("OPEN");
    }

    @Test
    void tenantSpecificTimeoutOverridesDefault() {
        quotaService.upsertQuota(AgentTestDatabase.TENANT_ID, 8, 4, 60);
        WaitingParent parent = waitingParent();
        backdateWaiting(parent.runId(), Instant.now().minusSeconds(120));

        TenantQuotaService.SweepResult result = quotaService.cancelTimedOutWaitingRuns(Instant.now());

        assertThat(result.cancelledRunIds()).containsExactly(parent.runId());
        assertThat(runStatus(parent.runId())).isEqualTo("CANCELLED");
        assertThat(runStatus(parent.childRunId())).isEqualTo("CANCELLED");
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId);
    }
}
