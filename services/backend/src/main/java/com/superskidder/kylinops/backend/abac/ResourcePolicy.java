package com.superskidder.kylinops.backend.abac;

/**
 * 一条资源级授权策略（ABAC）。
 *
 * 主体（角色或具体用户）× 资源（类型 + 模式）× 动作 × 效果（允许/拒绝），可带条件
 * （如风险上限）。RBAC 的全局权限串管「能不能进这个功能」，本策略管「能不能对这个具体
 * 资源做这个动作」——页面隐藏不是授权，审批也不替代事前权限。
 */
public record ResourcePolicy(
        String id,
        SubjectType subjectType,
        String subjectId,
        String resourceType,
        String resourcePattern,
        String action,
        Effect effect,
        String conditionJson,
        int specificity
) {
    public enum SubjectType { ROLE, USER }

    public enum Effect { ALLOW, DENY }
}
