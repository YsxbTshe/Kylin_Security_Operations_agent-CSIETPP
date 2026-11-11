package com.superskidder.kylinops.backend.llm;

import com.fasterxml.jackson.databind.JsonNode;

public record LlmTool(
        String name,
        String description,
        JsonNode parameters
) {
}
