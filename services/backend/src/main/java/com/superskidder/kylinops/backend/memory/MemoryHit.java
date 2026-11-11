package com.superskidder.kylinops.backend.memory;

/**
 * 一条召回命中。除复合相关性分数外，还带出可信度与生命周期信号（ADR-001 §2.7）：
 * 召回不再假装「记忆就是当前真相」，而是把出处、验证、有效期、是否被取代、是否冲突
 * 一并暴露给调用方，由使用处结合可信度决定采信程度。
 */
public record MemoryHit(
        String id,
        String memoryType,
        String memorySource,
        String title,
        String content,
        int importance,
        double score,
        double semantic,
        double graph,
        double recency,
        double taskAffinity,
        double trust,
        boolean verified,
        boolean superseded,
        boolean expired,
        boolean conflicting,
        String scope
) {
}
