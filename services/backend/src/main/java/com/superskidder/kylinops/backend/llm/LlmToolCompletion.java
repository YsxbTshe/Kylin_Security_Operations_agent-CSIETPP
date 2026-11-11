package com.superskidder.kylinops.backend.llm;

import java.util.List;

public record LlmToolCompletion(
        String content,
        String reasoningContent,
        List<LlmToolCall> toolCalls
) {
}
