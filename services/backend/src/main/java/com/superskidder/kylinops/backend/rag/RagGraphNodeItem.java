package com.superskidder.kylinops.backend.rag;

public record RagGraphNodeItem(
        String id,
        String name,
        String nodeType,
        String description,
        double salience,
        int mentionCount
) {
}
