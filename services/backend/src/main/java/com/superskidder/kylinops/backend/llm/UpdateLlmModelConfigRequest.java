package com.superskidder.kylinops.backend.llm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateLlmModelConfigRequest(
        @Size(max = 32, message = "长度不能超过32")
        String providerType,
        @Size(max = 128, message = "长度不能超过128")
        String groupName,
        @Size(max = 512, message = "长度不能超过512")
        String baseUrl,
        @Size(max = 512, message = "长度不能超过512")
        String apiKey,
        @Size(max = 128, message = "长度不能超过128")
        String model,
        Boolean enabled,
        @Min(value = 1, message = "不能小于1")
        @Max(value = 120000, message = "不能超过120000")
        Integer timeoutMs,
        @Min(value = 0, message = "不能小于0")
        @Max(value = 5, message = "不能超过5")
        Integer retryCount,
        @Size(max = 128, message = "长度不能超过128")
        String thinkingLevels,
        @Size(max = 16, message = "长度不能超过16")
        String thinkingDefault
) {
}
