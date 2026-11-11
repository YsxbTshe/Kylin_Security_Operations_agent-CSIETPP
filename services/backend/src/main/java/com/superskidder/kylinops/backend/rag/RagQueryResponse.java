package com.superskidder.kylinops.backend.rag;

import java.util.List;

public record RagQueryResponse(
        String answer,
        List<RagDocumentItem> references,
        List<RagChunkItem> chunks,
        List<RagMemoryItem> memories,
        List<RagGraphEdgeItem> graphEdges,
        /** 混合检索的可解释轨迹；仅在请求显式要求时非空 */
        RagRetrievalTrace trace
) {
}
