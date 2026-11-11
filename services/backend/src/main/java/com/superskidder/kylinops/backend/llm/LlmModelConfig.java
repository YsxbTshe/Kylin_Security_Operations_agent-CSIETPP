package com.superskidder.kylinops.backend.llm;

import java.time.LocalDateTime;

public record LlmModelConfig(
        String id,
        String providerType,
        String groupName,
        String baseUrl,
        String apiKey,
        String model,
        boolean enabled,
        int timeoutMs,
        int retryCount,
        String thinkingLevels,
        String thinkingDefault,
        String updatedBy,
        LocalDateTime updatedAt
) {
    public java.util.List<String> supportedThinkingLevels() {
        return ThinkingLevels.parseSupported(thinkingLevels);
    }

    public String defaultThinkingLevelOrOff() {
        return ThinkingLevels.clamp(thinkingDefault, supportedThinkingLevels(), ThinkingLevels.OFF);
    }
}
