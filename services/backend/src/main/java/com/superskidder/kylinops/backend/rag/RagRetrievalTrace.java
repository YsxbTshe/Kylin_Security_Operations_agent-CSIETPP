package com.superskidder.kylinops.backend.rag;

import java.util.List;

/**
 * 混合检索的可解释轨迹：把 {@code RagService.retrieveContext} 内部原本算完即丢的中间结果
 * 显式暴露出来，用于检索预览的"多路候选 → 融合 → 最终结果"展示与答辩追问。
 *
 * <p>这里的每个数字都来自真实检索，不做任何补齐或美化：某一路没有候选就是空列表，
 * 关键词通道降级到 JVM 词项重合时 {@code detail} 会如实写明，不伪装成 Elasticsearch BM25。
 */
public record RagRetrievalTrace(
        String question,
        int rrfK,
        int candidateN,
        int topK,
        List<Channel> channels,
        List<Fused> fused,
        Rerank rerank
) {

    /**
     * 重排阶段。{@code status} 有三种取值，界面必须如实区分，不能一律画成"已重排"：
     * {@code DISABLED} 未启用、{@code APPLIED} 已重排、{@code FALLBACK} 调用失败已退回融合顺序。
     */
    public record Rerank(
            String status,
            String model,
            int inputCount,
            List<RerankedItem> items
    ) {
        public static Rerank disabled() {
            return new Rerank("DISABLED", "", 0, List.of());
        }

        public static Rerank fallback(String model, int inputCount) {
            return new Rerank("FALLBACK", model, inputCount, List.of());
        }
    }

    /** 重排后的一条结果，同时保留融合阶段的名次，便于展示"精排把谁提上来了"。 */
    public record RerankedItem(
            String chunkId,
            String title,
            String snippet,
            int rank,
            int fusedRank,
            double relevance,
            boolean selected
    ) {
    }

    /** 一路检索通道及其候选集。 */
    public record Channel(
            String key,
            String label,
            /** 该路的真实实现说明，降级时如实反映 */
            String detail,
            double weight,
            List<Candidate> candidates
    ) {
    }

    /** 通道内的一条候选。{@code rank} 从 1 开始，{@code score} 是该通道的原始分（量纲各通道不同）。 */
    public record Candidate(
            String chunkId,
            String title,
            String snippet,
            int rank,
            double score
    ) {
    }

    /**
     * 融合后的排序结果。{@code contributions} 是这条分片在各通道拿到的 RRF 贡献分，
     * 相加即 {@code score}；{@code selected} 表示是否进入 topK。
     */
    public record Fused(
            String chunkId,
            String title,
            String snippet,
            int rank,
            double score,
            boolean selected,
            List<Contribution> contributions
    ) {
    }

    /** 某条分片在某一通道的贡献：weight / (rrfK + rank)。 */
    public record Contribution(
            String channel,
            int rank,
            double points
    ) {
    }
}
