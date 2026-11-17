package com.superskidder.kylinops.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.common.AppException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RagExternalIndexClient {

    private final RagSettingsService settingsService;
    private final RagEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RagExternalIndexClient(
            RagSettingsService settingsService,
            RagEmbeddingService embeddingService,
            ObjectMapper objectMapper
    ) {
        this.settingsService = settingsService;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public boolean externalIndexEnabled() {
        RagSettings settings = settingsService.current();
        return settings.milvusEnabled() || settings.elasticsearchEnabled() || settings.redisEnabled();
    }

    public void ensureReady() {
        RagSettings settings = settingsService.current();
        if (settings.elasticsearchEnabled()) {
            putJson(elasticsearchUrl(settings, "/" + settings.elasticsearchIndex()), Map.of(
                    "mappings", Map.of("properties", Map.of(
                            "organizationId", Map.of("type", "keyword"),
                            "documentId", Map.of("type", "keyword"),
                            "chunkId", Map.of("type", "keyword"),
                            "chunkIndex", Map.of("type", "integer"),
                            "title", Map.of("type", "text", "analyzer", "cjk"),
                            "content", Map.of("type", "text", "analyzer", "cjk"),
                            "sourceType", Map.of("type", "keyword")
                    ))
            ), Set.of(200, 201, 400));
        }
        if (settings.milvusEnabled()) {
            postJson(milvusUrl(settings, "/v2/vectordb/collections/create"), Map.of(
                    "collectionName", settings.milvusCollection(),
                    "dimension", settings.embeddingDimension(),
                    "metricType", "COSINE",
                    "primaryFieldName", "milvusId",
                    "vectorFieldName", "vector"
            ), Set.of(200));
        }
    }

    public void indexDocument(String organizationId, RagDocumentItem document) {
        ensureReady();
        RagSettings settings = settingsService.current();
        // Elasticsearch is indexed at chunk granularity via indexChunks(); see RagService.rebuildDocumentChunks.
        if (settings.milvusEnabled()) {
            postJson(milvusUrl(settings, "/v2/vectordb/entities/insert"), Map.of(
                    "collectionName", settings.milvusCollection(),
                    "data", List.of(Map.of(
                            "milvusId", Math.abs(document.id().hashCode()),
                            "documentId", document.id(),
                            "organizationId", organizationId,
                            "title", document.title(),
                            "sourceType", document.sourceType(),
                            "content", document.content(),
                            "vector", embeddingService.embed(document.title() + "\n" + document.content())
                    ))
            ), Set.of(200));
        }
        if (settings.redisEnabled()) {
            redisSet(settings, "doc:" + organizationId + ":" + document.id(), document.title() + "\n" + document.content());
        }
    }

    /** Index a document's chunks into Elasticsearch (chunk-level BM25). No-op when ES disabled. */
    public void indexChunks(String organizationId, String documentId, List<RagChunkItem> chunks) {
        RagSettings settings = settingsService.current();
        if (!settings.elasticsearchEnabled()) {
            return;
        }
        ensureReady();
        deleteChunksByDocument(settings, organizationId, documentId);
        for (RagChunkItem chunk : chunks) {
            putJson(elasticsearchUrl(settings, "/" + settings.elasticsearchIndex() + "/_doc/" + encode(chunk.id())), Map.of(
                    "organizationId", organizationId,
                    "documentId", documentId,
                    "chunkId", chunk.id(),
                    "chunkIndex", chunk.chunkIndex(),
                    "title", chunk.title() == null ? "" : chunk.title(),
                    "sourceType", chunk.sourceType() == null ? "" : chunk.sourceType(),
                    "content", chunk.content() == null ? "" : chunk.content()
            ), Set.of(200, 201));
        }
        post(elasticsearchUrl(settings, "/" + settings.elasticsearchIndex() + "/_refresh"), Set.of(200));
    }

    /** BM25 chunk search. Returns chunkId + ES _score, ordered by relevance. Empty when ES disabled. */
    public List<RagScoredId> searchChunks(String organizationId, String question, int size) {
        RagSettings settings = settingsService.current();
        if (!settings.elasticsearchEnabled()) {
            return List.of();
        }
        JsonNode root = postJson(elasticsearchUrl(settings, "/" + settings.elasticsearchIndex() + "/_search"), Map.of(
                "size", size,
                "query", Map.of("bool", Map.of(
                        "filter", List.of(Map.of("term", Map.of("organizationId", organizationId))),
                        "must", List.of(Map.of("multi_match", Map.of(
                                "query", question,
                                "fields", List.of("title^2", "content")
                        )))
                ))
        ), Set.of(200));
        List<RagScoredId> hits = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            String id = hit.path("_source").path("chunkId").asText("");
            if (!id.isBlank()) {
                hits.add(new RagScoredId(id, hit.path("_score").asDouble(0.0D)));
            }
        }
        return hits;
    }

    private void deleteChunksByDocument(RagSettings settings, String organizationId, String documentId) {
        postJson(elasticsearchUrl(settings, "/" + settings.elasticsearchIndex() + "/_delete_by_query?refresh=true"), Map.of(
                "query", Map.of("bool", Map.of("filter", List.of(
                        Map.of("term", Map.of("organizationId", organizationId)),
                        Map.of("term", Map.of("documentId", documentId))
                )))
        ), Set.of(200, 404, 409));
    }

    public void deleteDocument(String organizationId, String documentId) {
        RagSettings settings = settingsService.current();
        if (settings.elasticsearchEnabled()) {
            deleteChunksByDocument(settings, organizationId, documentId);
        }
        if (settings.milvusEnabled()) {
            postJson(milvusUrl(settings, "/v2/vectordb/entities/delete"), Map.of(
                    "collectionName", settings.milvusCollection(),
                    "filter", "documentId == \"" + documentId.replace("\"", "\\\"") + "\""
            ), Set.of(200));
        }
        if (settings.redisEnabled()) {
            redisDelete(settings, "doc:" + organizationId + ":" + documentId);
        }
    }

    public List<String> searchDocumentIds(String organizationId, String question) {
        RagSettings settings = settingsService.current();
        Set<String> ids = new LinkedHashSet<>();
        if (settings.elasticsearchEnabled()) {
            ids.addAll(searchElasticsearch(settings, organizationId, question));
        }
        if (settings.milvusEnabled()) {
            ids.addAll(searchMilvus(settings, organizationId, question));
        }
        return new ArrayList<>(ids);
    }

    public void storeMemory(String organizationId, RagMemoryItem memory) {
        RagSettings settings = settingsService.current();
        if (settings.redisEnabled()) {
            redisSet(settings, "memory:" + organizationId + ":" + memory.id(), memory.title() + "\n" + memory.content());
        }
    }

    public boolean healthCheck() {
        ensureReady();
        RagSettings settings = settingsService.current();
        if (settings.elasticsearchEnabled()) {
            get(elasticsearchUrl(settings, "/_cluster/health"), Set.of(200));
        }
        if (settings.milvusEnabled()) {
            postJson(milvusUrl(settings, "/v2/vectordb/collections/list"), Map.of(), Set.of(200));
        }
        if (settings.redisEnabled()) {
            redisCommand(settings, List.of("PING"));
        }
        return true;
    }

    private List<String> searchElasticsearch(RagSettings settings, String organizationId, String question) {
        JsonNode root = postJson(elasticsearchUrl(settings, "/" + settings.elasticsearchIndex() + "/_search"), Map.of(
                "size", 8,
                "query", Map.of("bool", Map.of(
                        "filter", List.of(Map.of("term", Map.of("organizationId", organizationId))),
                        "must", List.of(Map.of("multi_match", Map.of(
                                "query", question,
                                "fields", List.of("title^2", "content")
                        )))
                ))
        ), Set.of(200));
        List<String> ids = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            String id = hit.path("_source").path("documentId").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> searchMilvus(RagSettings settings, String organizationId, String question) {
        JsonNode root = postJson(milvusUrl(settings, "/v2/vectordb/entities/search"), Map.of(
                "collectionName", settings.milvusCollection(),
                "data", List.of(embeddingService.embed(question)),
                "filter", "organizationId == \"" + organizationId.replace("\"", "\\\"") + "\"",
                "limit", 8,
                "outputFields", List.of("documentId")
        ), Set.of(200));
        List<String> ids = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            String id = item.path("documentId").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void redisSet(RagSettings settings, String key, String value) {
        redisCommand(settings, List.of("SET", prefixed(settings, key), value));
    }

    private void redisDelete(RagSettings settings, String key) {
        redisCommand(settings, List.of("DEL", prefixed(settings, key)));
    }

    private void redisCommand(RagSettings settings, List<String> command) {
        RedisEndpoint endpoint = redisEndpoint(settings);
        try (Socket socket = new Socket(endpoint.host(), endpoint.port())) {
            socket.setSoTimeout(5000);
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(resp(command).getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            InputStream inputStream = socket.getInputStream();
            int first = inputStream.read();
            if (first == '-') {
                throw new AppException(HttpStatus.BAD_GATEWAY, "Redis 命令执行失败");
            }
            if (first == -1) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "Redis 未返回响应");
            }
        } catch (IOException exception) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "Redis 连接失败");
        }
    }

    private JsonNode get(String url, Set<Integer> expectedStatuses) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), expectedStatuses);
    }

    private JsonNode delete(String url, Set<Integer> expectedStatuses) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).DELETE().build(), expectedStatuses);
    }

    private JsonNode post(String url, Set<Integer> expectedStatuses) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), expectedStatuses);
    }

    private JsonNode putJson(String url, Object payload, Set<Integer> expectedStatuses) {
        return requestWithBody("PUT", url, payload, expectedStatuses);
    }

    private JsonNode postJson(String url, Object payload, Set<Integer> expectedStatuses) {
        return requestWithBody("POST", url, payload, expectedStatuses);
    }

    private JsonNode requestWithBody(String method, String url, Object payload, Set<Integer> expectedStatuses) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return send(request, expectedStatuses);
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "RAG 外部索引请求序列化失败");
        }
    }

    private JsonNode send(HttpRequest request, Set<Integer> expectedStatuses) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (!expectedStatuses.contains(response.statusCode())) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "RAG 外部服务调用失败: HTTP " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "RAG 外部服务响应解析失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.BAD_GATEWAY, "RAG 外部服务调用被中断");
        }
    }

    private String milvusUrl(RagSettings settings, String path) {
        return join(settings.milvusBaseUrl(), path);
    }

    private String elasticsearchUrl(RagSettings settings, String path) {
        return join(settings.elasticsearchBaseUrl(), path);
    }

    private String join(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + path;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String prefixed(RagSettings settings, String key) {
        return settings.redisPrefix() + ":" + key;
    }

    private RedisEndpoint redisEndpoint(RagSettings settings) {
        String value = settings.redisBaseUrl().replace("redis://", "").trim();
        String[] parts = value.split(":", 2);
        if (parts.length != 2) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Redis 地址格式应为 host:port");
        }
        return new RedisEndpoint(parts[0], Integer.parseInt(parts[1]));
    }

    private String resp(List<String> command) {
        StringBuilder builder = new StringBuilder();
        builder.append("*").append(command.size()).append("\r\n");
        for (String item : command) {
            byte[] bytes = item.getBytes(StandardCharsets.UTF_8);
            builder.append("$").append(bytes.length).append("\r\n");
            builder.append(item).append("\r\n");
        }
        return builder.toString();
    }

    private record RedisEndpoint(String host, int port) {
    }
}
