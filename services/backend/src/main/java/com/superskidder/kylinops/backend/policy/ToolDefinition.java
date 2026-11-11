package com.superskidder.kylinops.backend.policy;

import java.util.List;

/**
 * 策略引擎视角的工具定义：风险分级、路径白名单与补偿动作声明。
 *
 * <p>原 {@code hostcontrol.HostAgentToolDefinition} 随旧执行链路删除迁入 policy 包，
 * 成为 {@link PolicyEngine} 与工具目录之间的唯一契约；组件名与原 record 保持一致，
 * 对外 JSON 形状不变。
 */
public record ToolDefinition(
        String toolName,
        String category,
        boolean readOnly,
        boolean requiresApproval,
        String description,
        String riskCode,
        List<ResourceLock> resourceLocks,
        List<String> allowedRoots,
        List<String> blockedPaths,
        /** 补偿动作声明；由控制面权威给出，不接受端侧上报（ADR-001 §2.9.8） */
        ToolCompensation compensation
) {

    /** 工具声明的资源锁（resourceKey/锁类型），用于规划期的互斥判定。 */
    public record ResourceLock(
            String resource,
            String type
    ) {
    }

    public ToolDefinition(
            String toolName,
            String category,
            boolean readOnly,
            boolean requiresApproval,
            String description,
            String riskCode
    ) {
        this(toolName, category, readOnly, requiresApproval, description, riskCode,
                List.of(), List.of(), List.of(), ToolCompensation.none());
    }

    public ToolDefinition(
            String toolName,
            String category,
            boolean readOnly,
            boolean requiresApproval,
            String description,
            String riskCode,
            List<ResourceLock> resourceLocks,
            List<String> allowedRoots,
            List<String> blockedPaths
    ) {
        this(toolName, category, readOnly, requiresApproval, description, riskCode,
                resourceLocks, allowedRoots, blockedPaths, ToolCompensation.none());
    }
}
