package com.superskidder.kylinops.backend.rag;

public record RagChunkItem(
        String id,
        String documentId,
        int chunkIndex,
        String title,
        String sourceType,
        String content,
        double score
) {
}
