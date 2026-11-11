package com.superskidder.kylinops.backend.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 主机列表/详情的 Native Guard 总姿态必须读会话表：
 * 心跳只写 {@code agent_runtime_node_session}，主机表对应列没有写入方。
 */
class HostServiceNativeGuardReadTest {

    private static final String TENANT = "org-alpha";

    private JdbcTemplate jdbc;
    private HostService hostService;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:host-native-guard-" + UUID.randomUUID()
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
            throw new IllegalStateException("host native-guard test schema failed on H2", exception);
        }
        jdbc = new JdbcTemplate(dataSource);
        hostService = new HostService(jdbc, 180, new ObjectMapper(), null, 600);
    }

    @Test
    void listHostsReadsPostureFromNodeSessionNotHostColumns() {
        insertHost("kylin-01", "node-kylin-01");
        insertHost("kylin-02", "node-kylin-02");
        jdbc.update("""
                update app_managed_host
                set native_guard_status = 'CRITICAL', native_guard_checked_at = ?
                where organization_id = ? and node_id = ?
                """, Timestamp.from(Instant.now()), TENANT, "node-kylin-01");
        String postureJson = """
                {"overall":"warning","items":[\
                {"tool":"kylin.security.auditd_status","supported":true,"status":"inactive","message":"auditd down"},\
                {"tool":"kylin.security.selinux_status","supported":true,"status":"permissive","message":"permissive"}\
                ]}
                """;
        insertSession("node-kylin-01", "WARNING", Instant.now(), postureJson, 1, 1);

        HostSummary reported = hostService.getHost(TENANT, "kylin-01");
        HostSummary unseen = hostService.getHost(TENANT, "kylin-02");

        assertThat(reported.nativeGuardStatus()).isEqualTo("WARNING");
        assertThat(reported.nativeGuardCheckedAt()).isNotBlank();
        assertThat(reported.nativeGuardCriticalCount()).isEqualTo(1);
        assertThat(reported.nativeGuardWarningCount()).isEqualTo(1);
        assertThat(reported.nativeGuardJson()).contains("kylin.security.auditd_status");
        assertThat(unseen.nativeGuardStatus()).isEqualTo("UNKNOWN");
        assertThat(unseen.nativeGuardCheckedAt()).isEmpty();
        assertThat(unseen.nativeGuardJson()).isEmpty();
    }

    @Test
    void listHostsMarksStaleSessionPosture() {
        insertHost("kylin-stale", "node-stale");
        insertSession("node-stale", "WARNING", Instant.now().minusSeconds(600), null, 0, 1);

        assertThat(hostService.getHost(TENANT, "kylin-stale").nativeGuardStatus()).isEqualTo("STALE");
    }

    private void insertHost(String hostname, String nodeId) {
        jdbc.update("""
                insert into app_managed_host (organization_id, hostname, ip, status, os_version, node_id)
                values (?, ?, '127.0.0.1', 'ONLINE', 'Kylin', ?)
                """, TENANT, hostname, nodeId);
    }

    private void insertSession(String nodeId, String status, Instant observedAt,
                              String json, int critical, int warning) {
        jdbc.update("""
                insert into agent_runtime_node_session (
                    tenant_id, node_id, protocol_version, capabilities_json,
                    last_control_sequence, last_node_sequence, connected, updated_at,
                    native_guard_status, native_guard_observed_at,
                    native_guard_json, native_guard_critical_count, native_guard_warning_count
                ) values (?, ?, 2, '{}', 0, 0, true, ?, ?, ?, ?, ?, ?)
                """, TENANT, nodeId, Timestamp.from(Instant.now()), status, Timestamp.from(observedAt),
                json, critical, warning);
    }
}
