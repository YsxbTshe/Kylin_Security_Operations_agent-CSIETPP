package com.superskidder.kylinops.backend.dashboard;

import com.superskidder.kylinops.backend.alert.AlertService;
import com.superskidder.kylinops.backend.audit.AuditRecord;
import com.superskidder.kylinops.backend.audit.AuditService;
import com.superskidder.kylinops.backend.host.HostService;
import com.superskidder.kylinops.backend.host.HostSummary;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;
    private final HostService hostService;
    private final AlertService alertService;
    private final AuditService auditService;
    private final long redisPendingWarnThreshold;
    private final long redisPendingCriticalThreshold;
    private final long redisDeadLetterWarnThreshold;

    public DashboardService(
            JdbcTemplate jdbcTemplate,
            HostService hostService,
            AlertService alertService,
            AuditService auditService,
            @Value("${app.host-task-events.redis.alert.pending-threshold:100}") long redisPendingWarnThreshold,
            @Value("${app.host-task-events.redis.alert.pending-critical-threshold:1000}") long redisPendingCriticalThreshold,
            @Value("${app.host-task-events.redis.alert.dead-letter-threshold:1}") long redisDeadLetterWarnThreshold
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.hostService = hostService;
        this.alertService = alertService;
        this.auditService = auditService;
        this.redisPendingWarnThreshold = Math.max(1, redisPendingWarnThreshold);
        this.redisPendingCriticalThreshold = Math.max(this.redisPendingWarnThreshold, redisPendingCriticalThreshold);
        this.redisDeadLetterWarnThreshold = Math.max(1, redisDeadLetterWarnThreshold);
    }

    public DashboardSummary summary(String organizationId) {
        long hostCount = hostService.listHosts(organizationId).size();
        long onlineCount = hostService.listHosts(organizationId).stream()
                .filter(host -> "ONLINE".equals(host.status()) || "ALERTING".equals(host.status()))
                .count();
        Long alertCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_alert_event WHERE organization_id = ?",
                Long.class,
                organizationId);
        Long agentExecutions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_audit_entry WHERE organization_id = ?",
                Long.class,
                organizationId);

        return new DashboardSummary(
                hostCount,
                onlineCount,
                valueOrZero(alertCount),
                valueOrZero(agentExecutions)
        );
    }

    public ScreenDashboardResponse screen(String organizationId) {
        DashboardSummary summary = summary(organizationId);
        List<HostSummary> hosts = hostService.listHosts(organizationId);
        Map<String, Long> riskLevels = alertService.listAlerts(organizationId).stream()
                .collect(java.util.stream.Collectors.groupingBy(item -> item.level().toUpperCase(), java.util.stream.Collectors.counting()));

        List<ScreenDashboardResponse.RiskSlice> riskDistribution = List.of(
                new ScreenDashboardResponse.RiskSlice("HIGH", valueOrZero(riskLevels.get("HIGH"))),
                new ScreenDashboardResponse.RiskSlice("MEDIUM", valueOrZero(riskLevels.get("MEDIUM"))),
                new ScreenDashboardResponse.RiskSlice("LOW", valueOrZero(riskLevels.get("LOW")))
        );
        List<ScreenDashboardResponse.TrendPoint> trend = buildTrend(organizationId);

        List<ScreenDashboardResponse.EventItem> events = auditService.listAudits(organizationId).stream()
                .limit(6)
                .map(this::toEventItem)
                .toList();

        List<ScreenDashboardResponse.HostHealthItem> hostHealth = hosts.stream()
                .limit(6)
                .map(item -> new ScreenDashboardResponse.HostHealthItem(
                        item.hostname(),
                        item.status(),
                        item.cpuUsage(),
                        item.memoryUsage(),
                        item.diskUsage()
                ))
                .toList();

        List<ScreenDashboardResponse.AgentStatusItem> agents = buildAgentStatus(summary, hosts);
        ScreenDashboardResponse.SafeOpsSummary safeOps = buildSafeOpsSummary(organizationId);
        ScreenDashboardResponse.IncidentBoard incidents = buildIncidentBoard(organizationId);

        return new ScreenDashboardResponse(summary, riskDistribution, trend, events, hostHealth, agents, safeOps, incidents);
    }

    /**
     * 安全事件响应闭环大屏板块。事件表可能尚未建（旧库），用 try/catch 兜底为空板块，
     * 保证大屏在任何库版本下都不报错。MTTR = 已解决事件 detected→resolved 的均值。
     */
    private ScreenDashboardResponse.IncidentBoard buildIncidentBoard(String organizationId) {
        try {
            long total = valueOrZero(jdbcTemplate.queryForObject(
                    "select count(1) from app_incident where organization_id = ?", Long.class, organizationId));
            long resolved = valueOrZero(jdbcTemplate.queryForObject(
                    "select count(1) from app_incident where organization_id = ? and status = 'RESOLVED'",
                    Long.class, organizationId));
            long open = valueOrZero(jdbcTemplate.queryForObject("""
                    select count(1) from app_incident where organization_id = ?
                      and status not in ('RESOLVED', 'CLOSED', 'FALSE_POSITIVE')
                    """, Long.class, organizationId));
            // 时效在 Java 侧算，避免 DATEDIFF 等非可移植 SQL（H2 dev / PostgreSQL prod 一致）。
            List<long[]> spans = jdbcTemplate.query("""
                    select detected_at, contained_at, resolved_at from app_incident
                    where organization_id = ? and status = 'RESOLVED' and resolved_at is not null
                    """, (rs, n) -> new long[]{
                    spanSeconds(rs.getTimestamp("detected_at"), rs.getTimestamp("contained_at")),
                    spanSeconds(rs.getTimestamp("detected_at"), rs.getTimestamp("resolved_at"))
            }, organizationId);
            Long avgContain = averageSpan(spans, 0);
            Long avgResolve = averageSpan(spans, 1);
            List<ScreenDashboardResponse.IncidentTile> latest = jdbcTemplate.query("""
                    select id, title, severity, status, data_mode, source_ip
                    from app_incident where organization_id = ?
                    order by detected_at desc, id desc
                    """, (rs, n) -> new ScreenDashboardResponse.IncidentTile(
                    rs.getString("id"), rs.getString("title"), rs.getString("severity"),
                    rs.getString("status"), rs.getString("data_mode"), rs.getString("source_ip")),
                    organizationId).stream().limit(6).toList();
            return new ScreenDashboardResponse.IncidentBoard(total, open, resolved, avgResolve, avgContain, latest);
        } catch (Exception exception) {
            return new ScreenDashboardResponse.IncidentBoard(0, 0, 0, null, null, List.of());
        }
    }

    private static long spanSeconds(java.sql.Timestamp from, java.sql.Timestamp to) {
        if (from == null || to == null) {
            return -1L;
        }
        return java.time.Duration.between(from.toLocalDateTime(), to.toLocalDateTime()).getSeconds();
    }

    private static Long averageSpan(List<long[]> spans, int index) {
        long sum = 0;
        long count = 0;
        for (long[] span : spans) {
            if (span[index] >= 0) {
                sum += span[index];
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }


    /** 包级可见：让 DashboardSafeOpsAggregationTest 能直接跑这些 SQL，而不必给三个无关协作者打桩。 */
    ScreenDashboardResponse.SafeOpsSummary buildSafeOpsSummary(String organizationId) {
        long grpcConnectedAgents = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and grpc_connected = true
                """, Long.class, organizationId));
        // Agent Runtime 待审批数：大屏「任务直播」幕的核心叙事（此前前端契约有此字段但后端从不返回，恒 0）。
        long pendingApprovals = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from approval
                where tenant_id = ? and decision = 'PENDING'
                """, Long.class, organizationId));
        ScreenDashboardResponse.NativeGuardStatus nativeGuardStatus = buildNativeGuardStatus(organizationId);
        ScreenDashboardResponse.NodeExporterStatus nodeExporterStatus = buildNodeExporterStatus(organizationId);
        ScreenDashboardResponse.HostRuntimeStatus hostRuntimeStatus = buildHostRuntimeStatus(organizationId);
        long activeCertificates = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and cert_status = 'ACTIVE'
                """, Long.class, organizationId));
        long revokedCertificates = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and cert_status = 'REVOKED'
                """, Long.class, organizationId));
        long expiringCertificates = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ?
                  and cert_status = 'ACTIVE'
                  and cert_expires_at is not null
                  and cert_expires_at <= ?
                """, Long.class, organizationId, Timestamp.from(Instant.now().plusSeconds(7 * 24 * 3600L))));
        ScreenDashboardResponse.RedisStreamStatus redisStreamStatus = buildRedisStreamStatus();
        List<ScreenDashboardResponse.NodeExporterHostStat> nodeExporterHosts = buildNodeExporterHosts(organizationId);
        // 以下三个计数 + 两个列表：前端契约里一直存在，但后端从不返回，于是大屏落地页
        // 恒显示 0 / 空。这里补齐真实来源（tool_invocation / resource_lock / agent_run_event）。
        long r5Blocked = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from tool_invocation
                where tenant_id = ?
                  and status = 'REJECTED'
                  and (risk_code = 'R5' or rejection_code = 'R5_FORBIDDEN')
                """, Long.class, organizationId));
        long rollbackRecords = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from tool_invocation
                where tenant_id = ? and tool_id = 'kylin.cleanup.rollback' and status = 'SUCCEEDED'
                """, Long.class, organizationId));
        long activeResourceLocks = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from resource_lock
                where tenant_id = ? and (expires_at is null or expires_at > ?)
                """, Long.class, organizationId, Timestamp.from(Instant.now())));
        List<ScreenDashboardResponse.McpToolStat> mcpToolTop = buildMcpToolTop(organizationId);
        List<ScreenDashboardResponse.EventItem> hostEvents = buildHostEvents(organizationId);
        return new ScreenDashboardResponse.SafeOpsSummary(
                grpcConnectedAgents,
                pendingApprovals,
                r5Blocked,
                rollbackRecords,
                activeResourceLocks,
                nativeGuardStatus,
                nodeExporterStatus,
                hostRuntimeStatus,
                activeCertificates,
                revokedCertificates,
                expiringCertificates,
                redisStreamStatus,
                mcpToolTop,
                hostEvents,
                nodeExporterHosts
        );
    }

    /** MCP 工具调用排行：只统计成功调用，避免把被拒的下发也算进「工具生态活跃度」。 */
    private List<ScreenDashboardResponse.McpToolStat> buildMcpToolTop(String organizationId) {
        return jdbcTemplate.query("""
                select tool_id, count(1) as call_count
                from tool_invocation
                where tenant_id = ? and status = 'SUCCEEDED'
                group by tool_id
                order by call_count desc, tool_id asc
                """,
                (rs, rowNum) -> new ScreenDashboardResponse.McpToolStat(
                        rs.getString("tool_id"), rs.getLong("call_count")),
                organizationId)
                .stream().limit(8).toList();
    }

    /** 最近运行时事件。payload 不外泄：只取事件类型与 run 关联，正文交由详情页按权限查。 */
    private List<ScreenDashboardResponse.EventItem> buildHostEvents(String organizationId) {
        return jdbcTemplate.query("""
                select event_type, run_id, created_at
                from agent_run_event
                where tenant_id = ?
                order by created_at desc, sequence_no desc
                """,
                (rs, rowNum) -> {
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    return new ScreenDashboardResponse.EventItem(
                            createdAt == null ? "" : createdAt.toInstant().toString(),
                            rs.getString("event_type"),
                            "run " + rs.getString("run_id"));
                },
                organizationId)
                .stream().limit(12).toList();
    }

    private List<ScreenDashboardResponse.NodeExporterHostStat> buildNodeExporterHosts(String organizationId) {
        return jdbcTemplate.query("""
                select hostname, node_exporter_status, node_exporter_checked_at,
                       node_exporter_cpu_usage_percent, node_exporter_memory_usage_percent,
                       node_exporter_filesystem_usage_percent, node_exporter_load1
                from app_managed_host
                where organization_id = ?
                order by
                    case
                        when node_exporter_status in ('DOWN', 'ERROR') then 0
                        when node_exporter_checked_at is null then 1
                        when node_exporter_checked_at < ? then 2
                        when node_exporter_status = 'DISABLED' then 3
                        else 4
                    end,
                    coalesce(node_exporter_cpu_usage_percent, 0) desc,
                    hostname asc
                limit 8
                """, (rs, rowNum) -> new ScreenDashboardResponse.NodeExporterHostStat(
                rs.getString("hostname"),
                rs.getString("node_exporter_status"),
                rs.getTimestamp("node_exporter_checked_at") == null ? "" : rs.getTimestamp("node_exporter_checked_at").toLocalDateTime().toString().replace('T', ' '),
                round(rs.getObject("node_exporter_cpu_usage_percent", Double.class)),
                round(rs.getObject("node_exporter_memory_usage_percent", Double.class)),
                round(rs.getObject("node_exporter_filesystem_usage_percent", Double.class)),
                round(rs.getObject("node_exporter_load1", Double.class))
        ), organizationId, Timestamp.from(Instant.now().minusSeconds(180)));
    }

    private ScreenDashboardResponse.NodeExporterStatus buildNodeExporterStatus(String organizationId) {
        Timestamp staleBefore = Timestamp.from(Instant.now().minusSeconds(180));
        long upHosts = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and node_exporter_status = 'UP' and node_exporter_checked_at >= ?
                """, Long.class, organizationId, staleBefore));
        long downHosts = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and node_exporter_status in ('DOWN', 'ERROR') and node_exporter_checked_at >= ?
                """, Long.class, organizationId, staleBefore));
        long disabledHosts = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and node_exporter_status = 'DISABLED' and node_exporter_checked_at >= ?
                """, Long.class, organizationId, staleBefore));
        long staleHosts = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and node_exporter_checked_at is not null and node_exporter_checked_at < ?
                """, Long.class, organizationId, staleBefore));
        long unknownHosts = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host
                where organization_id = ? and node_exporter_checked_at is null
                """, Long.class, organizationId));
        Double avgCpu = jdbcTemplate.queryForObject("""
                select avg(node_exporter_cpu_usage_percent)
                from app_managed_host
                where organization_id = ? and node_exporter_status = 'UP' and node_exporter_checked_at >= ?
                """, Double.class, organizationId, staleBefore);
        Double avgMemory = jdbcTemplate.queryForObject("""
                select avg(node_exporter_memory_usage_percent)
                from app_managed_host
                where organization_id = ? and node_exporter_status = 'UP' and node_exporter_checked_at >= ?
                """, Double.class, organizationId, staleBefore);
        Double avgFilesystem = jdbcTemplate.queryForObject("""
                select avg(node_exporter_filesystem_usage_percent)
                from app_managed_host
                where organization_id = ? and node_exporter_status = 'UP' and node_exporter_checked_at >= ?
                """, Double.class, organizationId, staleBefore);
        return new ScreenDashboardResponse.NodeExporterStatus(
                upHosts,
                downHosts,
                disabledHosts,
                staleHosts,
                unknownHosts,
                round(avgCpu),
                round(avgMemory),
                round(avgFilesystem)
        );
    }

    /**
     * 姿态以心跳写入的 {@code agent_runtime_node_session} 为准。
     * 库存仍按 {@code app_managed_host} 计主机数：注册过但从未上报的主机落入 unknown。
     */
    private ScreenDashboardResponse.NativeGuardStatus buildNativeGuardStatus(String organizationId) {
        Timestamp staleBefore = Timestamp.from(Instant.now().minusSeconds(180));
        long healthyHosts = countNativeGuardHosts(organizationId, """
                s.native_guard_status = 'HEALTHY' and s.native_guard_observed_at >= ?
                """, staleBefore);
        long warningHosts = countNativeGuardHosts(organizationId, """
                s.native_guard_status = 'WARNING' and s.native_guard_observed_at >= ?
                """, staleBefore);
        long criticalHosts = countNativeGuardHosts(organizationId, """
                s.native_guard_status = 'CRITICAL' and s.native_guard_observed_at >= ?
                """, staleBefore);
        long unsupportedHosts = countNativeGuardHosts(organizationId, """
                s.native_guard_status = 'UNSUPPORTED' and s.native_guard_observed_at >= ?
                """, staleBefore);
        long staleHosts = countNativeGuardHosts(organizationId, """
                s.native_guard_observed_at is not null and s.native_guard_observed_at < ?
                """, staleBefore);
        long unknownHosts = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host h
                left join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                where h.organization_id = ? and s.native_guard_observed_at is null
                """, Long.class, organizationId));
        long criticalProbes = valueOrZero(jdbcTemplate.queryForObject("""
                select coalesce(sum(s.native_guard_critical_count), 0)
                from app_managed_host h
                join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                where h.organization_id = ? and s.native_guard_observed_at >= ?
                """, Long.class, organizationId, staleBefore));
        long warningProbes = valueOrZero(jdbcTemplate.queryForObject("""
                select coalesce(sum(s.native_guard_warning_count), 0)
                from app_managed_host h
                join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                where h.organization_id = ? and s.native_guard_observed_at >= ?
                """, Long.class, organizationId, staleBefore));
        return new ScreenDashboardResponse.NativeGuardStatus(
                healthyHosts,
                warningHosts,
                criticalHosts,
                unsupportedHosts,
                staleHosts,
                unknownHosts,
                criticalProbes,
                warningProbes
        );
    }

    private long countNativeGuardHosts(String organizationId, String predicate, Timestamp staleBefore) {
        return valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from app_managed_host h
                left join agent_runtime_node_session s
                  on s.tenant_id = h.organization_id and s.node_id = h.node_id
                where h.organization_id = ? and %s
                """.formatted(predicate), Long.class, organizationId, staleBefore));
    }

    private ScreenDashboardResponse.HostRuntimeStatus buildHostRuntimeStatus(String organizationId) {
        // 运行态读新 Agent Runtime 模型（agent_run / resource_lock / tenant_runtime_quota），
        // 不再读旧执行链的 app_managed_host.scheduler_* 列（新模型不写、恒 0）。
        long runningTasks = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1) from agent_run
                where tenant_id = ? and status = 'RUNNING'
                """, Long.class, organizationId));
        long queuedTasks = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1) from agent_run
                where tenant_id = ? and status = 'QUEUED'
                """, Long.class, organizationId));
        long localActiveLocks = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1) from resource_lock
                where tenant_id = ? and expires_at > ?
                """, Long.class, organizationId, Timestamp.from(Instant.now())));
        long maxParallel = valueOrZero(jdbcTemplate.queryForObject("""
                select coalesce(max(max_concurrent_runs), 8) from tenant_runtime_quota
                where tenant_id = ?
                """, Long.class, organizationId));
        long heartbeatHosts = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1) from app_managed_host
                where organization_id = ? and last_heartbeat_at >= ?
                """, Long.class, organizationId, Timestamp.from(Instant.now().minusSeconds(120))));
        return new ScreenDashboardResponse.HostRuntimeStatus(
                runningTasks,
                queuedTasks,
                localActiveLocks,
                maxParallel,
                heartbeatHosts
        );
    }

    private ScreenDashboardResponse.RedisStreamStatus buildRedisStreamStatus() {
        // outbox_event 已随迁移 V9 下线（表无消费者、绕过保留期清理），因此
        // outboxPending / outboxFailed 两个字段一并从大屏契约里移除——
        // 与其报一个恒为 0（或恒等于历史事件总数）的数字，不如不报。
        long consumerFailures = valueOrZero(jdbcTemplate.queryForObject("""
                select count(1)
                from redis_stream_consumer_failure
                where status <> 'DEAD_LETTERED'
                """, Long.class));
        String capacity = redisCapacityStatus(consumerFailures, 0, 0, 0, 0);
        // Redis Stream 长度/积压未接入 INFO，不以硬编码 0 伪装"队列健康"。
        // 真实信号只剩 consumer_failure 表；面板状态标为 DISABLED 以免把缺失数据误读为健康状态。
        String status = "CRITICAL".equals(capacity) || "WARN".equals(capacity) ? capacity : "DISABLED";
        String capacityLevel = switch (capacity) {
            case "CRITICAL" -> "CRITICAL";
            case "WARN" -> "DEGRADED";
            default -> "UNWIRED";
        };
        return new ScreenDashboardResponse.RedisStreamStatus(
                false,
                status,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                consumerFailures,
                0,
                0,
                capacityLevel,
                redisAlertRules(consumerFailures, 0, 0)
        );
    }

    String redisCapacityStatus(long consumerFailures, long assistantPending, long hostTaskPending, long approvalPending, long deadLetterLength) {
        long maxPending = Math.max(assistantPending, Math.max(hostTaskPending, approvalPending));
        if (consumerFailures > 0
                || deadLetterLength >= redisDeadLetterWarnThreshold
                || maxPending >= redisPendingCriticalThreshold) {
            return "CRITICAL";
        }
        if (consumerFailures > 0
                || deadLetterLength >= redisDeadLetterWarnThreshold
                || maxPending >= redisPendingWarnThreshold) {
            return "WARN";
        }
        return "UP";
    }

    List<ScreenDashboardResponse.RedisAlertRule> redisAlertRules(long consumerFailures, long maxPending, long deadLetterLength) {
        return List.of(
                new ScreenDashboardResponse.RedisAlertRule(
                        "redis_pending_warn",
                        "WARN",
                        redisPendingWarnThreshold,
                        maxPending,
                        maxPending >= redisPendingWarnThreshold
                ),
                new ScreenDashboardResponse.RedisAlertRule(
                        "redis_pending_critical",
                        "CRITICAL",
                        redisPendingCriticalThreshold,
                        maxPending,
                        maxPending >= redisPendingCriticalThreshold
                ),
                new ScreenDashboardResponse.RedisAlertRule(
                        "redis_dead_letter",
                        "CRITICAL",
                        redisDeadLetterWarnThreshold,
                        deadLetterLength,
                        deadLetterLength >= redisDeadLetterWarnThreshold
                ),
                new ScreenDashboardResponse.RedisAlertRule(
                        "redis_consumer_failure",
                        "CRITICAL",
                        1,
                        consumerFailures,
                        consumerFailures > 0
                )
        );
    }

    private List<ScreenDashboardResponse.AgentStatusItem> buildAgentStatus(
            DashboardSummary summary,
            List<HostSummary> hosts
    ) {
        long onlineHosts = hosts.stream()
                .filter(host -> "ONLINE".equals(host.status()) || "ALERTING".equals(host.status()))
                .count();

        String hostStatus = hosts.isEmpty() ? "IDLE" : onlineHosts > 0 ? "RUNNING" : "OFFLINE";

        return List.of(
                new ScreenDashboardResponse.AgentStatusItem(
                        "Host Agent",
                        hostStatus,
                        "%d/%d 在线节点，%d 次平台执行".formatted(onlineHosts, hosts.size(), summary.agentExecutions())
                )
        );
    }

    private List<ScreenDashboardResponse.TrendPoint> buildTrend(String organizationId) {
        Map<String, Long> counts = jdbcTemplate.query("""
                select cast(event_time as date) as event_day, count(1) as event_count
                from app_audit_entry
                where organization_id = ? and event_time >= ?
                group by cast(event_time as date)
                """, (resultSet, rowNum) -> Map.entry(
                resultSet.getString("event_day"),
                resultSet.getLong("event_count")
        ), organizationId, java.sql.Date.valueOf(LocalDate.now().minusDays(6))).stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return java.util.stream.IntStream.rangeClosed(0, 6)
                .mapToObj(offset -> LocalDate.now().minusDays(6L - offset))
                .map(day -> new ScreenDashboardResponse.TrendPoint(
                        day.toString().substring(5),
                        valueOrZero(counts.get(day.toString()))
                ))
                .toList();
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    private ScreenDashboardResponse.EventItem toEventItem(AuditRecord record) {
        return new ScreenDashboardResponse.EventItem(
                record.time(),
                record.action(),
                record.operator() + " -> " + record.target()
        );
    }
}
