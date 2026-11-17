package com.superskidder.kylinops.backend.agentruntime.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 阶段压缩（ContextSummaryArtifact）：把最旧 N 轮消息压缩为一段确定性摘要。
 *
 * 当前实现是纯确定性提取（工具名 + 结果状态 + 关键数值），不依赖 LLM、无新外部依赖；
 * 后续可替换为模型摘要——保持 summarize(priorSummary, evictedMessages) 签名即可，
 * 调用方（AgentRunWorker）只消费摘要文本与 PromptSegment。
 */
@Component
public class ContextSummarizer {

    public static final String SEGMENT_NAME = "context-summary";

    private static final Logger log = LoggerFactory.getLogger(ContextSummarizer.class);
    private static final List<String> STATUS_KEYS = List.of("status", "state", "activeState", "outcome");
    private static final int MAX_KEY_NUMBERS = 3;

    private final ObjectMapper objectMapper;
    private final Counter compressions;
    private final Counter savedTokens;

    public ContextSummarizer(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.compressions = meterRegistry.counter("kylin.agent.context.summary.compressions");
        this.savedTokens = meterRegistry.counter("kylin.agent.context.summary.tokens.saved");
    }

    public PromptSegment summarySegment(String summaryText) {
        return new PromptSegment(SEGMENT_NAME, "system", false, summaryText,
                Map.of("source", "context-summarizer", "kind", "context-summary"));
    }

    /** 合并既有摘要与被裁撤轮次，产出新的摘要文本（确定性、可重复）。 */
    public String summarize(String priorSummary, List<Map<String, Object>> evictedMessages) {
        List<String> parts = new ArrayList<>();
        if (priorSummary != null && !priorSummary.isBlank()) {
            parts.add(priorSummary);
        }
        int roundNo = 1;
        for (List<Map<String, Object>> round : rounds(evictedMessages)) {
            List<String> calls = new ArrayList<>();
            for (Map<String, Object> message : round) {
                String role = String.valueOf(message.getOrDefault("role", ""));
                if ("assistant".equals(role)) {
                    calls.addAll(requestedToolNames(message));
                } else if ("tool".equals(role)) {
                    calls.add(toolObservation(message));
                }
            }
            if (!calls.isEmpty()) {
                parts.add(roundNo + ") " + String.join("; ", calls));
            }
            roundNo++;
        }
        return "[compressed-history] " + String.join(" | ", parts);
    }

    /** 记录一次压缩与节省的 token 数（内存计数器 + 日志，不新建端点）。 */
    public void recordCompression(long tokensSaved) {
        compressions.increment();
        if (tokensSaved > 0) {
            savedTokens.increment(tokensSaved);
        }
        log.info("context summary compression applied: tokensSaved={}", Math.max(0, tokensSaved));
    }

    public double compressionCount() {
        return compressions.count();
    }

    public double tokensSaved() {
        return savedTokens.count();
    }

    private List<String> requestedToolNames(Map<String, Object> message) {
        List<String> names = new ArrayList<>();
        if (message.get("tool_calls") instanceof List<?> toolCalls) {
            for (Object spec : toolCalls) {
                if (spec instanceof Map<?, ?> specMap && specMap.get("function") instanceof Map<?, ?> function) {
                    names.add("call " + function.get("name"));
                }
            }
        }
        return names;
    }

    private String toolObservation(Map<String, Object> message) {
        String toolName = String.valueOf(message.getOrDefault("name", "unknown-tool"));
        Map<String, Object> parsed = tryRead(String.valueOf(message.getOrDefault("content", "")));
        return toolName + " -> " + statusOf(parsed) + keyNumbers(parsed);
    }

    private String statusOf(Map<String, Object> parsed) {
        if (parsed.isEmpty()) {
            return "UNPARSED";
        }
        Object error = parsed.get("error");
        if (error != null) {
            return "FAILED(" + error + ")";
        }
        for (String key : STATUS_KEYS) {
            Object value = parsed.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
            if (value instanceof Boolean flag) {
                return flag.toString();
            }
        }
        return "OK";
    }

    private String keyNumbers(Map<String, Object> parsed) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                builder.append(' ').append(entry.getKey()).append('=').append(number);
                if (++count >= MAX_KEY_NUMBERS) {
                    break;
                }
            }
        }
        return builder.toString();
    }

    private Map<String, Object> tryRead(String content) {
        try {
            Map<String, Object> values = objectMapper.readValue(content, new TypeReference<>() {});
            return values == null ? Map.of() : new LinkedHashMap<>(values);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private List<List<Map<String, Object>>> rounds(List<Map<String, Object>> messages) {
        List<List<Map<String, Object>>> rounds = new ArrayList<>();
        List<Map<String, Object>> current = null;
        for (Map<String, Object> message : messages) {
            boolean roundStart = "assistant".equals(String.valueOf(message.getOrDefault("role", "")));
            if (roundStart || current == null) {
                current = new ArrayList<>();
                rounds.add(current);
            }
            current.add(message);
        }
        return rounds;
    }
}
