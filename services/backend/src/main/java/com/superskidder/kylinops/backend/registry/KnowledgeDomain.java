package com.superskidder.kylinops.backend.registry;

/** 知识域（决赛计划 R）：专家 Agent 可绑定的知识范围/领域。 */
public record KnowledgeDomain(
        String id,
        String code,
        String name,
        String description
) {
}
