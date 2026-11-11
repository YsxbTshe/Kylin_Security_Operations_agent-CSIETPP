package com.superskidder.kylinops.backend.rag;

import java.util.List;
import java.util.Set;

/**
 * Standard IR retrieval metrics over a ranked list of chunk ids against a gold-relevant set.
 * Binary relevance (gold or not), which matches the section-provenance weak labelling used by
 * the RAG benchmark. See docs/05-评估方案.
 */
public final class RagMetrics {

    private RagMetrics() {
    }

    public static double recallAtK(List<String> ranked, Set<String> gold, int k) {
        if (gold.isEmpty()) {
            return 0.0D;
        }
        long hit = ranked.stream().limit(k).filter(gold::contains).distinct().count();
        return (double) hit / (double) gold.size();
    }

    public static double precisionAtK(List<String> ranked, Set<String> gold, int k) {
        if (k <= 0) {
            return 0.0D;
        }
        long hit = ranked.stream().limit(k).filter(gold::contains).count();
        return (double) hit / (double) Math.min(k, Math.max(1, ranked.size()));
    }

    public static double hitAtK(List<String> ranked, Set<String> gold, int k) {
        return ranked.stream().limit(k).anyMatch(gold::contains) ? 1.0D : 0.0D;
    }

    /** Mean Reciprocal Rank: 1/rank of the first relevant hit (within k), else 0. */
    public static double reciprocalRank(List<String> ranked, Set<String> gold, int k) {
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (gold.contains(ranked.get(i))) {
                return 1.0D / (i + 1);
            }
        }
        return 0.0D;
    }

    /** Average Precision over the top-k (used to average into MAP). */
    public static double averagePrecision(List<String> ranked, Set<String> gold, int k) {
        if (gold.isEmpty()) {
            return 0.0D;
        }
        int hits = 0;
        double sum = 0.0D;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (gold.contains(ranked.get(i))) {
                hits++;
                sum += (double) hits / (i + 1);
            }
        }
        return sum / (double) gold.size();
    }

    /** nDCG@k with binary gains. */
    public static double ndcgAtK(List<String> ranked, Set<String> gold, int k) {
        double dcg = 0.0D;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            if (gold.contains(ranked.get(i))) {
                dcg += 1.0D / (Math.log(i + 2) / Math.log(2));
            }
        }
        double idcg = 0.0D;
        int ideal = Math.min(k, gold.size());
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0D / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0D ? 0.0D : dcg / idcg;
    }
}
