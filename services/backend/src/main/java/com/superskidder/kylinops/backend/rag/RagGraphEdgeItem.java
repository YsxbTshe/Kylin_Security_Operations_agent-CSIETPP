package com.superskidder.kylinops.backend.rag;

public record RagGraphEdgeItem(
        String id,
        String sourceNode,
        String relation,
        String targetNode,
        String evidence,
        String sourceDocumentId
) {
}
