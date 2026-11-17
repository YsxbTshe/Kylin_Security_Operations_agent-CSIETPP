package com.superskidder.kylinops.backend.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 思考等级(reasoning effort 档位)的自由 token 处理。
 *
 * 不同模型的档位不同(例如 DeepSeek 是 low/high/max,OpenAI 是 low/medium/high),因此档位
 * 不用固定枚举,而是每个模型自行声明一串**有序 token**(Faster→Smarter),请求携带其中之一。
 *
 * 约定:token `off`(大小写不敏感)表示「不思考」;其余 token 原样作为 reasoning_effort 下发。
 */
public final class ThinkingLevels {

    public static final String OFF = "off";

    private ThinkingLevels() {
    }

    public static boolean isOff(String token) {
        return token == null || token.isBlank() || OFF.equalsIgnoreCase(token.trim());
    }

    /** 解析 CSV 为有序、去空、大小写不敏感去重的 token 列表(保留首次出现的原始写法与顺序)。 */
    public static List<String> parseSupported(String csv) {
        Map<String, String> ordered = new LinkedHashMap<>();
        if (csv != null) {
            for (String token : csv.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    ordered.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                }
            }
        }
        return new ArrayList<>(ordered.values());
    }

    public static String toCsv(List<String> tokens) {
        return String.join(",", tokens);
    }

    /** 该模型是否真正提供了「可思考」的档位(存在至少一个非 off 的 token)。 */
    public static boolean hasThinking(List<String> supported) {
        return supported.stream().anyMatch(token -> !isOff(token));
    }

    /**
     * 把请求 token 夹到模型支持集内(大小写不敏感匹配,返回支持集里的原始写法);
     * 不在则回落到默认,默认也不在则回落到第一个支持项,支持集空则 off。
     */
    public static String clamp(String requested, List<String> supported, String fallback) {
        if (supported == null || supported.isEmpty()) {
            return OFF;
        }
        String match = matchIgnoreCase(requested, supported);
        if (match != null) {
            return match;
        }
        String fallbackMatch = matchIgnoreCase(fallback, supported);
        if (fallbackMatch != null) {
            return fallbackMatch;
        }
        return supported.get(0);
    }

    /** 旧布尔开关映射:开→首个非 off 里最靠 Smarter 的档位,关→off。用于向后兼容仍传布尔的调用方。 */
    public static String fromLegacyEnabled(boolean thinkingEnabled, List<String> supported) {
        if (!thinkingEnabled) {
            return OFF;
        }
        for (int i = supported.size() - 1; i >= 0; i--) {
            if (!isOff(supported.get(i))) {
                return supported.get(i);
            }
        }
        return OFF;
    }

    private static String matchIgnoreCase(String token, List<String> supported) {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (String candidate : supported) {
            if (candidate.equalsIgnoreCase(token.trim())) {
                return candidate;
            }
        }
        return null;
    }
}
