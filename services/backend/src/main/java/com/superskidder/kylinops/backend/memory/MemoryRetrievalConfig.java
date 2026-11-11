package com.superskidder.kylinops.backend.memory;

/**
 * 复合召回配置。在语义相似、图实体重合、时近衰减、重要度四路之上，新增：
 * - 任务相似度（第五路，ADR-001 §2.7）：执行相似工作时找到既往任务记录；
 * - 可信度加权：按出处/验证/有效期/是否被取代对相关性分数加权。
 * 逐路开关驱动记忆基准的消融实验。
 */
public record MemoryRetrievalConfig(
        boolean useSemantic,
        boolean useGraph,
        boolean useRecency,
        boolean useImportance,
        boolean useTaskAffinity,
        boolean useTrust,
        double weightSemantic,
        double weightGraph,
        double weightRecency,
        double weightImportance,
        double weightTaskAffinity,
        int graphHops,
        double recencyLambdaPerHour
) {
    public static MemoryRetrievalConfig full() {
        return new MemoryRetrievalConfig(true, true, true, true, true, true,
                1.0, 0.4, 0.3, 0.2, 0.5, 2, 0.01);
    }

    public static MemoryRetrievalConfig semanticOnly() {
        return new MemoryRetrievalConfig(true, false, false, false, false, false,
                1.0, 0.0, 0.0, 0.0, 0.0, 2, 0.01);
    }

    /** Generative-Agents style baseline: relevance + recency + importance, no graph. */
    public static MemoryRetrievalConfig generativeAgents() {
        return new MemoryRetrievalConfig(true, false, true, true, false, false,
                1.0, 0.0, 0.3, 0.2, 0.0, 2, 0.01);
    }

    /** 四路复合（语义+图+时近+重要度），不含任务相似度与可信度加权——用于消融对照。 */
    public static MemoryRetrievalConfig compositeNoTrust() {
        return new MemoryRetrievalConfig(true, true, true, true, false, false,
                1.0, 0.4, 0.3, 0.2, 0.0, 2, 0.01);
    }
}
