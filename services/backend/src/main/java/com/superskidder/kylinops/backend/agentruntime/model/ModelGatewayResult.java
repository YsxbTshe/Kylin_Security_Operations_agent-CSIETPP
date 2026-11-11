package com.superskidder.kylinops.backend.agentruntime.model;

import com.superskidder.kylinops.backend.llm.LlmToolCall;
import java.util.List;

public record ModelGatewayResult(
        String content,
        List<LlmToolCall> toolCalls,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long ttftMillis,
        long latencyMillis,
        int retryCount
) {
    public ModelGatewayResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
