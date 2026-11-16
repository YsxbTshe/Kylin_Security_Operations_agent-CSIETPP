package com.superskidder.kylinops.backend.abac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.ResourcePolicy.Effect;
import com.superskidder.kylinops.backend.policy.RiskCode;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * ABAC 判定引擎。纯函数：给定候选策略与请求，得出 ALLOW/DENY。
 *
 * 判定规则（安全优先）：
 *  1. 只考虑「主体匹配 ∧ 资源类型匹配 ∧ 资源模式匹配 ∧ 动作匹配 ∧ 条件满足」的策略；
 *  2. 任一匹配的 DENY 立即拒绝（deny 覆盖 allow）；
 *  3. 否则存在匹配的 ALLOW 才允许；
 *  4. 否则默认拒绝（fail-closed）。
 *
 * 「高危漏放=0」由 2 与 4 共同保证：没有明确授权，一律拒；有任何拒绝，压过一切允许。
 * 抽成纯函数以便快速、彻底地回归——它是资源级授权是否可信的关键判据。
 */
@Component
public final class AbacPolicyEngine {

    private final ObjectMapper objectMapper;

    public AbacPolicyEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AbacDecision decide(List<ResourcePolicy> policies, AbacRequest request) {
        ResourcePolicy bestAllow = null;
        for (ResourcePolicy policy : policies == null ? List.<ResourcePolicy>of() : policies) {
            if (!applies(policy, request)) {
                continue;
            }
            if (policy.effect() == Effect.DENY) {
                return AbacDecision.deny("显式拒绝策略 " + policy.id(), policy.id());
            }
            if (bestAllow == null || policy.specificity() > bestAllow.specificity()) {
                bestAllow = policy;
            }
        }
        if (bestAllow != null) {
            return AbacDecision.allow(bestAllow.id());
        }
        return AbacDecision.deny("无匹配的允许策略（默认拒绝）", null);
    }

    private boolean applies(ResourcePolicy policy, AbacRequest request) {
        return subjectMatches(policy, request)
                && equalsIgnoreCaseTrimmed(policy.resourceType(), request.resourceType())
                && patternMatches(policy.resourcePattern(), request.resourceId())
                && actionMatches(policy.action(), request.action())
                && conditionSatisfied(policy.conditionJson(), request);
    }

    private boolean subjectMatches(ResourcePolicy policy, AbacRequest request) {
        String subjectId = policy.subjectId() == null ? "" : policy.subjectId().trim();
        return switch (policy.subjectType()) {
            case USER -> subjectId.equalsIgnoreCase(request.userId() == null ? "" : request.userId().trim());
            case ROLE -> request.roles() != null && request.roles().stream()
                    .anyMatch(role -> role != null && role.trim().equalsIgnoreCase(subjectId));
        };
    }

    private boolean actionMatches(String policyAction, String requestAction) {
        if (policyAction == null) {
            return false;
        }
        return "*".equals(policyAction.trim()) || equalsIgnoreCaseTrimmed(policyAction, requestAction);
    }

    /** 资源模式：支持精确匹配与结尾通配（如 "prod-*" / "*"）。 */
    private boolean patternMatches(String pattern, String resourceId) {
        if (pattern == null) {
            return false;
        }
        String p = pattern.trim();
        String r = resourceId == null ? "" : resourceId.trim();
        if ("*".equals(p)) {
            return true;
        }
        if (p.endsWith("*")) {
            return r.toLowerCase(Locale.ROOT).startsWith(p.substring(0, p.length() - 1).toLowerCase(Locale.ROOT));
        }
        return p.equalsIgnoreCase(r);
    }

    /**
     * 条件匹配。当前支持 maxRisk：请求上下文的 risk 不得超过策略允许的最高风险。
     * 条件解析失败按不满足处理（收紧不放宽）。
     */
    private boolean conditionSatisfied(String conditionJson, AbacRequest request) {
        if (conditionJson == null || conditionJson.isBlank()) {
            return true;
        }
        try {
            JsonNode condition = objectMapper.readTree(conditionJson);
            JsonNode maxRisk = condition.get("maxRisk");
            if (maxRisk != null && !maxRisk.isNull()) {
                String requestRisk = request.context() == null ? null : request.context().get("risk");
                if (requestRisk == null || requestRisk.isBlank()) {
                    return false; // 策略声明了风险上限，请求必须显式带风险，缺省按不满足处理
                }
                return riskRank(requestRisk) <= riskRank(maxRisk.asText());
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private int riskRank(String risk) {
        try {
            return RiskCode.valueOf(risk.trim().toUpperCase(Locale.ROOT)).ordinal();
        } catch (Exception exception) {
            return Integer.MAX_VALUE; // 认不出的风险当作最高，条件更难满足
        }
    }

    private boolean equalsIgnoreCaseTrimmed(String a, String b) {
        return (a == null ? "" : a.trim()).equalsIgnoreCase(b == null ? "" : b.trim());
    }
}
