package com.superskidder.kylinops.backend.dashboard;

import java.util.List;

public record ScreenDashboardResponse(
        DashboardSummary summary,
        List<RiskSlice> riskDistribution,
        List<TrendPoint> trend,
        List<EventItem> events,
        List<HostHealthItem> hostHealth,
        List<AgentStatusItem> agents,
        SafeOpsSummary safeOps,
        IncidentBoard incidents
) {

    /** 安全事件响应闭环大屏板块：闭环计数 + 时效（MTTR）+ 最新事件。 */
    public record IncidentBoard(
            long total,
            long open,
            long resolved,
            Long avgResolveSeconds,
            Long avgContainSeconds,
            List<IncidentTile> latest
    ) {
    }

    public record IncidentTile(
            String id,
            String title,
            String severity,
            String status,
            String dataMode,
            String sourceIp
    ) {
    }
    public record RiskSlice(String level, long count) {
    }

    public record TrendPoint(String label, long count) {
    }

    public record EventItem(String time, String type, String message) {
    }

    public record HostHealthItem(String hostname, String status, String cpuUsage, String memoryUsage, String diskUsage) {
    }

    public record AgentStatusItem(String name, String status, String detail) {
    }

    public record SafeOpsSummary(
            long grpcConnectedAgents,
            long pendingApprovals,
            /** 被风险分级硬拒的下发次数（R5 或 R5_FORBIDDEN）。 */
            long r5Blocked,
            /** 已执行的清理回滚次数（kylin.cleanup.rollback）。 */
            long rollbackRecords,
            /** 当前未过期的资源锁数量。 */
            long activeResourceLocks,
            NativeGuardStatus nativeGuardStatus,
            NodeExporterStatus nodeExporterStatus,
            HostRuntimeStatus hostRuntimeStatus,
            long activeCertificates,
            long revokedCertificates,
            long expiringCertificates,
            RedisStreamStatus redisStreamStatus,
            /** MCP 工具调用排行（成功调用计数，TopN）。 */
            List<McpToolStat> mcpToolTop,
            /** 最近的运行时事件，供大屏「任务直播」滚动。 */
            List<EventItem> hostEvents,
            List<NodeExporterHostStat> nodeExporterHosts
    ) {
    }

    public record McpToolStat(
            String toolName,
            long count
    ) {
    }

    public record NativeGuardStatus(
            long healthyHosts,
            long warningHosts,
            long criticalHosts,
            long unsupportedHosts,
            long staleHosts,
            long unknownHosts,
            long criticalProbes,
            long warningProbes
    ) {
    }

    public record NodeExporterStatus(
            long upHosts,
            long downHosts,
            long disabledHosts,
            long staleHosts,
            long unknownHosts,
            Double avgCpuUsagePercent,
            Double avgMemoryUsagePercent,
            Double avgFilesystemUsagePercent
    ) {
    }

    public record NodeExporterHostStat(
            String hostname,
            String status,
            String checkedAt,
            Double cpuUsagePercent,
            Double memoryUsagePercent,
            Double filesystemUsagePercent,
            Double load1
    ) {
    }

    public record RedisStreamStatus(
            boolean enabled,
            String status,
            long assistantStreamLength,
            long hostTaskStreamLength,
            long approvalStreamLength,
            long assistantPending,
            long hostTaskPending,
            long approvalPending,
            long deadLetterLength,
            // outboxPending / outboxFailed 已随 outbox_event 表下线（迁移 V9）从契约移除：
            // 那张表没有消费者，任何数字都不代表投递健康度。前端 apps/dashboard-screen 已同步。
            long consumerFailures,
            long totalStreamLength,
            long maxPending,
            String capacityLevel,
            List<RedisAlertRule> alertRules
    ) {
    }

    public record RedisAlertRule(
            String name,
            String severity,
            long threshold,
            long currentValue,
            boolean triggered
    ) {
    }

    public record HostRuntimeStatus(
            long runningTasks,
            long queuedTasks,
            long localActiveLocks,
            long maxParallel,
            long heartbeatHosts
    ) {
    }
}
