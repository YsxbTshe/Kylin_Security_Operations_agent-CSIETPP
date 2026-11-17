package com.superskidder.kylinops.backend.incident;

import java.util.Map;

/**
 * 事件时效指标。基于已解决事件的均值：
 *  · avgAcknowledgeSeconds：detected → acknowledged（响应启动耗时）
 *  · avgContainSeconds：detected → contained（遏制耗时，MTTD 口径）
 *  · avgResolveSeconds：detected → resolved（MTTR）
 * 口径边界：处置链跑通不等于检测准确；这里只度量闭环时效，不声称检测 Precision/Recall。
 */
public record IncidentMetrics(
        long total,
        Map<String, Long> byStatus,
        long resolvedCount,
        Long avgAcknowledgeSeconds,
        Long avgContainSeconds,
        Long avgResolveSeconds
) {
}
