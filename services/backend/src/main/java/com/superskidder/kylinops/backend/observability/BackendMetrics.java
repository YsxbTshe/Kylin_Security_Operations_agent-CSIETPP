package com.superskidder.kylinops.backend.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class BackendMetrics implements MeterBinder {

    private final JdbcTemplate jdbcTemplate;

    public BackendMetrics(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "kylin.backend.managed.hosts", "Managed Host count", "select count(*) from app_managed_host");
        gauge(registry, "kylin.backend.managed.hosts.online", "Online Managed Host count", "select count(*) from app_managed_host where status = 'ONLINE'");
        gauge(registry, "kylin.backend.grpc.connected.hosts", "Hosts with active gRPC ControlStream", "select count(*) from app_managed_host where grpc_connected = true");
        gauge(registry, "kylin.backend.certificates.active", "Active Host Agent certificates", "select count(*) from app_managed_host where cert_status = 'ACTIVE'");
        gauge(registry, "kylin.backend.certificates.revoked", "Revoked Host Agent certificates", "select count(*) from app_managed_host where cert_status = 'REVOKED'");
        gauge(registry, "kylin.backend.native_guard.critical.hosts", "Hosts with critical Native Guard posture",
                """
                select count(*) from app_managed_host h
                join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                where s.native_guard_status = 'CRITICAL'
                """);
        gauge(registry, "kylin.backend.native_guard.warning.hosts", "Hosts with warning Native Guard posture",
                """
                select count(*) from app_managed_host h
                join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                where s.native_guard_status = 'WARNING'
                """);
        gauge(registry, "kylin.backend.native_guard.critical.probes", "Critical Native Guard probes",
                """
                select coalesce(sum(s.native_guard_critical_count), 0) from app_managed_host h
                join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                """);
        gauge(registry, "kylin.backend.native_guard.warning.probes", "Warning Native Guard probes",
                """
                select coalesce(sum(s.native_guard_warning_count), 0) from app_managed_host h
                join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                """);
        gauge(registry, "kylin.backend.node_exporter.up.hosts", "Hosts with fresh node_exporter snapshot", "select count(*) from app_managed_host where node_exporter_status = 'UP'");
        gauge(registry, "kylin.backend.node_exporter.down.hosts", "Hosts with failed node_exporter snapshot", "select count(*) from app_managed_host where node_exporter_status in ('DOWN', 'ERROR')");
        gauge(registry, "kylin.backend.audit.events", "Persisted Host task audit events", "select count(*) from host_task_event");
        gauge(registry, "kylin.backend.resource_locks.active", "Active Host resource locks", "select count(*) from host_resource_lock where expires_at is null or expires_at > current_timestamp");
        // kylin.backend.outbox.{pending,failed} 已随 outbox_event 表下线（迁移 V9）：
        // 那张表没有任何消费者，pending 计数永远等于历史事件总数，作为指标只会误导告警。
        gauge(registry, "kylin.backend.redis.consumer_failures.open", "Open Redis Stream consumer failures", "select count(*) from redis_stream_consumer_failure where status <> 'DEAD_LETTERED'");
    }

    private void gauge(MeterRegistry registry, String name, String description, String sql) {
        Gauge.builder(name, () -> count(sql))
                .description(description)
                .register(registry);
    }

    private double count(String sql) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0.0 : value.doubleValue();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }
}
