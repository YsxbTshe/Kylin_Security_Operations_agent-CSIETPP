package com.superskidder.kylinops.backend.memory;

import java.time.LocalDateTime;

/**
 * 一次记忆写入的完整载荷，携带出处与生命周期信息（ADR-001 §2.7）。
 *
 * 出处字段（sourceTaskId/taskOutcome/verified/validUntil/scope）让召回时能按来源判可信度，
 * 也让「同一 scope 的冲突」和「被取代」得以识别。全部可空——不知道来源时退化为一条
 * 未验证、无出处的普通记忆。
 */
public record MemoryWrite(
        String memoryType,
        String title,
        String content,
        int importance,
        String memorySource,
        String eventRef,
        String sourceTaskId,
        String taskOutcome,
        boolean verified,
        LocalDateTime validUntil,
        String scope
) {
}
