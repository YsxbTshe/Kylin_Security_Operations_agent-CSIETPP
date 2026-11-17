package com.superskidder.kylinops.backend.llm;

public record LlmModelConfigView(
        String id,
        String providerType,
        String groupName,
        String baseUrl,
        String model,
        boolean enabled,
        int timeoutMs,
        int retryCount,
        java.util.List<String> supportedThinkingLevels,
        String defaultThinkingLevel,
        boolean hasApiKey,
        String apiKeyMasked,
        String updatedBy,
        String updatedAt
) {
}
