package com.superskidder.kylinops.backend.agentruntime.context;

import java.util.Map;

public record PromptSegment(
        String name,
        String role,
        boolean stable,
        String content,
        Map<String, String> provenance
) {
    public PromptSegment {
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }
}
