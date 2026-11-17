package com.superskidder.kylinops.backend.rag;

/**
 * 图谱节点与知识分片的绑定关系（证据回链）。
 *
 * <p>这是抽取时就记录下来的**真实来源**：该实体是从哪些分片里被抽出来的，
 * 与"拿实体名再检索一遍"不是一回事——后者只是近似猜测，会把没参与抽取的片段也带进来。
 *
 * <p>{@code weight} 是该实体在这个分片里被提及的累计次数，可用于排序。
 */
public record RagNodeChunkItem(
        String chunkId,
        String documentId,
        String documentTitle,
        int chunkIndex,
        String title,
        String sourceType,
        String content,
        double weight
) {
}
