package com.superskidder.kylinops.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 交叉编码器重排（cross-encoder rerank）。
 *
 * <p>RRF 融合只看候选在各通道里的名次，不看查询与分片内容的真实语义匹配程度——一条在
 * 两路都排中游的分片，融合分可能高过只在一路命中但内容真正对题的分片。重排把融合后的
 * 前 N 条交给交叉编码器逐条重新打分，属于"粗排 → 精排"里的精排。
 *
 * <p>失败时不抛出：重排是增强项，服务不可用应退回融合结果继续作答，而不是让整条检索链路
 * 失败。调用方通过返回的空结果判断是否发生了降级。
 */
@Service
public class RagRerankService {

    private static final Logger log = LoggerFactory.getLogger(RagRerankService.class);
    private static final int TIMEOUT_SECONDS = 30;
    /** 送给重排模型的单条文本上限，避免超长分片撑爆请求 */
    private static final int MAX_DOCUMENT_CHARS = 4000;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 重排结果：候选在入参列表中的下标 → 相关性分数，按分数降序。 */
    public record RerankResult(List<Integer> order, Map<Integer, Double> scores) {
        public static RerankResult empty() {
            return new RerankResult(List.of(), Map.of());
        }

        public boolean isEmpty() {
            return order.isEmpty();
        }
    }

    public boolean isEnabled(RagSettings settings) {
        return settings.rerankerEnabled()
                && !settings.rerankerModel().isBlank()
                && !resolveBaseUrl(settings).isBlank()
                && !resolveApiKey(settings).isBlank();
    }

    /** 重排配置留空时回退到 embedding 的服务地址（两者常在同一提供商上）。 */
    private String resolveBaseUrl(RagSettings settings) {
        return settings.rerankerBaseUrl().isBlank() ? settings.embeddingBaseUrl() : settings.rerankerBaseUrl();
    }

    private String resolveApiKey(RagSettings settings) {
        return settings.rerankerApiKey().isBlank() ? settings.embeddingApiKey() : settings.rerankerApiKey();
    }

    public RerankResult rerank(RagSettings settings, String query, List<String> documents) {
        if (!isEnabled(settings) || documents.isEmpty() || query == null || query.isBlank()) {
            return RerankResult.empty();
        }
        List<String> trimmed = documents.stream()
                .map(text -> text == null ? "" : text)
                .map(text -> text.length() > MAX_DOCUMENT_CHARS ? text.substring(0, MAX_DOCUMENT_CHARS) : text)
                .toList();
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", settings.rerankerModel(),
                    "query", query,
                    "documents", trimmed,
                    "top_n", trimmed.size()
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(join(resolveBaseUrl(settings), "/rerank")))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + resolveApiKey(settings))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("rerank 服务返回 HTTP {}，本次退回融合顺序", response.statusCode());
                return RerankResult.empty();
            }
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            List<Integer> order = new ArrayList<>();
            Map<Integer, Double> scores = new LinkedHashMap<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= trimmed.size()) {
                    continue;
                }
                order.add(index);
                scores.put(index, item.path("relevance_score").asDouble());
            }
            if (order.isEmpty()) {
                log.warn("rerank 服务未返回可用结果，本次退回融合顺序");
                return RerankResult.empty();
            }
            return new RerankResult(order, scores);
        } catch (IOException exception) {
            log.warn("rerank 服务调用失败，本次退回融合顺序: {}", exception.getMessage());
            return RerankResult.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("rerank 服务调用被中断，本次退回融合顺序");
            return RerankResult.empty();
        }
    }

    private String join(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + path;
    }
}
