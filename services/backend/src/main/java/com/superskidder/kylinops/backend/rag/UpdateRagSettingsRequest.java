package com.superskidder.kylinops.backend.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateRagSettingsRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 32, message = "长度不能超过32")
        String embeddingProvider,
        @Size(max = 512, message = "长度不能超过512")
        String embeddingBaseUrl,
        @Size(max = 512, message = "长度不能超过512")
        String embeddingApiKey,
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String embeddingModel,
        @Min(value = 16, message = "不能小于16")
        @Max(value = 4096, message = "不能大于4096")
        int embeddingDimension,
        @Min(value = 200, message = "不能小于200")
        @Max(value = 4000, message = "不能大于4000")
        int chunkSize,
        @Min(value = 0, message = "不能小于0")
        @Max(value = 1000, message = "不能大于1000")
        int chunkOverlap,
        @Min(value = 1, message = "不能小于1")
        @Max(value = 20, message = "不能大于20")
        int topK,
        boolean milvusEnabled,
        @NotBlank(message = "不能为空")
        @Size(max = 512, message = "长度不能超过512")
        String milvusBaseUrl,
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String milvusCollection,
        boolean elasticsearchEnabled,
        @NotBlank(message = "不能为空")
        @Size(max = 512, message = "长度不能超过512")
        String elasticsearchBaseUrl,
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String elasticsearchIndex,
        boolean redisEnabled,
        @NotBlank(message = "不能为空")
        @Size(max = 512, message = "长度不能超过512")
        String redisBaseUrl,
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String redisPrefix,
        Boolean rerankerEnabled,
        @Size(max = 512, message = "长度不能超过512")
        String rerankerBaseUrl,
        @Size(max = 512, message = "长度不能超过512")
        String rerankerApiKey,
        @Size(max = 128, message = "长度不能超过128")
        String rerankerModel,
        @Min(value = 1, message = "不能小于1")
        @Max(value = 100, message = "不能大于100")
        Integer rerankerTopN,
        @Size(max = 64, message = "长度不能超过64")
        String visionConfigId
) {
}
