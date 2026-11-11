package com.superskidder.kylinops.backend.agentruntime.context;

import java.util.List;
import java.util.Map;

public record CompiledContext(
        List<PromptSegment> stablePrefix,
        List<PromptSegment> dynamicSuffix,
        String stablePrefixDigest,
        String dynamicSuffixDigest,
        String contextDigest,
        long tokenEstimate,
        boolean localPrefixCacheHit
) {
    public CompiledContext {
        stablePrefix = List.copyOf(stablePrefix);
        dynamicSuffix = List.copyOf(dynamicSuffix);
    }

    public List<Map<String, Object>> messages() {
        return java.util.stream.Stream.concat(stablePrefix.stream(), dynamicSuffix.stream())
                .map(segment -> Map.<String, Object>of("role", segment.role(), "content", segment.content()))
                .toList();
    }
}
