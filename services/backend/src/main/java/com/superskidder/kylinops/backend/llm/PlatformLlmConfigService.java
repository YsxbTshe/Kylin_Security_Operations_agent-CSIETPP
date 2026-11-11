package com.superskidder.kylinops.backend.llm;

import com.superskidder.kylinops.backend.common.AppException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PlatformLlmConfigService {

    private final JdbcTemplate jdbcTemplate;

    public PlatformLlmConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<LlmModelConfigView> listViews() {
        return jdbcTemplate.query("""
                select id, provider_type, group_name, base_url, api_key, model, enabled, timeout_ms, retry_count, thinking_levels, thinking_default, updated_by, updated_at
                from app_platform_llm_config
                order by group_name asc, updated_at desc, id asc
                """, (resultSet, rowNum) -> toView(new LlmModelConfig(
                resultSet.getString("id"),
                resultSet.getString("provider_type"),
                resultSet.getString("group_name"),
                resultSet.getString("base_url"),
                resultSet.getString("api_key"),
                resultSet.getString("model"),
                resultSet.getBoolean("enabled"),
                resultSet.getInt("timeout_ms"),
                resultSet.getInt("retry_count"),
                resultSet.getString("thinking_levels"),
                resultSet.getString("thinking_default"),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("updated_at").toLocalDateTime()
        )));
    }

    public LlmModelConfig currentConfig() {
        List<LlmModelConfig> configs = jdbcTemplate.query("""
                select id, provider_type, group_name, base_url, api_key, model, enabled, timeout_ms, retry_count, thinking_levels, thinking_default, updated_by, updated_at
                from app_platform_llm_config
                where enabled = true
                order by updated_at desc, id asc
                """, (resultSet, rowNum) -> new LlmModelConfig(
                resultSet.getString("id"),
                resultSet.getString("provider_type"),
                resultSet.getString("group_name"),
                resultSet.getString("base_url"),
                resultSet.getString("api_key"),
                resultSet.getString("model"),
                resultSet.getBoolean("enabled"),
                resultSet.getInt("timeout_ms"),
                resultSet.getInt("retry_count"),
                resultSet.getString("thinking_levels"),
                resultSet.getString("thinking_default"),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("updated_at").toLocalDateTime()
        ));

        if (configs.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "当前没有已启用的模型");
        }
        return configs.get(0);
    }

    public LlmModelConfig configById(String configId) {
        LlmModelConfig config = requireConfig(configId);
        if (!config.enabled()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "所选模型未启用");
        }
        return config;
    }

    public LlmModelConfigView create(String operator, CreateLlmModelConfigRequest request) {
        NormalizedConfig normalized = normalizeForSave(
                request.providerType(),
                request.groupName(),
                request.baseUrl(),
                request.apiKey(),
                request.model(),
                request.enabled(),
                request.timeoutMs(),
                request.retryCount(),
                request.thinkingLevels(),
                request.thinkingDefault(),
                null
        );

        String id = "llm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                insert into app_platform_llm_config (
                    id, provider_type, group_name, base_url, api_key, model, enabled, timeout_ms, retry_count, thinking_levels, thinking_default, updated_by, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                normalized.providerType(),
                normalized.groupName(),
                normalized.baseUrl(),
                normalized.apiKey(),
                normalized.model(),
                normalized.enabled(),
                normalized.timeoutMs(),
                normalized.retryCount(),
                normalized.thinkingLevels(),
                normalized.thinkingDefault(),
                operator,
                Timestamp.valueOf(now)
        );
        return getView(id);
    }

    public LlmModelConfigView update(String operator, String configId, UpdateLlmModelConfigRequest request) {
        LlmModelConfig current = requireConfig(configId);
        NormalizedConfig normalized = normalizeForSave(
                request.providerType(),
                request.groupName(),
                request.baseUrl(),
                request.apiKey(),
                request.model(),
                request.enabled(),
                request.timeoutMs(),
                request.retryCount(),
                request.thinkingLevels(),
                request.thinkingDefault(),
                current
        );

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                update app_platform_llm_config
                set provider_type = ?, group_name = ?, base_url = ?, api_key = ?, model = ?, enabled = ?,
                    timeout_ms = ?, retry_count = ?, thinking_levels = ?, thinking_default = ?, updated_by = ?, updated_at = ?
                where id = ?
                """,
                normalized.providerType(),
                normalized.groupName(),
                normalized.baseUrl(),
                normalized.apiKey(),
                normalized.model(),
                normalized.enabled(),
                normalized.timeoutMs(),
                normalized.retryCount(),
                normalized.thinkingLevels(),
                normalized.thinkingDefault(),
                operator,
                Timestamp.valueOf(now),
                configId
        );
        return getView(configId);
    }

    public void delete(String configId) {
        int deleted = jdbcTemplate.update("delete from app_platform_llm_config where id = ?", configId);
        if (deleted == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "模型配置不存在");
        }
    }

    private LlmModelConfigView getView(String configId) {
        return toView(requireConfig(configId));
    }

    private LlmModelConfig requireConfig(String configId) {
        List<LlmModelConfig> rows = jdbcTemplate.query("""
                select id, provider_type, group_name, base_url, api_key, model, enabled, timeout_ms, retry_count, thinking_levels, thinking_default, updated_by, updated_at
                from app_platform_llm_config
                where id = ?
                """, (resultSet, rowNum) -> new LlmModelConfig(
                resultSet.getString("id"),
                resultSet.getString("provider_type"),
                resultSet.getString("group_name"),
                resultSet.getString("base_url"),
                resultSet.getString("api_key"),
                resultSet.getString("model"),
                resultSet.getBoolean("enabled"),
                resultSet.getInt("timeout_ms"),
                resultSet.getInt("retry_count"),
                resultSet.getString("thinking_levels"),
                resultSet.getString("thinking_default"),
                resultSet.getString("updated_by"),
                resultSet.getTimestamp("updated_at").toLocalDateTime()
        ), configId);

        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "模型配置不存在");
        }
        return rows.get(0);
    }

    private LlmModelConfigView toView(LlmModelConfig config) {
        return new LlmModelConfigView(
                config.id(),
                config.providerType(),
                config.groupName(),
                config.baseUrl(),
                config.model(),
                config.enabled(),
                config.timeoutMs(),
                config.retryCount(),
                config.supportedThinkingLevels(),
                config.defaultThinkingLevelOrOff(),
                config.apiKey() != null && !config.apiKey().isBlank(),
                mask(config.apiKey()),
                config.updatedBy(),
                format(config.updatedAt())
        );
    }

    private NormalizedConfig normalizeForSave(
            String providerTypeValue,
            String groupNameValue,
            String baseUrlValue,
            String apiKeyValue,
            String modelValue,
            Boolean enabledValue,
            Integer timeoutMsValue,
            Integer retryCountValue,
            String thinkingLevelsValue,
            String thinkingDefaultValue,
            LlmModelConfig current
    ) {
        String providerType = normalizeProviderType(providerTypeValue, current == null ? null : current.providerType());
        String groupName = normalize(groupNameValue, current == null ? "默认分组" : current.groupName());
        String model = normalize(modelValue, current == null ? "" : current.model());
        String apiKey;
        if (current == null) {
            apiKey = apiKeyValue == null ? "" : apiKeyValue.trim();
        } else {
            apiKey = apiKeyValue == null || apiKeyValue.isBlank()
                    ? current.apiKey()
                    : apiKeyValue.trim();
        }
        boolean enabled = enabledValue == null ? current != null && current.enabled() : enabledValue;
        int timeoutMs = timeoutMsValue == null ? (current == null ? 15000 : current.timeoutMs()) : timeoutMsValue;
        int retryCount = retryCountValue == null ? (current == null ? 0 : current.retryCount()) : retryCountValue;

        // 思考等级:每模型自定义有序档位 token;规整去空去重,默认档位夹到支持集内。
        java.util.List<String> supported = ThinkingLevels.parseSupported(
                thinkingLevelsValue != null ? thinkingLevelsValue
                        : (current == null ? ThinkingLevels.OFF : current.thinkingLevels()));
        if (supported.isEmpty()) {
            supported = java.util.List.of(ThinkingLevels.OFF);
        }
        String thinkingDefaultName = ThinkingLevels.clamp(
                thinkingDefaultValue != null ? thinkingDefaultValue
                        : (current == null ? ThinkingLevels.OFF : current.thinkingDefault()),
                supported, ThinkingLevels.OFF);
        String thinkingLevelsCsv = ThinkingLevels.toCsv(supported);

        if (groupName.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "分组不能为空");
        }
        if (model.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "模型名不能为空");
        }
        if (timeoutMs <= 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "超时时长必须大于 0");
        }
        if (retryCount < 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "重试次数不能小于 0");
        }

        String baseUrl = resolveBaseUrl(providerType, normalize(baseUrlValue, current == null ? "" : current.baseUrl()));
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "设为默认模型前必须填写 API Key");
        }

        return new NormalizedConfig(
                providerType,
                groupName,
                baseUrl,
                apiKey == null ? "" : apiKey,
                model,
                enabled,
                timeoutMs,
                retryCount,
                thinkingLevelsCsv,
                thinkingDefaultName
        );
    }

    private String resolveBaseUrl(String providerType, String customBaseUrl) {
        return switch (providerType) {
            case "DEEPSEEK" -> "https://api.deepseek.com/v1";
            case "ANTHROPIC" -> customBaseUrl.isBlank() ? "https://api.anthropic.com/v1" : customBaseUrl;
            case "OPENAI_COMPATIBLE", "CUSTOM" -> {
                if (customBaseUrl.isBlank()) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "OpenAI compatible 模型必须填写接口地址");
                }
                yield customBaseUrl;
            }
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "不支持的模型提供商");
        };
    }

    private String normalizeProviderType(String providerTypeValue, String fallback) {
        String resolved = normalize(providerTypeValue, fallback == null ? "OPENAI_COMPATIBLE" : fallback);
        return resolved.toUpperCase(Locale.ROOT);
    }

    private String normalize(String nextValue, String fallback) {
        if (nextValue == null) {
            return fallback == null ? "" : fallback.trim();
        }
        return nextValue.trim();
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String normalized = apiKey.trim();
        if (normalized.length() <= 8) {
            return "********";
        }
        return normalized.substring(0, 4) + "********" + normalized.substring(normalized.length() - 4);
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : value.toString().replace('T', ' ');
    }

    private record NormalizedConfig(
            String providerType,
            String groupName,
            String baseUrl,
            String apiKey,
            String model,
            boolean enabled,
            int timeoutMs,
            int retryCount,
            String thinkingLevels,
            String thinkingDefault
    ) {
    }
}
