package com.superskidder.kylinops.backend.agentruntime.persistence;

public record ToolExecutionRef(String toolInvocationId, String executionAttemptId, String canonicalArgumentsJson) {
}
