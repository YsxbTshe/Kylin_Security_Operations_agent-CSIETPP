package com.superskidder.kylinops.backend.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.common.AppException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LlmCompletionService {

    private final PlatformLlmConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmCompletionService(PlatformLlmConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public boolean isEnabled() {
        try {
            return configService.currentConfig().enabled();
        } catch (AppException exception) {
            return false;
        }
    }

    public String complete(List<ChatMessage> messages) {
        LlmModelConfig config = configService.currentConfig();
        return complete(config, messages, false);
    }

    public String complete(String configId, List<ChatMessage> messages) {
        LlmModelConfig config = configService.configById(configId);
        return complete(config, messages, false);
    }

    public String completeJson(List<ChatMessage> messages) {
        LlmModelConfig config = configService.currentConfig();
        return complete(config, messages, true);
    }

    public LlmToolCompletion completeWithTools(List<ChatMessage> messages, List<LlmTool> tools) {
        LlmModelConfig config = configService.currentConfig();
        return completeWithTools(config, messages, tools);
    }

    public LlmToolCompletion completeWithTools(String configId, List<ChatMessage> messages, List<LlmTool> tools) {
        LlmModelConfig config = configService.configById(configId);
        return completeWithTools(config, messages, tools);
    }

    public LlmToolCompletion completeWithToolMessages(List<Map<String, Object>> messages, List<LlmTool> tools) {
        LlmModelConfig config = configService.currentConfig();
        return completeWithToolMessages(config, messages, tools, ThinkingLevels.OFF);
    }

    public LlmToolCompletion completeWithToolMessages(String configId, List<Map<String, Object>> messages, List<LlmTool> tools) {
        LlmModelConfig config = configService.configById(configId);
        return completeWithToolMessages(config, messages, tools, ThinkingLevels.OFF);
    }

    public LlmToolCompletion completeWithToolMessages(List<Map<String, Object>> messages, List<LlmTool> tools, String thinkingLevel) {
        LlmModelConfig config = configService.currentConfig();
        return completeWithToolMessages(config, messages, tools, thinkingLevel);
    }

    public LlmToolCompletion completeWithToolMessages(String configId, List<Map<String, Object>> messages, List<LlmTool> tools, String thinkingLevel) {
        LlmModelConfig config = configService.configById(configId);
        return completeWithToolMessages(config, messages, tools, thinkingLevel);
    }

    public String completeStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        LlmModelConfig config = configService.currentConfig();
        return completeStream(config, messages, ThinkingLevels.OFF, onDelta);
    }

    public String completeStream(String configId, List<ChatMessage> messages, Consumer<String> onDelta) {
        LlmModelConfig config = configService.configById(configId);
        return completeStream(config, messages, ThinkingLevels.OFF, onDelta);
    }

    public String completeStream(List<ChatMessage> messages, String thinkingLevel, Consumer<String> onDelta) {
        LlmModelConfig config = configService.currentConfig();
        return completeStream(config, messages, thinkingLevel, onDelta);
    }

    public String completeStream(String configId, List<ChatMessage> messages, String thinkingLevel, Consumer<String> onDelta) {
        LlmModelConfig config = configService.configById(configId);
        return completeStream(config, messages, thinkingLevel, onDelta);
    }

    public String completeStream(
            String configId,
            List<ChatMessage> messages,
            String thinkingLevel,
            Consumer<String> onReasoningDelta,
            Consumer<String> onDelta
    ) {
        LlmModelConfig config = configId == null || configId.isBlank()
                ? configService.currentConfig()
                : configService.configById(configId);
        return completeStream(config, messages, thinkingLevel, onReasoningDelta, onDelta);
    }

    /** 一张送给视觉模型的图片。以 data URL 内联，避免为临时图片额外暴露可访问地址。 */
    public record VisionImage(String mediaType, byte[] data) {
    }

    /**
     * 视觉补全：一段提示词 + 若干图片。
     *
     * <p>用独立的模型配置（{@code configId}）而不是主对话模型：主模型通常是纯文本推理模型，
     * 把图片塞给它只会得到幻觉——实测 PaddleOCR-VL 用通用 chat 协议调用时输出的是彻底的乱码，
     * 所以视觉模型必须单独配置且单独验证。
     */
    public String completeVision(String configId, String prompt, List<VisionImage> images) {
        LlmModelConfig config = configId == null || configId.isBlank()
                ? configService.currentConfig()
                : configService.configById(configId);
        if (images == null || images.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "视觉补全至少需要一张图片");
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (VisionImage image : images) {
            String dataUrl = "data:" + image.mediaType() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.data());
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        content.add(Map.of("type", "text", "text", prompt == null ? "" : prompt));
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", content));
        return completeRaw(config, messages, false);
    }

    /** 视觉模型是否已配置且启用。未配置时调用方应退回纯文字层解析，而不是报错。 */
    public boolean isVisionEnabled(String configId) {
        try {
            return configService.configById(configId).enabled();
        } catch (AppException exception) {
            return false;
        }
    }

    public LlmConfigTestResponse testConfig(String configId) {
        LlmModelConfig config = configService.configById(configId);
        String answer = complete(config, List.of(
                new ChatMessage("system", "Reply with one short sentence to confirm the model connection."),
                new ChatMessage("user", "Connection test")
        ), false);
        return new LlmConfigTestResponse(true, answer, config.model());
    }

    private String complete(LlmModelConfig config, List<ChatMessage> messages, boolean jsonMode) {
        return completeRaw(config, messages, jsonMode);
    }

    /** messages 直接作为请求体的 messages 字段序列化：纯文本走 ChatMessage 列表，多模态走 Map 列表。 */
    private String completeRaw(LlmModelConfig config, Object messages, boolean jsonMode) {
        if (!config.enabled()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "平台模型当前未启用");
        }

        String endpoint = resolveEndpoint(config.baseUrl());
        String requestBody;
        try {
            Map<String, Object> payload = baseRequestPayload(config, messages);
            if (jsonMode) {
                payload.put("response_format", Map.of("type", "json_object"));
            }
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化模型请求失败");
        }

        int attemptLimit = Math.max(1, config.retryCount() + 1);
        AppException lastError = null;
        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    lastError = modelHttpError(response.statusCode(), response.body());
                    continue;
                }

                JsonNode root;
                try {
                    root = objectMapper.readTree(response.body());
                } catch (IOException exception) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型接口响应不是兼容 JSON: " + compactResponse(response.body()));
                    continue;
                }
                String upstreamError = extractUpstreamErrorMessage(root);
                if (!upstreamError.isBlank()) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型接口返回错误: " + upstreamError);
                    continue;
                }
                JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
                if (contentNode.isMissingNode()) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型接口响应缺少 choices[0].message.content: " + compactResponse(response.body()));
                    continue;
                }
                if (contentNode.asText().isBlank()) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型接口返回内容为空");
                    continue;
                }
                return contentNode.asText();
            } catch (HttpTimeoutException exception) {
                lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型接口调用超时（" + config.timeoutMs() + "ms）");
            } catch (IOException exception) {
                lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型接口调用失败: " + compactCause(exception));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AppException(HttpStatus.BAD_GATEWAY, "模型接口调用被中断");
            }
        }
        throw lastError == null ? new AppException(HttpStatus.BAD_GATEWAY, "模型接口调用失败") : lastError;
    }

    private LlmToolCompletion completeWithTools(LlmModelConfig config, List<ChatMessage> messages, List<LlmTool> tools) {
        return completeWithToolMessages(config, toMessagePayloads(messages), tools, ThinkingLevels.OFF);
    }

    private LlmToolCompletion completeWithToolMessages(LlmModelConfig config, List<Map<String, Object>> messages, List<LlmTool> tools, String thinkingLevel) {
        if (!config.enabled()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "平台模型当前未启用");
        }

        String endpoint = resolveEndpoint(config.baseUrl());
        String requestBody;
        try {
            Map<String, Object> payload = baseRequestPayload(config, messages);
            payload.put("tools", toToolPayloads(tools));
            payload.put("tool_choice", "auto");
            applyThinkingPayload(payload, config, ThinkingLevels.clamp(thinkingLevel, config.supportedThinkingLevels(), config.defaultThinkingLevelOrOff()));
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化模型工具调用请求失败");
        }

        int attemptLimit = Math.max(1, config.retryCount() + 1);
        AppException lastError = null;
        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    lastError = modelHttpError(response.statusCode(), response.body());
                    continue;
                }

                JsonNode root;
                try {
                    root = objectMapper.readTree(response.body());
                } catch (IOException exception) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用响应不是兼容 JSON: " + compactResponse(response.body()));
                    continue;
                }
                String upstreamError = extractUpstreamErrorMessage(root);
                if (!upstreamError.isBlank()) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用接口返回错误: " + upstreamError);
                    continue;
                }
                JsonNode messageNode = root.path("choices").path(0).path("message");
                if (messageNode.isMissingNode()) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用响应缺少 choices[0].message: " + compactResponse(response.body()));
                    continue;
                }
                LlmToolCompletion completion = readToolCompletion(messageNode);
                if (completion.content().isBlank() && completion.toolCalls().isEmpty()) {
                    lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用响应为空");
                    continue;
                }
                return completion;
            } catch (HttpTimeoutException exception) {
                lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用接口超时（" + config.timeoutMs() + "ms）");
            } catch (IOException exception) {
                lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用接口失败: " + compactCause(exception));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用接口被中断");
            }
        }
        throw lastError == null ? new AppException(HttpStatus.BAD_GATEWAY, "模型工具调用接口失败") : lastError;
    }

    private String completeStream(LlmModelConfig config, List<ChatMessage> messages, String thinkingLevel, Consumer<String> onDelta) {
        return completeStream(config, messages, thinkingLevel, ignored -> {
        }, onDelta);
    }

    private String completeStream(
            LlmModelConfig config,
            List<ChatMessage> messages,
            String thinkingLevel,
            Consumer<String> onReasoningDelta,
            Consumer<String> onDelta
    ) {
        if (!config.enabled()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "平台模型当前未启用");
        }

        String endpoint = resolveEndpoint(config.baseUrl());
        String requestBody;
        try {
            Map<String, Object> payload = baseRequestPayload(config, messages);
            payload.put("stream", true);
            applyThinkingPayload(payload, config, ThinkingLevels.clamp(thinkingLevel, config.supportedThinkingLevels(), config.defaultThinkingLevelOrOff()));
            requestBody = objectMapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化模型请求失败");
        }

        int attemptLimit = Math.max(1, config.retryCount() + 1);
        AppException lastError = null;
        attemptLoop:
        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) {
                    try (InputStream errorStream = response.body()) {
                        lastError = modelHttpError(response.statusCode(), readResponseBody(errorStream));
                    }
                    continue;
                }

                ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "llm-stream-reader");
                    thread.setDaemon(true);
                    return thread;
                });
                try (InputStream inputStream = response.body();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = readStreamLine(reader, inputStream, readerExecutor, config.timeoutMs())) != null) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                            continue;
                        }

                        String payload = trimmed.substring(5).trim();
                        if (payload.isEmpty()) {
                            continue;
                        }
                        if ("[DONE]".equals(payload)) {
                            break;
                        }

                        JsonNode root;
                        try {
                            root = objectMapper.readTree(payload);
                        } catch (IOException exception) {
                            lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型流式响应片段不是兼容 JSON: " + compactResponse(payload));
                            continue attemptLoop;
                        }
                        String upstreamError = extractUpstreamErrorMessage(root);
                        if (!upstreamError.isBlank()) {
                            lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型流式接口返回错误: " + upstreamError);
                            continue attemptLoop;
                        }
                        JsonNode choice = root.path("choices").path(0);
                        if (choice.isMissingNode()) {
                            lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型流式响应片段缺少 choices[0]: " + compactResponse(payload));
                            continue attemptLoop;
                        }
                        JsonNode deltaNode = choice.path("delta");
                        if (deltaNode.isMissingNode() || deltaNode.isNull()) {
                            continue;
                        }

                        String reasoningDelta = deltaNode.path("reasoning_content").asText("");
                        if (!reasoningDelta.isEmpty()) {
                            onReasoningDelta.accept(reasoningDelta);
                        }

                        String delta = deltaNode.path("content").asText("");
                        if (!delta.isEmpty()) {
                            content.append(delta);
                            onDelta.accept(delta);
                        }
                    }

                    if (content.isEmpty()) {
                        lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型流式返回内容为空");
                        continue;
                    }
                    return content.toString();
                } finally {
                    readerExecutor.shutdownNow();
                }
            } catch (TimeoutException exception) {
                lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型流式响应超时（" + config.timeoutMs() + "ms 内无新数据）");
            } catch (HttpTimeoutException exception) {
                lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型接口建立流式连接超时（" + config.timeoutMs() + "ms）");
            } catch (IOException exception) {
                lastError = new AppException(HttpStatus.BAD_GATEWAY, "模型流式连接中断: " + compactCause(exception));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AppException(HttpStatus.BAD_GATEWAY, "模型接口调用被中断");
            }
        }
        throw lastError == null ? new AppException(HttpStatus.BAD_GATEWAY, "模型接口调用失败") : lastError;
    }

    private Map<String, Object> baseRequestPayload(LlmModelConfig config, Object messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("temperature", 0.2);
        payload.put("messages", messages);
        return payload;
    }

    private void applyThinkingPayload(Map<String, Object> payload, LlmModelConfig config, String level) {
        // 是否注入推理参数,以「模型声明支持的档位」为准(设置页按模型自定义),而非硬编码 provider。
        // 没有任何非 off 档位的模型不注入,避免把不支持的字段发给它导致 400。
        if (!ThinkingLevels.hasThinking(config.supportedThinkingLevels())) {
            return;
        }
        boolean off = ThinkingLevels.isOff(level);
        // DeepSeek 原生接口用 thinking 对象显式开关;reasoning_effort 是 OpenAI 兼容的标准字段,
        // 对声明支持思考的模型一并下发所选档位 token(如 low/high/max),off 则都不发。
        if ("DEEPSEEK".equals(config.providerType())) {
            payload.put("thinking", Map.of("type", off ? "disabled" : "enabled"));
        }
        if (!off) {
            payload.put("reasoning_effort", level.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private List<Map<String, Object>> toMessagePayloads(List<ChatMessage> messages) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("role", message.role());
            payload.put("content", message.content());
            payloads.add(payload);
        }
        return payloads;
    }

    private List<Map<String, Object>> toToolPayloads(List<LlmTool> tools) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (LlmTool tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.parameters());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "function");
            payload.put("function", function);
            payloads.add(payload);
        }
        return payloads;
    }

    private LlmToolCompletion readToolCompletion(JsonNode messageNode) {
        String content = messageNode.path("content").asText("");
        String reasoningContent = messageNode.path("reasoning_content").asText("");
        List<LlmToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = messageNode.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode item : toolCallsNode) {
                JsonNode functionNode = item.path("function");
                String name = functionNode.path("name").asText("").trim();
                if (name.isBlank()) {
                    continue;
                }
                String argumentsJson = functionNode.path("arguments").asText("{}").trim();
                String id = item.path("id").asText("").trim();
                toolCalls.add(new LlmToolCall(
                        id.isBlank() ? "call_" + (toolCalls.size() + 1) : id,
                        name,
                        argumentsJson.isBlank() ? "{}" : argumentsJson
                ));
            }
        }
        return new LlmToolCompletion(
                content == null ? "" : content.trim(),
                reasoningContent == null ? "" : reasoningContent,
                toolCalls
        );
    }

    private String readStreamLine(
            BufferedReader reader,
            InputStream inputStream,
            ExecutorService readerExecutor,
            int timeoutMs
    ) throws IOException, InterruptedException, TimeoutException {
        Future<String> future = readerExecutor.submit(reader::readLine);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            closeQuietly(inputStream);
            future.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException(cause);
        }
    }

    private void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private AppException modelHttpError(int statusCode, String body) {
        String upstreamMessage = extractUpstreamErrorMessage(body);
        String detail = upstreamMessage.isBlank() ? compactResponse(body) : upstreamMessage;
        String message = detail.isBlank()
                ? "模型接口调用失败: HTTP " + statusCode
                : "模型接口调用失败: HTTP " + statusCode + " - " + detail;
        return new AppException(HttpStatus.BAD_GATEWAY, message);
    }

    private String readResponseBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String extractUpstreamErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            return extractUpstreamErrorMessage(objectMapper.readTree(body));
        } catch (IOException exception) {
            return "";
        }
    }

    private String extractUpstreamErrorMessage(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        JsonNode error = root.path("error");
        if (error.isTextual()) {
            return compactResponse(error.asText());
        }
        if (error.isObject()) {
            String message = compactResponse(error.path("message").asText(""));
            if (!message.isBlank()) {
                return message;
            }
            String code = compactResponse(error.path("code").asText(""));
            if (!code.isBlank()) {
                return code;
            }
        }
        return compactResponse(root.path("message").asText(""));
    }

    private String compactResponse(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String normalized = source.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    private String compactCause(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : compactResponse(message);
    }

    private String resolveEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "平台模型 baseUrl 未配置");
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized.replaceAll("/+$", "") + "/chat/completions";
    }
}
