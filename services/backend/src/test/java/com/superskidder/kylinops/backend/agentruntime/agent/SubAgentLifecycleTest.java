package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.superskidder.kylinops.backend.registry.AgentRegistryService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 5.3 子 Agent 完成闭环：终态唤醒、取消传播、预算归还、artifact 汇总与安全红线。
 */
class SubAgentLifecycleTest {

    private static final Map<String, Object> PARENT_GRANT = Map.of(
            "tools", List.of("kylin.service.status", "kylin.workspace.read"),
            "nodes", List.of("node-1"),
            "readOnly", false
    );
    private static final Map<String, Object> PARENT_BUDGET = Map.of(
            "maxModelInvocations", 10,
            "maxToolInvocations", 20,
            "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000,
            "maxWallClockSeconds", 600
    );
    private static final Map<String, Object> ZERO_USAGE = Map.of(
            "modelInvocations", 0, "toolInvocations", 0, "inputTokens", 0, "outputTokens", 0
    );
    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("summary"),
            "properties", Map.of("summary", Map.of("type", "string"))
    );

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private HandoffService handoffService;
    private RuntimeToolDispatcher dispatcher;
    private SubAgentLifecycleService lifecycle;
    private RecruitAgentTool tool;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        handoffService = new HandoffService(jdbc, AgentTestFixtures.MAPPER, 0.5);
        dispatcher = new RuntimeToolDispatcher(store, handoffService, AgentTestFixtures.MAPPER, new AgentRegistryService(jdbc, AgentTestFixtures.MAPPER));
        lifecycle = new SubAgentLifecycleService(store, handoffService, AgentTestFixtures.MAPPER);
        tool = new RecruitAgentTool(AgentTestFixtures.MAPPER);
    }

    // --- 子成功闭环 ---

    @Test
    void childCompletionCompletesHandoffWakesParentAndRefundsUnusedBudget() {
        Parent parent = newParent();
        RuntimeToolDispatcher.DispatchOutcome outcome = recruit(parent, Map.of("goal", "triage disk"));
        // 招募预扣子预算（10/20/10000/5000/600 的 0.5 → 5/10/5000/2500/300）
        assertBudget(budgetOf(parent.runId()), "maxModelInvocations", 5);
        assertThat(parentStatus(parent.runId())).isEqualTo("WAITING");

        LeasedAgentRun childRun = store.leaseSpecific(outcome.childRunId());
        setUsage(outcome.childRunId(), Map.of(
                "modelInvocations", 2, "toolInvocations", 3, "inputTokens", 1_000, "outputTokens", 500));
        store.completeRun(childRun, Map.of("summary", "disk triaged"), 0);

        lifecycle.onRunTerminal(childRun, true, null, null);

        assertThat(handoffStatus(outcome.handoffId())).isEqualTo("COMPLETED");
        assertThat(handoffResult(outcome.handoffId())).contains("disk triaged");
        assertThat(parentStatus(parent.runId())).isEqualTo("QUEUED");
        // 只消耗子实际用量：父预算 = 初始 - 子用量，绝不放大
        Map<String, Object> parentBudget = budgetOf(parent.runId());
        assertBudget(parentBudget, "maxModelInvocations", 8);
        assertBudget(parentBudget, "maxToolInvocations", 17);
        assertBudget(parentBudget, "maxInputTokens", 9_000);
        assertBudget(parentBudget, "maxOutputTokens", 4_500);
        assertBudget(parentBudget, "maxWallClockSeconds", 600);
        assertThat(parentEventTypes(parent.runId())).contains("SUB_AGENT_RESULT", "RUN_WAKEUP");
    }

    // --- 子失败闭环 ---

    @Test
    void childFailureRecordsStructuredErrorAndRefundsUnusedBudget() {
        Parent parent = newParent();
        RuntimeToolDispatcher.DispatchOutcome outcome = recruit(parent, Map.of("goal", "collect diagnostics"));

        LeasedAgentRun childRun = store.leaseSpecific(outcome.childRunId());
        setUsage(outcome.childRunId(), Map.of(
                "modelInvocations", 1, "toolInvocations", 1, "inputTokens", 500, "outputTokens", 250));
        store.failRun(childRun, "NODE_UNREACHABLE", "node went away");

        lifecycle.onRunTerminal(childRun, false, "NODE_UNREACHABLE", "node went away");

        assertThat(handoffStatus(outcome.handoffId())).isEqualTo("FAILED");
        assertThat(handoffResult(outcome.handoffId())).contains("NODE_UNREACHABLE");
        assertThat(parentStatus(parent.runId())).isEqualTo("QUEUED");
        Map<String, Object> parentBudget = budgetOf(parent.runId());
        assertBudget(parentBudget, "maxModelInvocations", 9);
        assertBudget(parentBudget, "maxToolInvocations", 19);
        assertBudget(parentBudget, "maxInputTokens", 9_500);
        assertBudget(parentBudget, "maxOutputTokens", 4_750);
    }

    // --- 并发子：全部完成才唤醒 ---

    @Test
    void parentStaysWaitingUntilAllConcurrentChildrenFinish() {
        Parent parent = newParent();
        RuntimeToolDispatcher.DispatchOutcome first = recruit(parent, Map.of("goal", "task A"));
        RuntimeToolDispatcher.DispatchOutcome second = recruit(parent, Map.of("goal", "task B"));

        LeasedAgentRun firstRun = store.leaseSpecific(first.childRunId());
        store.completeRun(firstRun, Map.of("summary", "A done"), 0);
        lifecycle.onRunTerminal(firstRun, true, null, null);

        // 第二个子仍 QUEUED → 父保持 WAITING
        assertThat(handoffStatus(first.handoffId())).isEqualTo("COMPLETED");
        assertThat(parentStatus(parent.runId())).isEqualTo("WAITING");
        assertThat(parentEventTypes(parent.runId())).doesNotContain("RUN_WAKEUP");

        LeasedAgentRun secondRun = store.leaseSpecific(second.childRunId());
        store.completeRun(secondRun, Map.of("summary", "B done"), 0);
        lifecycle.onRunTerminal(secondRun, true, null, null);

        assertThat(parentStatus(parent.runId())).isEqualTo("QUEUED");
        assertThat(parentEventTypes(parent.runId())).contains("RUN_WAKEUP");
    }

    // --- 取消传播 ---

    @Test
    void parentCancelCascadesToChildrenAndHandoffs() {
        Parent parent = newParent();
        RuntimeToolDispatcher.DispatchOutcome first = recruit(parent, Map.of("goal", "task A"));
        RuntimeToolDispatcher.DispatchOutcome second = recruit(parent, Map.of("goal", "task B"));

        SubAgentLifecycleService.CancelReport report =
                lifecycle.cancelRun(parent.runId(), "HUMAN", AgentTestDatabase.USER_ID, "user aborted");

        assertThat(report.cancelledRunIds()).contains(first.childRunId(), second.childRunId(), parent.runId());
        assertThat(report.cancelledHandoffIds()).contains(first.handoffId(), second.handoffId());
        assertThat(runStatus(parent.runId())).isEqualTo("CANCELLED");
        assertThat(runStatus(first.childRunId())).isEqualTo("CANCELLED");
        assertThat(runStatus(second.childRunId())).isEqualTo("CANCELLED");
        assertThat(handoffStatus(first.handoffId())).isEqualTo("CANCELLED");
        assertThat(handoffStatus(second.handoffId())).isEqualTo("CANCELLED");
        assertThat(outcome(parent.runId())).isEqualTo("NOT_ACHIEVED");
    }

    @Test
    void cancellingSingleChildWakesParentAndRefundsBudget() {
        Parent parent = newParent();
        RuntimeToolDispatcher.DispatchOutcome outcome = recruit(parent, Map.of("goal", "solo task"));

        lifecycle.cancelRun(outcome.childRunId(), "HUMAN", AgentTestDatabase.USER_ID, "cancel child");

        assertThat(handoffStatus(outcome.handoffId())).isEqualTo("CANCELLED");
        assertThat(runStatus(outcome.childRunId())).isEqualTo("CANCELLED");
        assertThat(parentStatus(parent.runId())).isEqualTo("QUEUED");
        // 子未消耗，预算全额归还 → 父预算回到初始，不放大
        Map<String, Object> parentBudget = budgetOf(parent.runId());
        assertBudget(parentBudget, "maxModelInvocations", 10);
        assertBudget(parentBudget, "maxToolInvocations", 20);
    }

    // --- 安全红线：结果注入 ---

    @Test
    void schemaViolationYieldsStructuredFailureNotDirtyData() {
        Parent parent = newParent();
        RuntimeToolDispatcher.DispatchOutcome outcome = recruit(parent, Map.of(
                "goal", "guarded task", "output_schema", OUTPUT_SCHEMA));

        LeasedAgentRun childRun = store.leaseSpecific(outcome.childRunId());
        // 子结果缺少 output_schema 要求的 summary 字段
        store.completeRun(childRun, Map.of("leaked", "raw internal payload"), 0);
        lifecycle.onRunTerminal(childRun, true, null, null);

        assertThat(handoffStatus(outcome.handoffId())).isEqualTo("FAILED");
        String result = handoffResult(outcome.handoffId());
        assertThat(result).contains("SUB_AGENT_OUTPUT_SCHEMA_VIOLATION");
        assertThat(result).doesNotContain("raw internal payload");
        // 父被唤醒并能看到结构化失败，而非脏数据
        assertThat(parentStatus(parent.runId())).isEqualTo("QUEUED");
        assertThat(latestWakeContextManifest(parent)).contains("SUB_AGENT_OUTPUT_SCHEMA_VIOLATION")
                .doesNotContain("raw internal payload");
    }

    // --- 安全红线：父上下文隔离 ---

    @Test
    void childCreationCarriesOnlyGoalAndHandoffNotParentContext() {
        Parent parent = newParent();
        // 父已有一帧上下文（模拟父执行中）
        jdbc.update("""
                insert into context_frame (id, run_id, tenant_id, agent_instance_id, input_revision,
                    stable_prefix_digest, dynamic_suffix_digest, context_digest, segment_manifest_json,
                    provenance_json, token_estimate, created_at)
                values ('ctx-parent-1', ?, ?, ?, 0, 'p1', 'p2', 'p3', '{"secret":"parent-context"}', '[]', 10, ?)
                """, parent.runId(), AgentTestDatabase.TENANT_ID, parent.rootInstanceId(),
                Timestamp.from(Instant.now()));

        RuntimeToolDispatcher.DispatchOutcome outcome = recruit(parent, Map.of("goal", "isolated task"));

        // 子 run 目标只有 goal；handoff 上下文摘要只有 goal，不携带父 context_frame
        String childObjective = jdbc.queryForObject(
                "select objective from agent_run where id = ?", String.class, outcome.childRunId());
        assertThat(childObjective).isEqualTo("isolated task");
        Map<String, Object> handoffRow = jdbc.queryForMap(
                "select context_summary, input_artifact_refs_json from handoff where id = ?", outcome.handoffId());
        assertThat(handoffRow.get("context_summary")).isEqualTo("isolated task");
        assertThat(handoffRow.get("input_artifact_refs_json")).isEqualTo("[]");
        // 子实例的上下文帧在创建时不存在（不会复制父帧）
        Integer childFrames = jdbc.queryForObject(
                "select count(*) from context_frame where agent_instance_id = ?",
                Integer.class, outcome.agentInstanceId());
        assertThat(childFrames).isZero();
    }

    // --- 安全红线：预算不放大（失败路径） ---

    @Test
    void failedChildNeverAmplifiesParentBudget() {
        Parent parent = newParent();
        Map<String, Object> original = budgetOf(parent.runId());
        RuntimeToolDispatcher.DispatchOutcome outcome = recruit(parent, Map.of("goal", "will fail"));

        LeasedAgentRun childRun = store.leaseSpecific(outcome.childRunId());
        store.failRun(childRun, "AGENT_RUNTIME_FAILED", "boom");
        lifecycle.onRunTerminal(childRun, false, "AGENT_RUNTIME_FAILED", "boom");

        Map<String, Object> refunded = budgetOf(parent.runId());
        for (String dimension : List.of("maxModelInvocations", "maxToolInvocations",
                "maxInputTokens", "maxOutputTokens", "maxWallClockSeconds")) {
            assertThat(((Number) refunded.get(dimension)).longValue())
                    .as(dimension).isLessThanOrEqualTo(((Number) original.get(dimension)).longValue());
        }
    }

    // --- 证据汇总 ---

    @Test
    void childArtifactsAreSummarizedAsRefsIntoParentContext() {
        Parent parent = newParent();
        RuntimeToolDispatcher.DispatchOutcome outcome = recruit(parent, Map.of("goal", "produce evidence"));
        insertArtifact("art-1", outcome.agentInstanceId(), "report.txt", "a".repeat(64));
        insertArtifact("art-2", outcome.agentInstanceId(), "trace.log", "b".repeat(64));

        LeasedAgentRun childRun = store.leaseSpecific(outcome.childRunId());
        store.completeRun(childRun, Map.of("summary", "evidence gathered"), 0);
        lifecycle.onRunTerminal(childRun, true, null, null);

        String manifest = latestWakeContextManifest(parent);
        assertThat(manifest)
                .contains("art-1").contains("report.txt").contains("a".repeat(64))
                .contains("art-2").contains("trace.log").contains("b".repeat(64));
        // 只传引用：父上下文帧不落入 artifact 内容本身
        assertThat(manifest).doesNotContain("storage_path");
    }

    // --- helpers ---

    private record Parent(String runId, String rootInstanceId, LeasedAgentRun leased) {}

    private Parent newParent() {
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, ZERO_USAGE);
        String rootInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        // 与生产 createWriteRun 一致：实例创建后回填 run 的 root_agent_instance_id
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", rootInstanceId, runId);
        LeasedAgentRun leased = AgentTestFixtures.leasedRun(runId, rootInstanceId, PARENT_GRANT, PARENT_BUDGET);
        return new Parent(runId, rootInstanceId, leased);
    }

    /**
     * 招募现在必须声明一个已注册且启用的专家（agent_key 必填 + 对注册表校验）。
     * 这些用例关心的是子 Agent 生命周期而不是身份校验，所以在此统一补一个 V1 种子里
     * 就有的专家 key；专门校验身份的用例在 RecruitAgentToolTest / 注册表封套测试里。
     */
    private RuntimeToolDispatcher.DispatchOutcome recruit(Parent parent, Map<String, Object> arguments) {
        Map<String, Object> withExpert = new java.util.LinkedHashMap<>(arguments);
        withExpert.putIfAbsent("agent_key", "sense-collector");
        return dispatcher.dispatch(parent.leased(), tool, withExpert);
    }

    private void insertArtifact(String id, String agentInstanceId, String name, String sm3) {
        jdbc.update("""
                insert into agent_artifact (id, tenant_id, agent_instance_id, name, content_type,
                    size_bytes, sm3_hex, status, storage_path, created_at, published_at, updated_at)
                values (?, ?, ?, ?, 'text/plain', 10, ?, 'PUBLISHED', '/tmp/' || ?, ?, ?, ?)
                """, id, AgentTestDatabase.TENANT_ID, agentInstanceId, name, sm3, id,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    private void setUsage(String runId, Map<String, Object> usage) {
        jdbc.update("update agent_run set usage_json = ? where id = ?",
                AgentTestFixtures.json(usage), runId);
    }

    private Map<String, Object> budgetOf(String runId) {
        String json = jdbc.queryForObject("select budget_json from agent_run where id = ?", String.class, runId);
        return readMap(json);
    }

    private void assertBudget(Map<String, Object> budget, String dimension, long expected) {
        assertThat(((Number) budget.get(dimension)).longValue()).as(dimension).isEqualTo(expected);
    }

    private String parentStatus(String runId) {
        return runStatus(runId);
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("select status from agent_run where id = ?", String.class, runId);
    }

    private String outcome(String runId) {
        return jdbc.queryForObject("select outcome from agent_run where id = ?", String.class, runId);
    }

    private String handoffStatus(String handoffId) {
        return jdbc.queryForObject("select status from handoff where id = ?", String.class, handoffId);
    }

    private String handoffResult(String handoffId) {
        return jdbc.queryForObject("select result_json from handoff where id = ?", String.class, handoffId);
    }

    private List<String> parentEventTypes(String runId) {
        return jdbc.queryForList("select event_type from agent_run_event where run_id = ? order by sequence_no",
                String.class, runId);
    }

    /** 父最近一帧 sub-agent-result 上下文帧的 segment_manifest。 */
    private String latestWakeContextManifest(Parent parent) {
        return jdbc.queryForObject("""
                select segment_manifest_json from context_frame
                where run_id = ? and segment_manifest_json like '%sub-agent-result%'
                order by created_at desc limit 1
                """, String.class, parent.runId());
    }

    private Map<String, Object> readMap(String json) {
        try {
            return AgentTestFixtures.MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
