package com.superskidder.kylinops.backend.agentruntime.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse;
import com.superskidder.kylinops.backend.nodeexecutor.v2.NodeHeartbeat;
import io.grpc.stub.StreamObserver;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class NodeExecutorV2NativeGuardHeartbeatTest {

    private JdbcTemplate jdbc;
    private NodeExecutorV2Registry registry;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:node-guard-hb-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            for (String migration : new String[]{
                    "V1__baseline.sql",
                    "V2__node_session_native_guard.sql",
                    "V12__node_session_native_guard_details.sql"}) {
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("db/migration/" + migration));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("heartbeat native-guard test schema failed on H2", exception);
        }
        jdbc = new JdbcTemplate(dataSource);
        registry = new NodeExecutorV2Registry(jdbc, null);
    }

    @Test
    void heartbeatWritesProbeDetailsToSessionNotHostTable() {
        String json = """
                {"overall":"warning","items":[\
                {"tool":"kylin.security.auditd_status","supported":true,"status":"inactive","message":"down"},\
                {"tool":"kylin.security.selinux_status","supported":true,"status":"permissive","message":"permissive"}\
                ]}
                """;
        Instant observed = Instant.parse("2026-08-18T10:00:00Z");
        registry.heartbeat(NodeHeartbeat.newBuilder()
                .setTenantId("org-alpha")
                .setNodeId("node-kylin-01")
                .setNativeGuardStatus("WARNING")
                .setNativeGuardObservedAt(Timestamp.newBuilder().setSeconds(observed.getEpochSecond()).build())
                .setNativeGuardJson(json)
                .build(), silentObserver(), 1);

        Map<String, Object> session = jdbc.queryForMap("""
                select native_guard_status, native_guard_json,
                       native_guard_critical_count, native_guard_warning_count
                from agent_runtime_node_session
                where tenant_id = 'org-alpha' and node_id = 'node-kylin-01'
                """);
        assertThat(session.get("native_guard_status")).isEqualTo("WARNING");
        assertThat((String) session.get("native_guard_json")).contains("kylin.security.auditd_status");
        assertThat(((Number) session.get("native_guard_critical_count")).intValue()).isEqualTo(1);
        assertThat(((Number) session.get("native_guard_warning_count")).intValue()).isEqualTo(1);

        Integer hostRows = jdbc.queryForObject(
                "select count(1) from app_managed_host where native_guard_json is not null",
                Integer.class);
        assertThat(hostRows).isZero();
    }

    private static StreamObserver<ControlStreamResponse> silentObserver() {
        return new StreamObserver<>() {
            @Override
            public void onNext(ControlStreamResponse value) {
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}
