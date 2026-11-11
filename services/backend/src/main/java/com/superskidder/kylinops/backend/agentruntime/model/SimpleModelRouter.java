package com.superskidder.kylinops.backend.agentruntime.model;

import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.crypto.Sm3;
import com.superskidder.kylinops.backend.llm.LlmModelConfig;
import com.superskidder.kylinops.backend.llm.PlatformLlmConfigService;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SimpleModelRouter {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformLlmConfigService configService;

    public SimpleModelRouter(JdbcTemplate jdbcTemplate, PlatformLlmConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    public RoutedModel route(String tenantId, String objective, String explicitProfileId, String requestedReasoning) {
        List<Profile> profiles = jdbcTemplate.query("""
                select id, llm_config_id, routing_description, cache_policy, priority
                from model_profile
                where tenant_id = ? and enabled = true
                order by priority asc, id asc
                """, (rs, rowNum) -> new Profile(rs.getString("id"), rs.getString("llm_config_id"),
                rs.getString("routing_description"), rs.getString("cache_policy"), rs.getInt("priority")), tenantId);
        Profile selected;
        if (explicitProfileId != null && !explicitProfileId.isBlank()) {
            selected = profiles.stream().filter(item -> item.id().equals(explicitProfileId)).findFirst()
                    .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "Model profile is not enabled for tenant"));
        } else if (profiles.isEmpty()) {
            LlmModelConfig fallback = configService.currentConfig();
            return routed("", fallback, requestedReasoning, "AUTOMATIC_PREFIX", "single enabled legacy model config");
        } else if (profiles.size() == 1) {
            selected = profiles.get(0);
        } else {
            String normalizedObjective = objective == null ? "" : objective.toLowerCase(Locale.ROOT);
            selected = profiles.stream().max(Comparator
                    .comparingInt((Profile profile) -> keywordScore(normalizedObjective, profile.routingDescription()))
                    .thenComparingInt(profile -> -profile.priority())).orElse(profiles.get(0));
        }
        return routed(selected.id(), configService.configById(selected.configId()), requestedReasoning,
                selected.cachePolicy(), profiles.size() <= 1 ? "only eligible model profile" : "routing-description match");
    }

    /**
     * 备选模型：同租户其余启用的 profile，按 priority 升序。用于主 profile 调用失败后的
     * 逐个重试——"所有模型 profile 均不可用"必须是被真正验证过的结论，而不是只试了一个就
     * 宣布不可用。任一 profile 的配置读不出来即跳过（它本来也用不了）。
     */
    public List<RoutedModel> alternatives(String tenantId, String excludeProfileId, String requestedReasoning) {
        List<Profile> profiles = jdbcTemplate.query("""
                select id, llm_config_id, routing_description, cache_policy, priority
                from model_profile
                where tenant_id = ? and enabled = true
                order by priority asc, id asc
                """, (rs, rowNum) -> new Profile(rs.getString("id"), rs.getString("llm_config_id"),
                rs.getString("routing_description"), rs.getString("cache_policy"), rs.getInt("priority")), tenantId);
        List<RoutedModel> candidates = new java.util.ArrayList<>();
        for (Profile profile : profiles) {
            if (excludeProfileId != null && profile.id().equals(excludeProfileId)) {
                continue;
            }
            try {
                candidates.add(routed(profile.id(), configService.configById(profile.configId()), requestedReasoning,
                        profile.cachePolicy(), "failover after primary model profile was unavailable"));
            } catch (RuntimeException ignored) {
                // 配置缺失/停用的 profile 不是可用备选，跳过即可。
            }
        }
        return List.copyOf(candidates);
    }

    private RoutedModel routed(String profileId, LlmModelConfig config, String requestedReasoning, String cachePolicy, String reason) {
        String reasoning = requestedReasoning == null || requestedReasoning.isBlank()
                ? config.defaultThinkingLevelOrOff()
                : com.superskidder.kylinops.backend.llm.ThinkingLevels.clamp(
                        requestedReasoning, config.supportedThinkingLevels(), config.defaultThinkingLevelOrOff());
        String digestSource = config.providerType() + "\n" + config.baseUrl() + "\n" + config.model() + "\n" + config.apiKey();
        return new RoutedModel(profileId, config, reasoning, cachePolicy, reason,
                Sm3.hex(digestSource.getBytes(StandardCharsets.UTF_8)));
    }

    private int keywordScore(String objective, String description) {
        if (description == null || description.isBlank()) {
            return 0;
        }
        int score = 0;
        for (String token : description.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && objective.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private record Profile(String id, String configId, String routingDescription, String cachePolicy, int priority) {}
}
