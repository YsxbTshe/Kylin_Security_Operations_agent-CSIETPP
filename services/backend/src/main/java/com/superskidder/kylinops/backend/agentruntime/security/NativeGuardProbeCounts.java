package com.superskidder.kylinops.backend.agentruntime.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;

/**
 * 从心跳 {@code native_guard_json.items[]} 统计探针风险数。
 * 口径与节点 {@code nativeGuardOverall} / 主机详情页色阶一致。
 */
public record NativeGuardProbeCounts(int critical, int warning) {

    public static final NativeGuardProbeCounts ZERO = new NativeGuardProbeCounts(0, 0);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> CRITICAL = Set.of("disabled", "inactive", "failed", "off", "error");
    private static final Set<String> WARNING = Set.of("permissive", "unknown");

    public static NativeGuardProbeCounts fromJson(String json) {
        if (json == null || json.isBlank()) {
            return ZERO;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                return ZERO;
            }
            int critical = 0;
            int warning = 0;
            for (JsonNode item : items) {
                if (item != null && item.has("supported") && !item.get("supported").asBoolean(true)) {
                    continue;
                }
                String status = item == null ? "" : item.path("status").asText("").trim().toLowerCase(Locale.ROOT);
                if (CRITICAL.contains(status)) {
                    critical++;
                } else if (WARNING.contains(status)) {
                    warning++;
                }
            }
            return new NativeGuardProbeCounts(critical, warning);
        } catch (Exception ignored) {
            return ZERO;
        }
    }
}
