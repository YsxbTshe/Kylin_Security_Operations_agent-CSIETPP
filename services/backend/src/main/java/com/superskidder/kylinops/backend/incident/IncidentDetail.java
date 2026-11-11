package com.superskidder.kylinops.backend.incident;

import java.util.List;

/** 事件详情：概要 + 证据摘要 + 关联运行手册 + 推荐 SOP 知识 + 本事件的时效点。 */
public record IncidentDetail(
        IncidentSummary summary,
        String description,
        String evidenceJson,
        String runbookId,
        IncidentRunbookSummary runbook,
        List<SopReference> recommendedSop,
        Long sourceAlertId,
        String acknowledgedAt,
        String containedAt,
        String resolvedAt,
        Long timeToAcknowledgeSeconds,
        Long timeToContainSeconds,
        Long timeToResolveSeconds
) {

    /** 与事件类别相关的官方文档/SOP（来自 RAG 知识库，只读引用，供研判参考）。 */
    public record SopReference(String id, String title, String sourceType) {
    }
}
