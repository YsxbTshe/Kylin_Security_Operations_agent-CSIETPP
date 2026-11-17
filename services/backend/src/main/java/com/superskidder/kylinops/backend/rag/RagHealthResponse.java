package com.superskidder.kylinops.backend.rag;

public record RagHealthResponse(
        boolean externalIndexEnabled,
        boolean healthy
) {
}
