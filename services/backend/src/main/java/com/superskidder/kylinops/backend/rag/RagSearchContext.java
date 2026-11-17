package com.superskidder.kylinops.backend.rag;

import java.util.List;

public record RagSearchContext(
        List<RagChunkItem> chunks,
        List<RagDocumentItem> references,
        List<RagMemoryItem> memories,
        List<RagGraphEdgeItem> graphEdges
) {
}
