package com.superskidder.kylinops.backend.registry;

/**
 * 专家 Agent 能力授权判定（Agent IAM，决赛计划 R）。fail-closed：未注册/停用/工具越界/超风险上限
 * 一律 allowed=false。即使 Agent 被攻陷，也只能在注册封套内行动。
 */
public record AgentAuthorization(boolean allowed, String reason) {

    public static AgentAuthorization allow() {
        return new AgentAuthorization(true, "allowed");
    }

    public static AgentAuthorization deny(String reason) {
        return new AgentAuthorization(false, reason);
    }
}
