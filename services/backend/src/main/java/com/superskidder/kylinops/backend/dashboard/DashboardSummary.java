package com.superskidder.kylinops.backend.dashboard;

public record DashboardSummary(long hostCount, long onlineCount, long alertCount, long agentExecutions) {
}
