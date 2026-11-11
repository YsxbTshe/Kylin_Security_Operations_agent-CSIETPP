package com.superskidder.kylinops.backend.abac;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.ResourcePolicy.Effect;
import com.superskidder.kylinops.backend.abac.ResourcePolicy.SubjectType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AbacPolicyEngineTest {

    private final AbacPolicyEngine engine = new AbacPolicyEngine(new ObjectMapper());

    private ResourcePolicy allow(String id, SubjectType st, String subject, String type, String pattern,
                                 String action, String condition, int specificity) {
        return new ResourcePolicy(id, st, subject, type, pattern, action, Effect.ALLOW, condition, specificity);
    }

    private AbacRequest req(String action, String resourceId, Map<String, String> ctx) {
        return new AbacRequest("user-a", List.of("secops"), "HOST", resourceId, action, ctx);
    }

    @Test
    void defaultDenyWhenNoPolicyMatches() {
        AbacDecision d = engine.decide(List.of(), req("operate", "prod-01", Map.of()));
        assertThat(d.allowed()).isFalse();
    }

    @Test
    void roleScopedAllowGrantsAccess() {
        var policies = List.of(allow("p1", SubjectType.ROLE, "secops", "HOST", "prod-*", "operate", null, 10));
        assertThat(engine.decide(policies, req("operate", "prod-01", Map.of())).allowed()).isTrue();
        // 资源模式不匹配 → 默认拒绝
        assertThat(engine.decide(policies, req("operate", "dev-01", Map.of())).allowed()).isFalse();
        // 动作不匹配 → 默认拒绝
        assertThat(engine.decide(policies, req("delete", "prod-01", Map.of())).allowed()).isFalse();
    }

    @Test
    void denyOverridesAllowEvenWhenBothMatch() {
        var policies = List.of(
                allow("p-allow", SubjectType.ROLE, "secops", "HOST", "*", "operate", null, 5),
                new ResourcePolicy("p-deny", SubjectType.ROLE, "secops", "HOST", "prod-db-*", "operate",
                        Effect.DENY, null, 20));
        // 通配允许存在，但对 prod-db-* 有显式拒绝 → 拒绝压过允许（高危漏放=0）
        assertThat(engine.decide(policies, req("operate", "prod-db-01", Map.of())).allowed()).isFalse();
        // 非 prod-db 的生产机仍被通配允许放行
        assertThat(engine.decide(policies, req("operate", "prod-web-01", Map.of())).allowed()).isTrue();
    }

    @Test
    void conditionCapsRiskLevel() {
        var policies = List.of(allow("p-r3", SubjectType.ROLE, "secops", "HOST", "prod-*", "operate",
                "{\"maxRisk\":\"R3\"}", 10));
        assertThat(engine.decide(policies, req("operate", "prod-01", Map.of("risk", "R3"))).allowed()).isTrue();
        // R4 超过策略允许的 R3 → 条件不满足 → 默认拒绝
        assertThat(engine.decide(policies, req("operate", "prod-01", Map.of("risk", "R4"))).allowed()).isFalse();
        // 策略声明了 maxRisk，请求未带 risk → 条件不满足（不再放行）
        assertThat(engine.decide(policies, req("operate", "prod-01", Map.of())).allowed()).isFalse();
    }

    @Test
    void userScopedPolicyMatchesSpecificUser() {
        var policies = List.of(allow("p-user", SubjectType.USER, "user-a", "DATABASE", "billing", "read", null, 10));
        AbacRequest r = new AbacRequest("user-a", List.of(), "DATABASE", "billing", "read", Map.of());
        assertThat(engine.decide(policies, r).allowed()).isTrue();
        AbacRequest other = new AbacRequest("user-b", List.of(), "DATABASE", "billing", "read", Map.of());
        assertThat(engine.decide(policies, other).allowed()).isFalse();
    }
}
