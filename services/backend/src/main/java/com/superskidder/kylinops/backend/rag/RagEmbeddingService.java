package com.superskidder.kylinops.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.common.AppException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RagEmbeddingService {

    private final RagSettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final boolean strictMode;

    public RagEmbeddingService(RagSettingsService settingsService, ObjectMapper objectMapper,
                               @org.springframework.beans.factory.annotation.Value("${app.strict-mode.enabled:false}") boolean strictMode) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.strictMode = strictMode;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public List<Double> embed(String text) {
        RagSettings settings = settingsService.current();
        if ("OPENAI_COMPATIBLE".equals(settings.embeddingProvider())) {
            return remoteEmbedding(settings, text);
        }
        // 严格模式：拒绝用本地 hash 伪向量冒充语义向量——向量检索需先配置真实 embedding。
        if (strictMode) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE,
                    "严格模式已开启：向量检索需配置真实 embedding（OPENAI_COMPATIBLE），当前为本地 hash 近似，已拒绝。"
                            + "请在「系统设置 → RAG」配置 embedding 端点，或关闭严格模式使用降级模式。");
        }
        return localEmbedding(text, settings.embeddingDimension());
    }

    public String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Embedding 序列化失败");
        }
    }

    public List<Double> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            List<Double> values = new ArrayList<>();
            for (JsonNode item : root) {
                values.add(item.asDouble());
            }
            return values;
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "Embedding 解析失败");
        }
    }

    public double cosine(List<Double> left, List<Double> right) {
        int length = Math.min(left.size(), right.size());
        if (length == 0) {
            return 0.0D;
        }
        double dot = 0.0D;
        double leftNorm = 0.0D;
        double rightNorm = 0.0D;
        for (int index = 0; index < length; index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0.0D || rightNorm == 0.0D) {
            return 0.0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private List<Double> remoteEmbedding(RagSettings settings, String text) {
        if (settings.embeddingBaseUrl().isBlank() || settings.embeddingApiKey().isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Embedding 接口地址和 API Key 不能为空");
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", settings.embeddingModel(),
                    "input", text == null ? "" : text
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(join(settings.embeddingBaseUrl(), "/embeddings")))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + settings.embeddingApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "Embedding 服务调用失败: HTTP " + response.statusCode());
            }
            JsonNode embedding = objectMapper.readTree(response.body()).path("data").path(0).path("embedding");
            List<Double> values = new ArrayList<>();
            for (JsonNode item : embedding) {
                values.add(item.asDouble());
            }
            if (values.isEmpty()) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "Embedding 服务未返回向量");
            }
            return values;
        } catch (IOException exception) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Embedding 服务响应解析失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "Embedding 服务调用被中断");
        }
    }

    private List<Double> localEmbedding(String text, int dimension) {
        double[] values = new double[dimension];
        String normalized = text == null ? "" : text.toLowerCase();
        for (String token : normalized.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (token.isBlank()) {
                continue;
            }
            addToken(values, token, 1.0D);
            for (int index = 0; index < token.length(); index++) {
                addToken(values, token.substring(index, index + 1), 0.2D);
            }
        }
        double norm = 0.0D;
        for (double value : values) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Double> vector = new ArrayList<>(dimension);
        for (double value : values) {
            vector.add(norm == 0.0D ? 0.0D : value / norm);
        }
        return vector;
    }

    private void addToken(double[] values, String token, double weight) {
        int bucket = Math.floorMod(token.hashCode(), values.length);
        values[bucket] += weight;
    }

    private String join(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + path;
    }
}
