package com.superskidder.kylinops.backend.incident;

/** 接入结果：merged=true 表示命中既有非终态事件被幂等合并（重复事件不产生新开放事件）。 */
public record IncidentIntakeResult(IncidentSummary incident, boolean merged) {
}
