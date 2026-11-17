package com.superskidder.kylinops.backend.agentruntime.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.llm.LlmToolCall;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AnthropicModelAdapter implements ModelProviderAdapter {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public AnthropicModelAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String providerType) {
        return "ANTHROPIC".equalsIgnoreCase(providerType);
    }

    @Override
    public ModelGatewayResult complete(RoutedModel routed, ModelGatewayRequest request) {
        long started = System.nanoTime();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", routed.config().model());
            payload.put("max_tokens", 1024);
            payload.put("system", request.context().stablePrefix().stream()
                    .map(segment -> segment.content()).reduce((a, b) -> a + "\n\n" + b).orElse(""));
            var messages = new java.util.ArrayList<Map<String, Object>>(request.context().dynamicSuffix().stream()
                    .map(segment -> Map.<String, Object>of("role", "assistant".equals(segment.role()) ? "assistant" : "user",
                            "content", segment.content())).toList());
            messages.addAll(request.extraMessages());
            payload.put("messages", messages);
            payload.put("tools", request.tools().stream().map(tool -> Map.of(
                    "name", tool.name(), "description", tool.description(), "input_schema", tool.parameters())).toList());
            String endpoint = routed.config().baseUrl().replaceAll("/+$", "") + "/messages";
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(min(request.timeout(), Duration.ofMillis(routed.config().timeoutMs())))
                    .header("content-type", "application/json")
                    .header("x-api-key", routed.config().apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "Anthropic model returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<LlmToolCall> calls = new ArrayList<>();
            StringBuilder content = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    content.append(block.path("text").asText());
                } else if ("tool_use".equals(block.path("type").asText())) {
                    calls.add(new LlmToolCall(block.path("id").asText(), block.path("name").asText(),
                            objectMapper.writeValueAsString(block.path("input"))));
                }
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            JsonNode usage = root.path("usage");
            return new ModelGatewayResult(content.toString(), calls, usage.path("input_tokens").asLong(),
                    usage.path("output_tokens").asLong(), usage.path("cache_read_input_tokens").asLong(),
                    usage.path("cache_creation_input_tokens").asLong(), elapsed, elapsed, 0);
        } catch (AppException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "Anthropic model call interrupted");
        } catch (Exception exception) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Anthropic model call failed: " + exception.getMessage());
        }
    }

    private Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}
