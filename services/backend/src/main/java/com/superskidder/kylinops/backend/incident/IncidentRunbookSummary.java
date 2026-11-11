package com.superskidder.kylinops.backend.incident;

/** 运行手册概要（步骤为原始 JSON 数组字符串，前端渲染为步骤条）。 */
public record IncidentRunbookSummary(
        String id,
        String name,
        String version,
        String category,
        String summary,
        String stepsJson
) {
}
