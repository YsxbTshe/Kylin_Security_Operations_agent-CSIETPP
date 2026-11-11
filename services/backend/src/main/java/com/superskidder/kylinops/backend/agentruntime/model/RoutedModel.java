package com.superskidder.kylinops.backend.agentruntime.model;

import com.superskidder.kylinops.backend.llm.LlmModelConfig;

public record RoutedModel(
        String profileId,
        LlmModelConfig config,
        String reasoningLevel,
        String cachePolicy,
        String reason,
        String configDigest
) {
}
