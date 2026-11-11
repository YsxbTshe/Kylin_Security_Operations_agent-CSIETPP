package com.superskidder.kylinops.backend.registry;

import java.util.List;

/**
 * 专家 Agent 注册条目（决赛计划 R）= Agent 的 IAM 身份 + 能力封套。
 * allowedTools/maxRisk/budgetTokens 构成其可行动边界；enabled 为启停开关。
 */
public record AgentRegistryEntry(
        String id,
        String agentKey,
        String name,
        String role,
        String description,
        List<String> knowledgeDomains,
        List<String> allowedTools,
        String maxRisk,
        long budgetTokens,
        boolean enabled,
        String version,
        String createdAt,
        String updatedAt
) {
}
