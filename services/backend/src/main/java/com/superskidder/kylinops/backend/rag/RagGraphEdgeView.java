package com.superskidder.kylinops.backend.rag;

public record RagGraphEdgeView(
        String id,
        String source,
        String target,
        String relation,
        double weight,
        String evidence
) {
}
