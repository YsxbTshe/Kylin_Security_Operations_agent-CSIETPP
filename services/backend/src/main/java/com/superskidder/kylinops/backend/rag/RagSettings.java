package com.superskidder.kylinops.backend.rag;

import java.time.LocalDateTime;

public record RagSettings(
        String id,
        String embeddingProvider,
        String embeddingBaseUrl,
        String embeddingApiKey,
        String embeddingModel,
        int embeddingDimension,
        int chunkSize,
        int chunkOverlap,
        int topK,
        boolean milvusEnabled,
        String milvusBaseUrl,
        String milvusCollection,
        boolean elasticsearchEnabled,
        String elasticsearchBaseUrl,
        String elasticsearchIndex,
        boolean redisEnabled,
        String redisBaseUrl,
        String redisPrefix,
        boolean rerankerEnabled,
        String rerankerBaseUrl,
        String rerankerApiKey,
        String rerankerModel,
        int rerankerTopN,
        String visionConfigId,
        String updatedBy,
        LocalDateTime updatedAt
) {
}
