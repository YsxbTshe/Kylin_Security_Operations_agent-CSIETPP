package com.superskidder.kylinops.backend.rag;

/** A retrieval hit: an id (chunk id) with its channel score. */
public record RagScoredId(String id, double score) {
}
