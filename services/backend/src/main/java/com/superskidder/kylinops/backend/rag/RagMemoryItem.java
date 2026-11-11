package com.superskidder.kylinops.backend.rag;

public record RagMemoryItem(
        String id,
        String memoryType,
        String title,
        String content,
        int importance,
        String sourceDocumentId
) {
}
