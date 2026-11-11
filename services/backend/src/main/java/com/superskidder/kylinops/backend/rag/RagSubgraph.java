package com.superskidder.kylinops.backend.rag;

import java.util.List;

public record RagSubgraph(
        List<RagGraphNodeItem> nodes,
        List<RagGraphEdgeView> edges
) {
    public static RagSubgraph empty() {
        return new RagSubgraph(List.of(), List.of());
    }
}
