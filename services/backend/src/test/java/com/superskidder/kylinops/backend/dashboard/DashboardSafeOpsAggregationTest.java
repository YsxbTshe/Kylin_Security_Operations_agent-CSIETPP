package com.superskidder.kylinops.backend.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 大屏 SafeOps 聚合的真实 SQL 覆盖。
 *
 * <p>背景：{@code r5Blocked / rollbackRecords / activeResourceLocks / mcpToolTop / hostEvents}
 * 这五个字段在前端契约里一直存在，后端却从不返回，于是大屏默认落地页恒显示 0 / 空。
 * 补上聚合之后，这里用真实 H2 + 生产迁移把 SQL 跑一遍——{@link DashboardServiceTest}
 * 是纯逻辑单测（构造时 jdbcTemplate 传 null），一行 SQL 都不会执行，光靠它这些查询
 * 可以带着列名笔误一路进演示。
 */
class DashboardSafeOpsAggregationTest {

    private static final String TENANT = "org-alpha";

    private JdbcTemplate jdbc;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:dashboard-safeops-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            for (String migration : new String[]{
                    "V1__baseline.sql",
                    "V2__node_session_native_guard.sql",
                    "V3__perception_readonly_tools.sql",
                    "V4__honest_write_output_schema.sql",
                    "V5__agent_tool_abac_policies.sql",
                    "V6__agent_approval_abac_policies.sql",
                    "V7__user_must_change_password.sql",
                    "V12__node_session_native_guard_details.sql"}) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/" + migration));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("dashboard safeops test schema failed on H2", exception);
        }
        jdbc = new JdbcTemplate(dataSource);
        service = new DashboardService(jdbc, null, null, null, 100, 1000, 1);
    }

    @Test
    void aggregatesR5BlocksRollbacksLocksAndToolRanking() {
        insertRun("run-agg-1");
        // 两次 R5 硬拒（一次按 risk_code、一次按 rejection_code）+ 一次普通拒绝（不应计入）。
        insertInvocation("inv-r5-a", "run-agg-1", "kylin.service.restart", "R5", "REJECTED", "R5_FORBIDDEN");
        insertInvocation("inv-r5-b", "run-agg-1", "kylin.service.stop", "R3", "REJECTED", "R5_FORBIDDEN");
        insertInvocation("inv-abac", "run-agg-1", "kylin.service.start", "R3", "REJECTED", "ABAC_DENIED");
        // 一次成功回滚 + 一次失败回滚（失败不计入）。
        insertInvocation("inv-rb-ok", "run-agg-1", "kylin.cleanup.rollback", "R3", "SUCCEEDED", null);
        insertInvocation("inv-rb-bad", "run-agg-1", "kylin.cleanup.rollback", "R3", "FAILED", null);
        // 工具排行：disk.usage 3 次成功，process.list 1 次；被拒的不进排行。
        insertInvocation("inv-d1", "run-agg-1", "kylin.disk.usage", "R0", "SUCCEEDED", null);
        insertInvocation("inv-d2", "run-agg-1", "kylin.disk.usage", "R0", "SUCCEEDED", null);
        insertInvocation("inv-d3", "run-agg-1", "kylin.disk.usage", "R0", "SUCCEEDED", null);
        insertInvocation("inv-p1", "run-agg-1", "kylin.process.list", "R0", "SUCCEEDED", null);
        // 未过期锁 1 把 + 已过期锁 1 把（过期不计入）。
        insertLock("lock-live", Instant.now().plusSeconds(600));
        insertLock("lock-dead", Instant.now().minusSeconds(600));

        ScreenDashboardResponse.SafeOpsSummary safeOps = service.buildSafeOpsSummary(TENANT);

        assertThat(safeOps.r5Blocked()).isEqualTo(2);
        assertThat(safeOps.rollbackRecords()).isEqualTo(1);
        assertThat(safeOps.activeResourceLocks()).isEqualTo(1);
        // 排序口径 count desc, tool_id asc：disk.usage(3) > 并列 1 次的 cleanup.rollback / process.list。
        // 被拒的三条（inv-r5-a/b、inv-abac）与失败的 inv-rb-bad 都不进排行。
        assertThat(safeOps.mcpToolTop()).extracting(ScreenDashboardResponse.McpToolStat::toolName)
                .containsExactly("kylin.disk.usage", "kylin.cleanup.rollback", "kylin.process.list");
        assertThat(safeOps.mcpToolTop().get(0).count()).isEqualTo(3);
    }

    @Test
    void hostEventsReturnMostRecentFirstAndAreTenantScoped() {
        insertRun("run-evt-1");
        insertEvent("evt-old", "run-evt-1", 1, "RUN_STARTED", Instant.now().minusSeconds(300));
        insertEvent("evt-new", "run-evt-1", 2, "TOOL_SUCCEEDED", Instant.now());

        ScreenDashboardResponse.SafeOpsSummary safeOps = service.buildSafeOpsSummary(TENANT);

        assertThat(safeOps.hostEvents()).isNotEmpty();
        assertThat(safeOps.hostEvents().get(0).type()).isEqualTo("TOOL_SUCCEEDED");
        assertThat(safeOps.hostEvents().get(0).message()).contains("run-evt-1");
        // 另一个租户的事件不得串台。
        assertThat(service.buildSafeOpsSummary("org-beta").hostEvents())
                .noneMatch(item -> item.message().contains("run-evt-1"));
    }

    /**
     * 姿态心跳写入会话表，主机表对应列恒空。大屏必须联查会话，
     * 且不能把主机表上的陈旧值当成事实源。
     */
    @Test
    void nativeGuardBucketsReadSessionTableNotHostColumns() {
        insertHost("host-healthy", "node-healthy");
        insertHost("host-stale", "node-stale");
        insertHost("host-unknown", "node-unknown");
        insertHost("host-decoy", "node-decoy");
        // 主机表上故意写 CRITICAL，证明读取方不再看这列。
        jdbc.update("""
                update app_managed_host
                set native_guard_status = 'CRITICAL', native_guard_checked_at = ?
                where organization_id = ? and node_id = ?
                """, Timestamp.from(Instant.now()), TENANT, "node-healthy");
        insertSession("node-healthy", "HEALTHY", Instant.now(), 0, 0);
        insertSession("node-stale", "WARNING", Instant.now().minusSeconds(600), 0, 1);
        insertSession("node-decoy", "HEALTHY", Instant.now(), 1, 2);
        // 另一个租户的会话不得串台。
        jdbc.update("""
                insert into app_managed_host (organization_id, hostname, ip, status, os_version, node_id)
                values ('org-beta', 'host-beta', '10.0.0.9', 'ONLINE', 'Kylin', 'node-beta')
                """);
        insertSessionForTenant("org-beta", "node-beta", "CRITICAL", Instant.now(), 4, 0);

        ScreenDashboardResponse.NativeGuardStatus status =
                service.buildSafeOpsSummary(TENANT).nativeGuardStatus();

        assertThat(status.healthyHosts()).isEqualTo(2);
        assertThat(status.warningHosts()).isZero();
        assertThat(status.criticalHosts()).isZero();
        assertThat(status.unsupportedHosts()).isZero();
        assertThat(status.staleHosts()).isEqualTo(1);
        assertThat(status.unknownHosts()).isEqualTo(1);
        // stale 会话的探针不计入当前风险；另一租户的 4 条 critical 不得串台。
        assertThat(status.criticalProbes()).isEqualTo(1);
        assertThat(status.warningProbes()).isEqualTo(2);
    }

    /** 空库不得抛异常，且五个字段都要给出零值而非 null。 */
    @Test
    void emptyDatabaseYieldsZeroesNotNulls() {
        ScreenDashboardResponse.SafeOpsSummary safeOps = service.buildSafeOpsSummary(TENANT);

        assertThat(safeOps.r5Blocked()).isZero();
        assertThat(safeOps.rollbackRecords()).isZero();
        assertThat(safeOps.activeResourceLocks()).isZero();
        assertThat(safeOps.mcpToolTop()).isNotNull().isEmpty();
        assertThat(safeOps.hostEvents()).isNotNull().isEmpty();
    }

    private void insertRun(String runId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into agent_run (id, tenant_id, human_principal_id, root_agent_instance_id,
                    objective, execution_mode, target_scope_json, target_scope_digest,
                    grant_snapshot_json, status, input_revision, budget_json, usage_json,
                    created_at, updated_at)
                values (?, ?, 'user-admin', null, 'obj', 'CAUTIOUS', '{}', 'digest',
                    '{}', 'RUNNING', 0, '{}', '{}', ?, ?)
                """, runId, TENANT, now, now);
        // tool_invocation.agent_instance_id 非空且外键指向这里，故一并建根实例。
        jdbc.update("""
                insert into agent_instance (id, run_id, tenant_id, instance_name, avatar_seed,
                    definition_snapshot_json, objective, depth, status, reasoning_level,
                    grant_snapshot_json, grant_digest, budget_json, usage_json, created_at, updated_at)
                values (?, ?, ?, 'root', 'seed', '{}', 'obj', 0, 'RUNNING', 'LOW',
                    '{}', 'digest', '{}', '{}', ?, ?)
                """, instanceId(runId), runId, TENANT, now, now);
    }

    private static String instanceId(String runId) {
        return "inst-" + runId;
    }

    private void insertInvocation(String id, String runId, String toolId, String riskCode,
                                  String status, String rejectionCode) {
        jdbc.update("""
                insert into tool_invocation (id, run_id, tenant_id, agent_instance_id,
                    tool_package_version_id, tool_id, tool_version, executor, arguments_json,
                    arguments_digest, input_schema_digest, output_schema_digest, grant_digest,
                    risk_code, read_only, always_require_approval, idempotency_type, max_attempts,
                    status, rejection_code, input_revision, target_node_id, queued_at)
                values (?, ?, ?, ?, 'toolpkgver-cleanup-execute-v1', ?, '1.0.0', 'NODE', '{}',
                    'digest', 'digest', 'digest', 'digest', ?, false, false, 'NON_RETRYABLE', 1,
                    ?, ?, 0, 'node-1', ?)
                """, id, runId, TENANT, instanceId(runId), toolId, riskCode, status, rejectionCode,
                Timestamp.from(Instant.now()));
    }

    private void insertLock(String id, Instant expiresAt) {
        jdbc.update("""
                insert into resource_lock (id, tenant_id, resource_key, lock_mode, held_by_run_id,
                    lock_version, acquired_at, expires_at)
                values (?, ?, ?, 'WRITE_EXCLUSIVE', 'run-agg-1', 1, ?, ?)
                """, id, TENANT, "path:/tmp/" + id,
                // 约束 resource_lock_expiry_ck 要求 expires_at > acquired_at，
                // 已过期的锁必须把获取时刻也往前推。
                Timestamp.from(expiresAt.minusSeconds(60)), Timestamp.from(expiresAt));
    }

    private void insertHost(String hostname, String nodeId) {
        jdbc.update("""
                insert into app_managed_host (organization_id, hostname, ip, status, os_version, node_id)
                values (?, ?, '127.0.0.1', 'ONLINE', 'Kylin', ?)
                """, TENANT, hostname, nodeId);
    }

    private void insertSession(String nodeId, String status, Instant observedAt, int critical, int warning) {
        insertSessionForTenant(TENANT, nodeId, status, observedAt, critical, warning);
    }

    private void insertSessionForTenant(String tenantId, String nodeId, String status, Instant observedAt,
                                        int critical, int warning) {
        jdbc.update("""
                insert into agent_runtime_node_session (
                    tenant_id, node_id, protocol_version, capabilities_json,
                    last_control_sequence, last_node_sequence, connected, updated_at,
                    native_guard_status, native_guard_observed_at,
                    native_guard_critical_count, native_guard_warning_count
                ) values (?, ?, 2, '{}', 0, 0, true, ?, ?, ?, ?, ?)
                """, tenantId, nodeId, Timestamp.from(Instant.now()), status, Timestamp.from(observedAt),
                critical, warning);
    }

    private void insertEvent(String eventId, String runId, int sequenceNo, String eventType, Instant at) {
        jdbc.update("""
                insert into agent_run_event (event_id, run_id, tenant_id, sequence_no, event_type,
                    actor_type, actor_id, payload_json, prev_hash, event_hash, hash_algorithm, created_at)
                values (?, ?, ?, ?, ?, 'RUNTIME', 'worker', '{}', 'prev', ?, 'SM3', ?)
                """, eventId, runId, TENANT, sequenceNo, eventType, eventId + "-hash",
                Timestamp.from(at));
    }
}
