package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.persistence.ResourceLockStore;
import com.superskidder.kylinops.backend.agentruntime.persistence.ToolExecutionRef;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 7 迭代 7.1 验收基线 18.3（正确性与恢复）——UNKNOWN/失败结果语义切片。
 *
 * 18.3 要求「UNKNOWN 不盲重试、不错误释放写锁」「服务端重启、Node 重连、消息重复和
 * 响应丢失不产生重复写副作用」。节点失联/响应丢失时 attempt 以失败终态落库，此时：
 *   - Runtime 不得自动创建第二个 attempt（不盲重试，重试决策属于 Agent/模型）；
 *   - 迟到且相互矛盾的结果不得翻转终态、不得产生重复事件（消息重复幂等）；
 *   - 资源写锁不得因失败被错误释放（只能由持锁 run 显式释放）。
 */
class UnknownResultAcceptanceTest {

    private static final Map<String, Object> GRANT = Map.of(
            "tools", List.of("kylin.service.stop"), "nodes", List.of("node-1"), "readOnly", false);
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
        // resource_lock / agent_runtime_node_lock_version / tool_invocation.direct_display
        // 均已包含在 AgentTestDatabase 执行的新基线 V1__baseline.sql 中，无需再单独建表。
        jdbc = AgentTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, AgentTestFixtures.MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        seedToolPackage();
        String runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", GRANT, BUDGET, ZERO_USAGE);
        String instanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        jdbc.update("update agent_run set root_agent_instance_id = ? where id = ?", instanceId, runId);
        run = AgentTestFixtures.leasedRun(runId, instanceId, GRANT, BUDGET);
    }

    /** tool_invocation.tool_package_version_id 外键需要的最小目录种子。 */
    private void seedToolPackage() {
        java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
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

    private NodeTool writeTool() {
        NodeTool tool = mock(NodeTool.class);
        when(tool.toolId()).thenReturn("kylin.service.stop");
        when(tool.version()).thenReturn("1.0.0");
        when(tool.packageVersionId()).thenReturn("tpv-kylin-service-1");
        when(tool.readOnly()).thenReturn(false);
        when(tool.riskFloor()).thenReturn("R2");
        when(tool.alwaysRequireApproval()).thenReturn(false);
        when(tool.idempotency()).thenReturn(ToolIdempotency.AT_MOST_ONCE);
        when(tool.maxAttempts()).thenReturn(1);
        when(tool.inputSchemaDigest()).thenReturn("digest-in");
        when(tool.outputSchemaDigest()).thenReturn("digest-out");
        when(tool.catalogDigest()).thenReturn("digest-catalog");
        return tool;
    }

    private ToolExecutionRef startWriteAttempt() {
        // agent_invocation_id 可空且不属于本切片验收面（attempt/锁语义才是），置空避免外键种子。
        return store.startToolInvocation(run, null, "node-1", writeTool(),
                "{\"service\":\"nginx\"}", List.of(), 3L);
    }

    /** 18.3：失败/UNKNOWN 结果落终态后，不得自动创建第二个 attempt（不盲重试）。 */
    @Test
    void failedOrUnknownResultIsNeverBlindlyRetried() {
        ToolExecutionRef ref = startWriteAttempt();
        store.markAttemptSent(ref.executionAttemptId(), 7L);

        store.recordNodeResult(ref.executionAttemptId(), false, null, "NODE_LOST",
                "node connection lost mid-execution", true);

        assertThat(jdbc.queryForObject("select status from execution_attempt where id = ?",
                String.class, ref.executionAttemptId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from tool_invocation where id = ?",
                String.class, ref.toolInvocationId())).isEqualTo("FAILED");
        // 不盲重试：失败后仍然只有一个 attempt，没有 attempt_no=2。
        assertThat(jdbc.queryForObject("select count(*) from execution_attempt where tool_invocation_id = ?",
                Integer.class, ref.toolInvocationId())).isEqualTo(1);
    }

    /** 18.3：迟到的矛盾结果不得翻转终态，也不得产生重复事件（消息重复幂等）。 */
    @Test
    void duplicateLateResultCannotFlipTerminalState() {
        ToolExecutionRef ref = startWriteAttempt();
        store.markAttemptSent(ref.executionAttemptId(), 7L);
        store.recordNodeResult(ref.executionAttemptId(), false, null, "NODE_LOST", "lost", true);

        // 重放一条声称成功的迟到结果——必须被终态守卫忽略。
        store.recordNodeResult(ref.executionAttemptId(), true, "{\"stopped\":true}", null, null, false);

        assertThat(jdbc.queryForObject("select status from execution_attempt where id = ?",
                String.class, ref.executionAttemptId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from tool_invocation where id = ?",
                String.class, ref.toolInvocationId())).isEqualTo("FAILED");
        List<String> events = store.eventsAfter(AgentTestDatabase.TENANT_ID, run.id(), 0, 100).stream()
                .map(event -> event.eventType()).toList();
        assertThat(events).doesNotContain("TOOL_INVOCATION_SUCCEEDED");
        assertThat(events.stream().filter(type -> type.equals("TOOL_INVOCATION_FAILED")).count()).isEqualTo(1);
    }

    /** 18.3：失败不得错误释放写锁；只有持锁 run 的显式释放才能解锁，其他 run 一律被拒。 */
    @Test
    void failureNeverReleasesWriteLockAndOtherRunsStayExcluded() {
        ResourceLockStore lockStore = new ResourceLockStore(jdbc);
        ToolExecutionRef ref = startWriteAttempt();
        long version = lockStore.acquireWriteLock(AgentTestDatabase.TENANT_ID, "service:nginx",
                run.id(), ref.executionAttemptId());
        assertThat(version).isPositive();

        // 执行以失败告终（UNKNOWN/响应丢失语义）——锁必须仍然在持。
        store.recordNodeResult(ref.executionAttemptId(), false, null, "NODE_LOST", "lost", true);
        assertThat(jdbc.queryForObject(
                "select held_by_run_id from resource_lock where tenant_id = ? and resource_key = ?",
                String.class, AgentTestDatabase.TENANT_ID, "service:nginx")).isEqualTo(run.id());

        // 其他 run 不得趁失败抢锁。
        assertThatThrownBy(() -> lockStore.acquireWriteLock(
                AgentTestDatabase.TENANT_ID, "service:nginx", "run-intruder", "attempt-intruder"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource is locked");

        // 持锁 run 重入（同一 run 内续写）允许且版本不变；显式释放才可解锁。
        assertThat(lockStore.acquireWriteLock(
                AgentTestDatabase.TENANT_ID, "service:nginx", run.id(), ref.executionAttemptId()))
                .isEqualTo(version);
        lockStore.releaseLock(AgentTestDatabase.TENANT_ID, "service:nginx", run.id());
        assertThat(jdbc.queryForObject("select count(*) from resource_lock where tenant_id = ?",
                Integer.class, AgentTestDatabase.TENANT_ID)).isZero();
    }
}
