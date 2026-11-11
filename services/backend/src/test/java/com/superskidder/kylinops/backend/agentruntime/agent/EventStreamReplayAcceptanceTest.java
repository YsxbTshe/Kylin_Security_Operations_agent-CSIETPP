package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEvent;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 7 迭代 7.1 验收基线 18.4（MCP、事件与部署）——SSE Last-Event-ID 回放的事件仓库切片。
 *
 * AgentRunController 的 SSE 端点以事件 sequence 作为 Last-Event-ID，断线重连后用
 * {@code eventsAfter(tenantId, runId, lastEventId, limit)} 分页续传。18.4 要求
 * 「SSE 使用 Last-Event-ID 完整回放，不重复/遗漏审批与终态事件」，其成立前提是事件仓库：
 *   - sequence 严格单调、无空洞（appendEvent 行级锁串行分配）；
 *   - eventsAfter 严格返回 sequence > cursor 的事件，按序分页；
 *   - cursor 追平后返回空集（不重复）。
 * 控制器端到端的 Last-Event-ID 回放由 ReadOnlyAgentRuntimeTest#sseReplaysStrictlyAfterLastEventIdFromPostgres 覆盖。
 */
class EventStreamReplayAcceptanceTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.restart"), "nodes", List.of("node-1"), "readOnly", false);
    private static final Map<String, Object> BUDGET = Map.of(
            "maxModelInvocations", 10, "maxToolInvocations", 20, "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000, "maxWallClockSeconds", 600);
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0);

    /** 模拟一条含审批与终态事件的 run 时间线。 */
    private static final List<String> EVENT_TYPES = List.of(
            "RUN_CREATED", "RUN_STARTED", "APPROVAL_CREATED", "APPROVAL_DECIDED",
            "TOOL_INVOCATION_QUEUED", "TOOL_INVOCATION_SUCCEEDED", "RUN_COMPLETED");

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        // 回放完整性与 run 是否终态无关；用 RUNNING 避免 V55 终态必须带 outcome 的检查约束。
        runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", GRANT, BUDGET, ZERO_USAGE);
        for (String eventType : EVENT_TYPES) {
            store.appendEvent(runId, AgentTestDatabase.TENANT_ID, eventType, "RUNTIME", "worker-test",
                    Map.of("type", eventType));
        }
    }

    /** 18.4：从任意 Last-Event-ID 分页续传，并集必须恰好等于全部事件——不重复、不遗漏。 */
    @Test
    void pagedReplayFromAnyCursorIsCompleteWithoutDuplicatesOrGaps() {
        // 模拟客户端在第 3 个事件后断线，以 Last-Event-ID=3 重连并逐页拉取。
        List<AgentRunEvent> replayed = new ArrayList<>();
        long cursor = 3;
        while (true) {
            List<AgentRunEvent> page = store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, cursor, 2);
            replayed.addAll(page);
            if (page.size() < 2) {
                break;
            }
            cursor = page.get(page.size() - 1).sequence();
        }

        assertThat(replayed).extracting(AgentRunEvent::sequence).containsExactly(4L, 5L, 6L, 7L);
        // 续传片段严格大于断线点，且审批与终态事件都在续传流内（不遗漏）。
        assertThat(replayed).extracting(AgentRunEvent::eventType).containsExactly(
                "APPROVAL_DECIDED", "TOOL_INVOCATION_QUEUED", "TOOL_INVOCATION_SUCCEEDED", "RUN_COMPLETED");
        // 从头完整回放：7 条事件一条不多一条不少，审批与终态事件都在流内。
        List<AgentRunEvent> full = store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, 0, 1_000);
        assertThat(full).extracting(AgentRunEvent::sequence).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(full).extracting(AgentRunEvent::eventType).containsExactlyElementsOf(EVENT_TYPES);
        // 两次回放拼接（断线前 1..3 + 续传 4..7）覆盖全流且无重复。
        List<Long> combined = new ArrayList<>(List.of(1L, 2L, 3L));
        replayed.forEach(event -> combined.add(event.sequence()));
        assertThat(combined).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(combined).doesNotHaveDuplicates();
        assertThat(store.verifyChain(AgentTestDatabase.TENANT_ID, runId).valid()).isTrue();
    }

    @Test
    void verifyChainDetectsTamperedEventHash() {
        assertThat(store.verifyChain(AgentTestDatabase.TENANT_ID, runId).valid()).isTrue();
        jdbc.update("update agent_run_event set event_hash = ? where run_id = ? and sequence_no = 1",
                "0".repeat(64), runId);
        var verification = store.verifyChain(AgentTestDatabase.TENANT_ID, runId);
        assertThat(verification.valid()).isFalse();
        assertThat(verification.reason()).isEqualTo("event_hash mismatch");
    }

    /** 18.4：客户端已追平（Last-Event-ID 指向最新事件）时重连不得重发任何事件。 */
    @Test
    void replayAtHeadSendsNothing() {
        long lastSequence = store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, 0, 1_000)
                .get(EVENT_TYPES.size() - 1).sequence();

        assertThat(store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, lastSequence, 100)).isEmpty();
        // 超出头部的非法大 cursor 同样必须为空（防重复）。
        assertThat(store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, lastSequence + 100, 100)).isEmpty();
    }

    /** 18.4：sequence 无空洞——回放完整性依赖 appendEvent 串行分配的单调序号。 */
    @Test
    void eventSequenceHasNoGapsAcrossConcurrentAppends() {
        String otherRunId = AgentTestFixtures.insertRun(jdbc, "RUNNING", GRANT, BUDGET, ZERO_USAGE);
        // 交错写入另一条 run 的事件，验证本 run 的序号不受影响且无空洞。
        for (int i = 0; i < 5; i++) {
            store.appendEvent(otherRunId, AgentTestDatabase.TENANT_ID, "RUN_HEARTBEAT", "RUNTIME", "worker-test",
                    Map.of("i", i));
            store.appendEvent(runId, AgentTestDatabase.TENANT_ID, "RUN_HEARTBEAT", "RUNTIME", "worker-test",
                    Map.of("i", i));
        }

        List<Long> sequences = store.eventsAfter(AgentTestDatabase.TENANT_ID, runId, 0, 1_000).stream()
                .map(AgentRunEvent::sequence).toList();
        assertThat(sequences).hasSize(EVENT_TYPES.size() + 5);
        for (int i = 1; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).as("no gap at index " + i).isEqualTo(sequences.get(i - 1) + 1);
        }
    }
}
