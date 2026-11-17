package com.superskidder.kylinops.backend.agentruntime.transport;

public record NodeInvocationResult(
        boolean success,
        String structuredResultJson,
        String errorCode,
        String message,
        boolean retryable
) {
}
