package com.superskidder.kylinops.backend.llm;

public record LlmToolCall(
        String id,
        String name,
        String argumentsJson
) {
}
