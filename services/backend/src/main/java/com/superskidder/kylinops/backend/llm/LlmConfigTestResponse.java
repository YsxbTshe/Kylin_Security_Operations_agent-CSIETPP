package com.superskidder.kylinops.backend.llm;

public record LlmConfigTestResponse(
        boolean ok,
        String message,
        String model
) {
}
