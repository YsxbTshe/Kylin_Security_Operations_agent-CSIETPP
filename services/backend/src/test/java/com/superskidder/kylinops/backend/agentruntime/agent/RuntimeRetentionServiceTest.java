package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.agentruntime.checkpoint.RunCheckpointService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.retention.RuntimeRetentionService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6.2 数据留存：已终态超过保留期只清过程数据（事件/消息帧/检查点），
 * agent_run 主记录与审计类表保留；未过期或非终态数据不动。
 */
class RuntimeRetentionServiceTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private RunCheckpointService checkpoints;
    private RuntimeRetentionService retention;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        checkpoints = new RunCheckpointService(jdbc, AgentTestFixtures.MAPPER);
        retention = new RuntimeRetentionService(jdbc, true, 90);
    }

    private record SeededRun(String runId, String instanceId) {}

    private SeededRun seedTerminalRun(Instant completedAt) {
        String runId = AgentTestFixtures.insertRun(jdbc, "QUEUED", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "COMPLETED");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        jdbc.update("""
                update agent_run set status = 'COMPLETED', outcome = 'SUCCEEDED',
                    completed_at = ?, started_at = ? where id = ?
                """, Timestamp.from(completedAt), Timestamp.from(completedAt.minusSeconds(60)), runId);
        return new SeededRun(runId, instanceId);
    }

    /** 为一个 run 挂上过程数据：事件、MESSAGE 帧、COMPILED 帧、检查点。 */
    private void attachProcessData(SeededRun run) {
        store.appendEvent(run.runId(), AgentTestDatabase.TENANT_ID, "RUN_CREATED", "HUMAN",
                AgentTestDatabase.USER_ID, Map.of("objective", "fixture"));
        store.appendEvent(run.runId(), AgentTestDatabase.TENANT_ID, "RUN_COMPLETED", "RUNTIME",
                "worker-test", Map.of("outcome", "SUCCEEDED"));
        LeasedAgentRun leased = AgentTestFixtures.leasedRun(run.runId(), run.instanceId(), GRANT, BUDGET);
        store.appendRunMessage(leased, 1, Map.of("role", "assistant", "content", "round-1"));
        store.appendRunMessage(leased, 2, Map.of("role", "tool", "tool_call_id", "call-1",
                "name", "kylin.service.status", "content", "{\"status\":\"SUCCEEDED\"}"));
        jdbc.update("""
                insert into context_frame (id, run_id, tenant_id, agent_instance_id, input_revision,
                    stable_prefix_digest, dynamic_suffix_digest, context_digest, segment_manifest_json,
                    provenance_json, token_estimate, created_at)
                values (?, ?, ?, ?, 0, 'p1', 'p2', 'p3', '{"kind":"compiled"}', '[]', 10, ?)
                """, "ctx-" + run.runId(), run.runId(), AgentTestDatabase.TENANT_ID, run.instanceId(),
                Timestamp.from(Instant.now()));
        checkpoints.save(leased, 3, "tool-1");
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Test
    void expiredTerminalRunLosesProcessDataButKeepsMainRecordAndAuditFrames() {
        SeededRun expired = seedTerminalRun(Instant.now().minusSeconds(91L * 86_400));
        attachProcessData(expired);
        SeededRun recent = seedTerminalRun(Instant.now().minusSeconds(10L * 86_400));
        attachProcessData(recent);

        RuntimeRetentionService.CleanupResult result = retention.cleanup(Instant.now());

        assertThat(result.eventsDeleted()).isEqualTo(2);
        assertThat(result.messageFramesDeleted()).isEqualTo(2);
        assertThat(result.checkpointsDeleted()).isEqualTo(1);

        // 过期 run：过程数据清空
        assertThat(count("select count(*) from agent_run_event where run_id = ?", expired.runId())).isZero();
        assertThat(count("select count(*) from context_frame where run_id = ? and frame_kind = 'MESSAGE'",
                expired.runId())).isZero();
        assertThat(checkpoints.load(expired.runId())).isEmpty();
        // 主记录保留；COMPILED 帧（上下文审计帧）保留
        assertThat(count("select count(*) from agent_run where id = ?", expired.runId())).isEqualTo(1);
        assertThat(count("select count(*) from context_frame where run_id = ? and frame_kind = 'COMPILED'",
                expired.runId())).isEqualTo(1);

        // 未过期 run：全部保留
        assertThat(count("select count(*) from agent_run_event where run_id = ?", recent.runId())).isEqualTo(2);
        assertThat(count("select count(*) from context_frame where run_id = ? and frame_kind = 'MESSAGE'",
                recent.runId())).isEqualTo(2);
        assertThat(checkpoints.load(recent.runId())).isPresent();
    }

    @Test
    void nonTerminalRunsAreNeverTouchedEvenWhenOld() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        jdbc.update("update agent_run set started_at = ?, updated_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(400L * 86_400)),
                Timestamp.from(Instant.now().minusSeconds(400L * 86_400)), runId);
        SeededRun run = new SeededRun(runId, instanceId);
        attachProcessData(run);

        RuntimeRetentionService.CleanupResult result = retention.cleanup(Instant.now());

        assertThat(result.eventsDeleted()).isZero();
        assertThat(result.messageFramesDeleted()).isZero();
        assertThat(result.checkpointsDeleted()).isZero();
        assertThat(count("select count(*) from agent_run_event where run_id = ?", runId)).isEqualTo(2);
        assertThat(count("select count(*) from context_frame where run_id = ? and frame_kind = 'MESSAGE'",
                runId)).isEqualTo(2);
        assertThat(checkpoints.load(runId)).isPresent();
    }
}
