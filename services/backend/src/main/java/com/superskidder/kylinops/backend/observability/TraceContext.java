package com.superskidder.kylinops.backend.observability;

import com.superskidder.kylinops.backend.crypto.Sm3;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class TraceContext {

    private TraceContext() {
    }

    public static String rootSpanId(String taskId) {
        String normalized = taskId == null ? "" : taskId.trim();
        return normalized.isBlank() ? "" : "task:" + normalized;
    }

    public static String spanId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (isHex(normalized, 16) && !allZero(normalized)) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return Sm3.hex(normalized.isBlank() ? "span" : normalized).substring(0, 16);
    }

    public static String traceParent(String traceId, String spanId) {
        String normalizedSpan = spanId(spanId);
        return "00-" + w3cTraceId(traceId) + "-" + normalizedSpan + "-01";
    }

    public static String w3cTraceId(String traceId) {
        String normalized = traceId == null ? "" : traceId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("trace-")) {
            normalized = normalized.substring("trace-".length());
        }
        if (isHex(normalized, 32) && !allZero(normalized)) {
            return normalized;
        }
        return Sm3.hex(traceId == null || traceId.isBlank() ? "trace" : traceId).substring(0, 32);
    }

    public static Map<String, Object> metadata(String traceId, String spanId, String parentSpanId, String subtaskId, String agentRole) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String normalizedSpan = spanId(spanId);
        metadata.put("traceId", traceId == null ? "" : traceId);
        metadata.put("w3cTraceId", w3cTraceId(traceId));
        metadata.put("spanId", normalizedSpan);
        metadata.put("parentSpanId", parentSpanId == null ? "" : parentSpanId);
        metadata.put("traceparent", traceParent(traceId, normalizedSpan));
        metadata.put("subtaskId", subtaskId == null ? "" : subtaskId);
        metadata.put("agentRole", agentRole == null ? "" : agentRole);
        return metadata;
    }

    private static boolean isHex(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

}
