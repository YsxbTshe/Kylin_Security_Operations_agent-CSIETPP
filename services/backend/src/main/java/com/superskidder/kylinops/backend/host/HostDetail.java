package com.superskidder.kylinops.backend.host;

import com.superskidder.kylinops.backend.policy.ToolDefinition;
import java.util.List;

public record HostDetail(
        String hostname,
        String ip,
        String status,
        String osVersion,
        String cpuUsage,
        String memoryUsage,
        String diskUsage,
        String loadAverage,
        String uptimeText,
        String nodeId,
        String clusterName,
        String agentVersion,
        java.util.List<String> supportedTools,
        List<ToolDefinition> supportedToolDetails,
        String lastHeartbeatAt,
        boolean grpcConnected,
        String grpcConnectedAt,
        String certSubject,
        String certExpiresAt,
        String certStatus,
        List<SessionItem> sessions
) {

    public record SessionItem(
            String taskId,
            String requestedBy,
            String executionMode,
            String status,
            String updatedAt
    ) {
    }
}
