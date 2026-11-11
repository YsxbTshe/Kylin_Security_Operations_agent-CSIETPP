package com.superskidder.kylinops.backend.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class BackendMetricsTest {

    @Test
    void shouldExposeBackendBusinessGauges() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:backend_metrics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        createTables(jdbcTemplate);
        seedData(jdbcTemplate);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new BackendMetrics(jdbcTemplate).bindTo(registry);

        assertThat(gauge(registry, "kylin.backend.managed.hosts")).isEqualTo(2.0);
        assertThat(gauge(registry, "kylin.backend.managed.hosts.online")).isEqualTo(1.0);
        assertThat(gauge(registry, "kylin.backend.grpc.connected.hosts")).isEqualTo(1.0);
        assertThat(gauge(registry, "kylin.backend.audit.events")).isEqualTo(2.0);
        assertThat(gauge(registry, "kylin.backend.redis.consumer_failures.open")).isEqualTo(1.0);
        // outbox 相关 gauge 已随 outbox_event 表下线（迁移 V9）移除
        assertThat(registry.find("kylin.backend.outbox.pending").gauge()).isNull();
        assertThat(registry.find("kylin.backend.outbox.failed").gauge()).isNull();
        assertThat(gauge(registry, "kylin.backend.node_exporter.up.hosts")).isEqualTo(1.0);
        assertThat(gauge(registry, "kylin.backend.node_exporter.down.hosts")).isEqualTo(1.0);
        assertThat(gauge(registry, "kylin.backend.native_guard.critical.hosts")).isEqualTo(1.0);
        assertThat(gauge(registry, "kylin.backend.native_guard.warning.hosts")).isEqualTo(1.0);
        assertThat(gauge(registry, "kylin.backend.native_guard.critical.probes")).isEqualTo(2.0);
        assertThat(gauge(registry, "kylin.backend.native_guard.warning.probes")).isEqualTo(3.0);
    }

    private void createTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("create table app_managed_host (organization_id varchar(64), node_id varchar(128), status varchar(32), grpc_connected boolean, cert_status varchar(32), node_exporter_status varchar(32))");
        jdbcTemplate.execute("create table agent_runtime_node_session (tenant_id varchar(64), node_id varchar(128), native_guard_status varchar(32), native_guard_critical_count integer, native_guard_warning_count integer)");
        jdbcTemplate.execute("create table host_task_event (id bigint)");
        jdbcTemplate.execute("create table host_resource_lock (expires_at timestamp)");
        jdbcTemplate.execute("create table redis_stream_consumer_failure (status varchar(32))");
    }

    private void seedData(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("insert into app_managed_host (organization_id, node_id, status, grpc_connected, cert_status, node_exporter_status) values ('org-alpha', 'node-1', 'ONLINE', true, 'ACTIVE', 'UP')");
        jdbcTemplate.update("insert into app_managed_host (organization_id, node_id, status, grpc_connected, cert_status, node_exporter_status) values ('org-alpha', 'node-2', 'OFFLINE', false, 'REVOKED', 'DOWN')");
        jdbcTemplate.update("insert into agent_runtime_node_session (tenant_id, node_id, native_guard_status, native_guard_critical_count, native_guard_warning_count) values ('org-alpha', 'node-1', 'CRITICAL', 2, 0)");
        jdbcTemplate.update("insert into agent_runtime_node_session (tenant_id, node_id, native_guard_status, native_guard_critical_count, native_guard_warning_count) values ('org-alpha', 'node-2', 'WARNING', 0, 3)");
        jdbcTemplate.update("insert into host_task_event (id) values (1)");
        jdbcTemplate.update("insert into host_task_event (id) values (2)");
        jdbcTemplate.update("insert into host_resource_lock (expires_at) values (null)");
        jdbcTemplate.update("insert into redis_stream_consumer_failure (status) values ('FAILED')");
        jdbcTemplate.update("insert into redis_stream_consumer_failure (status) values ('DEAD_LETTERED')");
    }

    private double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
