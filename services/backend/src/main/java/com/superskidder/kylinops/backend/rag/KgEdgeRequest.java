package com.superskidder.kylinops.backend.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工维护 app_kg_edge（/rag/graph 展示的那张图）的关系。
 * 与 {@link RagGraphEdgeCreateRequest} 不是一回事：后者作用于 app_rag_graph_edge。
 */
public record KgEdgeRequest(
        @Size(max = 200, message = "长度不能超过200")
        String sourceNode,
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String relation,
        @Size(max = 200, message = "长度不能超过200")
        String targetNode,
        @Size(max = 2000, message = "长度不能超过2000")
        String evidence
) {
}
