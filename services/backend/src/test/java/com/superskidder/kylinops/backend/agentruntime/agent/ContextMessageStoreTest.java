package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6.1 消息历史持久化与阶段压缩落库：写入→重建一致；压缩后原始消息不再重建。
 */
class ContextMessageStoreTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private LeasedAgentRun run;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        run = AgentTestFixtures.leasedRun(runId, instanceId, GRANT, BUDGET);
    }

    private Map<String, Object> assistantMessage(String callId) {
        return Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("id", callId, "type", "function",
                        "function", Map.of("name", "kylin.service.status", "arguments", "{\"service\":\"nginx\"}"))));
    }

    private Map<String, Object> toolMessage(String callId) {
        return Map.of("role", "tool", "tool_call_id", callId, "name", "kylin.service.status",
                "content", "{\"status\":\"SUCCEEDED\"}");
    }

    @Test
    void persistedMessagesRebuildInSequenceOrderWithSameContent() {
        Map<String, Object> assistant = assistantMessage("call-1");
        Map<String, Object> tool = toolMessage("call-1");
        store.appendRunMessage(run, 1, assistant);
        store.appendRunMessage(run, 2, tool);

        // 模拟进程重启/唤醒后从库内重建
        List<AgentRuntimeStore.RunMessage> rebuilt = store.loadActiveRunMessages(run.id());

        assertThat(rebuilt).hasSize(2);
        assertThat(rebuilt.get(0).sequenceNo()).isEqualTo(1);
        assertThat(rebuilt.get(1).sequenceNo()).isEqualTo(2);
        assertThat(rebuilt.get(0).message()).isEqualTo(assistant);
        assertThat(rebuilt.get(1).message()).isEqualTo(tool);
        assertThat(rebuilt).allSatisfy(message -> assertThat(message.tokenEstimate()).isPositive());
        assertThat(store.nextMessageSequence(run.id())).isEqualTo(3);
        assertThat(store.latestSummaryText(run.id())).isNull();
    }

    @Test
    void summaryMarksOriginalMessagesCompressedAndExcludesThemFromRebuild() {
        store.appendRunMessage(run, 1, assistantMessage("call-1"));
        store.appendRunMessage(run, 2, toolMessage("call-1"));
        store.appendRunMessage(run, 3, assistantMessage("call-2"));
        store.appendRunMessage(run, 4, toolMessage("call-2"));

        store.saveContextSummary(run, 5, "[compressed-history] 1) kylin.service.status -> SUCCEEDED",
                List.of(1L, 2L), 12, 40);

        List<AgentRuntimeStore.RunMessage> rebuilt = store.loadActiveRunMessages(run.id());
        assertThat(rebuilt).extracting(AgentRuntimeStore.RunMessage::sequenceNo).containsExactly(3L, 4L);
        assertThat(store.latestSummaryText(run.id()))
                .contains("kylin.service.status").contains("SUCCEEDED");
        Integer compressedCount = jdbc.queryForObject(
                "select count(*) from context_frame where run_id = ? and frame_kind = 'MESSAGE' and compressed = true",
                Integer.class, run.id());
        assertThat(compressedCount).isEqualTo(2);
        Integer summaryFrames = jdbc.queryForObject(
                "select count(*) from context_frame where run_id = ? and frame_kind = 'SUMMARY'",
                Integer.class, run.id());
        assertThat(summaryFrames).isEqualTo(1);
        List<String> events = jdbc.queryForList(
                "select event_type from agent_run_event where run_id = ? order by sequence_no",
                String.class, run.id());
        assertThat(events).contains("CONTEXT_SUMMARIZED");
    }

    @Test
    void compiledFramesKeepCompiledKindAndAreNeverReturnedAsMessages() {
        store.saveContextSummary(run, 1, "summary", List.of(), 5, 0);

        assertThat(store.loadActiveRunMessages(run.id())).isEmpty();
        assertThat(store.nextMessageSequence(run.id())).isEqualTo(1);
    }
}
