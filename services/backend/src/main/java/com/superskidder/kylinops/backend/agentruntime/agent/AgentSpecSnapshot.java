package com.superskidder.kylinops.backend.agentruntime.agent;

import com.superskidder.kylinops.backend.crypto.Sm3;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * SubAgent 规格快照。preset 规格从 agent_definition_version 冻结而来；
 * 动态规格使用同一结构在内存中传递，绝不落库（preset 与 dynamic 分离）。
 */
public record AgentSpecSnapshot(
        String instructions,
        List<String> allowedSkills,
        Map<String, Object> defaultBudget,
        String contentDigest
) {

    public AgentSpecSnapshot {
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("agent spec instructions must not be blank");
        }
        if (contentDigest == null || contentDigest.isBlank()) {
            throw new IllegalArgumentException("agent spec content digest must not be blank");
        }
        allowedSkills = allowedSkills == null ? List.of() : List.copyOf(allowedSkills);
        defaultBudget = defaultBudget == null ? Map.of() : Map.copyOf(defaultBudget);
    }

    public static AgentSpecSnapshot preset(
            String instructions,
            List<String> allowedSkills,
            Map<String, Object> defaultBudget,
            String contentDigest
    ) {
        return new AgentSpecSnapshot(instructions, allowedSkills, defaultBudget, contentDigest);
    }

    /** 动态规格：摘要由内容本身推导，仅用于内存传递与 definition_snapshot_json 冻结。 */
    public static AgentSpecSnapshot dynamic(
            String instructions,
            List<String> allowedSkills,
            Map<String, Object> defaultBudget
    ) {
        List<String> skills = allowedSkills == null ? List.of() : List.copyOf(allowedSkills);
        Map<String, Object> budget = defaultBudget == null ? Map.of() : Map.copyOf(defaultBudget);
        StringBuilder material = new StringBuilder(instructions);
        skills.stream().sorted().forEach(skill -> material.append('|').append(skill));
        budget.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> material.append('|').append(entry.getKey()).append('=').append(entry.getValue()));
        String digest = Sm3.hex(material.toString().getBytes(StandardCharsets.UTF_8));
        return new AgentSpecSnapshot(instructions, skills, budget, digest);
    }
}
