package com.superskidder.kylinops.backend.agentruntime.agent;

/**
 * SubAgent 招募硬门禁：纯函数判定，便于单测。
 * 输入当前深度 / 活跃数 / 总数，输出 ALLOWED 或 DENIED + 结构化原因。
 */
public final class SubAgentLimits {

    /** 最大嵌套深度（根 Agent 深度为 0，DB check 约束同样限制 0–2）。 */
    public static final int MAX_DEPTH = 2;
    /** 同一 run 内允许的最大并发活跃 SubAgent 数。 */
    public static final int MAX_ACTIVE_SUB_AGENTS = 4;
    /** 同一 run 内允许的最大累计 SubAgent 数（根 Agent 不计入）。 */
    public static final int MAX_TOTAL_SUB_AGENTS = 8;

    private SubAgentLimits() {
    }

    public record Decision(boolean allowed, String errorCode, String reason) {

        public static Decision allow() {
            return new Decision(true, null, null);
        }

        public static Decision deny(String errorCode, String reason) {
            return new Decision(false, errorCode, reason);
        }
    }

    /**
     * @param childDepth          拟创建子 Agent 的深度（父深度 + 1）
     * @param activeSubAgentCount 当前 run 内活跃（QUEUED/RUNNING/WAITING）SubAgent 数
     * @param totalSubAgentCount  当前 run 内累计 SubAgent 数（不含根）
     */
    public static Decision evaluate(int childDepth, int activeSubAgentCount, int totalSubAgentCount) {
        if (childDepth > MAX_DEPTH) {
            return Decision.deny("SUB_AGENT_DEPTH_EXCEEDED",
                    "sub-agent depth " + childDepth + " exceeds max " + MAX_DEPTH);
        }
        if (activeSubAgentCount + 1 > MAX_ACTIVE_SUB_AGENTS) {
            return Decision.deny("SUB_AGENT_ACTIVE_LIMIT_EXCEEDED",
                    "active sub-agents would become " + (activeSubAgentCount + 1)
                            + ", exceeding max " + MAX_ACTIVE_SUB_AGENTS);
        }
        if (totalSubAgentCount + 1 > MAX_TOTAL_SUB_AGENTS) {
            return Decision.deny("SUB_AGENT_TOTAL_LIMIT_EXCEEDED",
                    "total sub-agents would become " + (totalSubAgentCount + 1)
                            + ", exceeding max " + MAX_TOTAL_SUB_AGENTS);
        }
        return Decision.allow();
    }
}
