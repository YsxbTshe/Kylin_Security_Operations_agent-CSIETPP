package com.superskidder.kylinops.backend.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 遏制状态巡检：把 CONTAINED 从"无任何可达路径"变成"由处置任务的真实终态推进"。
 *
 * <p>{@code markContained} 此前有实现有单测但没有调用方，{@code contained_at} 恒空、
 * 大屏「遏制均时」恒空。本类验收补上的那一环，并钉死两条不可退让的性质：
 * 只有<b>成功完成</b>的处置任务才推进；{@code contained_at} 取 run 自己的 {@code completed_at}，
 * 因此度量不含巡检延迟。
 */
class IncidentContainmentReconcilerTest {

    private static final String TENANT = "org-alpha";
    private static final LocalDateTime RUN_COMPLETED_AT = LocalDateTime.of(2026, 8, 10, 10, 5, 0);

    private JdbcTemplate jdbc;
    private IncidentService incidentService;
    private IncidentContainmentReconciler reconciler;

    @BeforeEach
    void setUp() {
        jdbc = createDatabase();
        incidentService = new IncidentService(jdbc);
        reconciler = new IncidentContainmentReconciler(jdbc, incidentService, true);
    }

    @Test
    void successfulAgentRunAdvancesLinkedIncidentToContained() {
        String incidentId = openIncident();
        String runId = insertRun("COMPLETED", "SUCCEEDED", RUN_COMPLETED_AT);
        incidentService.beginTriage(TENANT, incidentId);
        incidentService.linkTask(TENANT, incidentId, runId);

        assertThat(reconciler.reconcile()).containsExactly(incidentId);

        IncidentDetail detail = incidentService.get(TENANT, incidentId);
        assertThat(detail.summary().status()).isEqualTo("CONTAINED");
        // 遏制时刻取 run 自己的 completed_at —— 大屏的遏制均时不含巡检轮询延迟。
        assertThat(jdbc.queryForObject("select contained_at from app_incident where id = ?",
                Timestamp.class, incidentId)).isEqualTo(Timestamp.valueOf(RUN_COMPLETED_AT));
        assertThat(detail.timeToContainSeconds()).isNotNull();
    }

    /** 处置没成功就不叫遏制：失败/取消/仍在跑的 run 一律不推进，宁可诚实空着。 */
    @Test
    void unsuccessfulOrUnfinishedRunsDoNotAdvanceTheIncident() {
        String failed = linkedIncident(insertRun("FAILED", "NOT_ACHIEVED", RUN_COMPLETED_AT));
        String cancelled = linkedIncident(insertRun("CANCELLED", "NOT_ACHIEVED", RUN_COMPLETED_AT));
        String running = linkedIncident(insertRun("RUNNING", null, null));

        assertThat(reconciler.reconcile()).isEmpty();

        for (String incidentId : List.of(failed, cancelled, running)) {
            assertThat(incidentService.get(TENANT, incidentId).summary().status()).isEqualTo("CONTAINING");
            assertThat(incidentService.get(TENANT, incidentId).containedAt()).isNull();
        }
    }

    /** 幂等：重复巡检不会重复推进，也不会把 contained_at 往后挪。 */
    @Test
    void reconcileIsIdempotent() {
        String incidentId = linkedIncident(insertRun("COMPLETED", "SUCCEEDED", RUN_COMPLETED_AT));

        assertThat(reconciler.reconcile()).containsExactly(incidentId);
        assertThat(reconciler.reconcile()).isEmpty();
        assertThat(jdbc.queryForObject("select contained_at from app_incident where id = ?",
                Timestamp.class, incidentId)).isEqualTo(Timestamp.valueOf(RUN_COMPLETED_AT));
    }

    /** 未关联处置任务的事件不受影响（task_id 为空不参与巡检）。 */
    @Test
    void incidentWithoutLinkedTaskIsUntouched() {
        String incidentId = openIncident();
        incidentService.beginTriage(TENANT, incidentId);

        assertThat(reconciler.reconcile()).isEmpty();
        assertThat(incidentService.get(TENANT, incidentId).summary().status()).isEqualTo("TRIAGED");
    }

    /** 开关关闭时巡检不动作（生产可按需停用，不影响其他链路）。 */
    @Test
    void disabledSweepDoesNothing() {
        String incidentId = linkedIncident(insertRun("COMPLETED", "SUCCEEDED", RUN_COMPLETED_AT));

        new IncidentContainmentReconciler(jdbc, incidentService, false).sweep();

        assertThat(incidentService.get(TENANT, incidentId).summary().status()).isEqualTo("CONTAINING");
    }

    private String linkedIncident(String runId) {
        String incidentId = openIncident();
        incidentService.beginTriage(TENANT, incidentId);
        incidentService.linkTask(TENANT, incidentId, runId);
        return incidentId;
    }

    private String openIncident() {
        String sourceIp = "203.0.113." + (1 + jdbc.queryForObject(
                "select count(1) from app_incident", Integer.class));
        return incidentService.intake(TENANT, new IncidentIntakeRequest(
                "sshd 认证异常", "HIGH", "AUTH_ANOMALY", sourceIp, "node-kylin-01",
                "SYNTHETIC", "疑似口令爆破", null, null, null)).incident().id();
    }

    private String insertRun(String status, String outcome, LocalDateTime completedAt) {
        String runId = "run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0, 0));
        jdbc.update("""
                insert into agent_run (
                    id, tenant_id, human_principal_id, objective, execution_mode,
                    target_scope_json, target_scope_digest, grant_version, grant_snapshot_json,
                    status, outcome, input_revision, budget_json, usage_json,
                    created_at, updated_at, completed_at
                ) values (?, ?, 'user-admin', '事件响应处置', 'CAUTIOUS', '{"nodeId":"node-kylin-01"}',
                    'digest-target', 1, '{}', ?, ?, 0, '{}', '{}', ?, ?, ?)
                """, runId, TENANT, status, outcome, now, now,
                completedAt == null ? null : Timestamp.valueOf(completedAt));
        return runId;
    }

    private static JdbcTemplate createDatabase() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:incident-contain-" + UUID.randomUUID()
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
            throw new IllegalStateException("incident containment test schema failed on H2", exception);
        }
        return new JdbcTemplate(dataSource);
    }
}
