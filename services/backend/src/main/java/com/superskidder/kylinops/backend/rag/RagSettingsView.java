package com.superskidder.kylinops.backend.rag;

public record RagSettingsView(
        String embeddingProvider,
        String embeddingBaseUrl,
        String embeddingModel,
        int embeddingDimension,
        int chunkSize,
        int chunkOverlap,
        int topK,
        boolean hasEmbeddingApiKey,
        String embeddingApiKeyMasked,
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
        String rerankerModel,
        int rerankerTopN,
        boolean hasRerankerApiKey,
        String rerankerApiKeyMasked,
        String visionConfigId,
        String updatedBy,
        String updatedAt
) {
}
