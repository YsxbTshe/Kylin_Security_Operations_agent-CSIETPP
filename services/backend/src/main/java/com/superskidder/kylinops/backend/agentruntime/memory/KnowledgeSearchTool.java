package com.superskidder.kylinops.backend.agentruntime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeTool;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import com.superskidder.kylinops.backend.rag.RagChunkItem;
import com.superskidder.kylinops.backend.rag.RagSearchContext;
import com.superskidder.kylinops.backend.rag.RagService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * knowledge.search：按需检索租户知识库（老架构 RagService 混合检索）的只读 RUNTIME 工具。
 * RAG 不再无条件注入上下文；检索不可用时 fail-open，返回空结果与显式提示，不抛崩执行循环。
 */
@Component
public class KnowledgeSearchTool implements RuntimeTool {

    public static final String TOOL_ID = "kylin.runtime.knowledge_search";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-runtime-knowledge-search-v1";
    public static final String INPUT_SCHEMA_DIGEST = "pending";
    public static final String OUTPUT_SCHEMA_DIGEST = "pending";
    public static final String CATALOG_DIGEST = "pending";

    public static final int DEFAULT_TOP_K = 5;
    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 10;
    private static final int QUERY_MAX_LENGTH = 1_000;
    private static final int SNIPPET_MAX_LENGTH = 500;

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    private final RagService ragService;
    private final ObjectMapper objectMapper;
    private final JsonNode inputSchemaNode;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public KnowledgeSearchTool(RagService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1, "maxLength", QUERY_MAX_LENGTH),
                        "topK", Map.of("type", "integer", "minimum", MIN_TOP_K, "maximum", MAX_TOP_K)
                ),
                "required", List.of("query")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "properties", Map.of(
                        "available", Map.of("type", "boolean"),
                        "query", Map.of("type", "string"),
                        "resultCount", Map.of("type", "integer"),
                        "notice", Map.of("type", "string"),
                        "results", Map.of("type", "array")
                ),
                "required", List.of("available", "resultCount", "results")
        );
        this.inputSchemaNode = objectMapper.valueToTree(inputSchemaMap);
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return true; }
    @Override public String riskFloor() { return "R0"; }
    @Override public boolean alwaysRequireApproval() { return false; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.IDEMPOTENT; }
    @Override public int maxAttempts() { return 3; }
    @Override public String inputSchemaDigest() { return INPUT_SCHEMA_DIGEST; }
    @Override public String outputSchemaDigest() { return OUTPUT_SCHEMA_DIGEST; }
    @Override public String catalogDigest() { return CATALOG_DIGEST; }
    @Override public List<ResourceLockTemplate> resourceLockTemplates() { return List.of(); }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Search the tenant knowledge base on demand (hybrid RAG retrieval). Call it only when "
                        + "extra knowledge is needed; returns scored text snippets with provenance. "
                        + "If the knowledge base is unavailable, it returns an empty result with a notice.",
                inputSchemaNode);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("knowledge_search arguments must not be null");
        }
        for (String key : arguments.keySet()) {
            if (!List.of("query", "topK").contains(key)) {
                throw new IllegalArgumentException("knowledge_search arguments contain unsupported field: " + key);
            }
        }
        Object queryValue = arguments.get("query");
        if (!(queryValue instanceof String query) || query.isBlank() || query.length() > QUERY_MAX_LENGTH) {
            throw new IllegalArgumentException("query must be a non-blank string of 1-" + QUERY_MAX_LENGTH + " characters");
        }
        int topK = DEFAULT_TOP_K;
        if (arguments.containsKey("topK")) {
            Object topKValue = arguments.get("topK");
            if (!(topKValue instanceof Number topKNumber)) {
                throw new IllegalArgumentException("topK must be an integer between " + MIN_TOP_K + " and " + MAX_TOP_K);
            }
            topK = topKNumber.intValue();
            if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
                throw new IllegalArgumentException("topK must be an integer between " + MIN_TOP_K + " and " + MAX_TOP_K);
            }
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        validated.put("query", query.strip());
        validated.put("topK", topK);
        return validated;
    }

    /** 执行检索；RAG 不可用时返回空结果与显式提示（fail-open，不抛出）。 */
    public Map<String, Object> search(String tenantId, Map<String, Object> validatedArguments) {
        String query = String.valueOf(validatedArguments.get("query"));
        int topK = validatedArguments.get("topK") instanceof Number number
                ? number.intValue() : DEFAULT_TOP_K;
        try {
            RagSearchContext context = ragService.retrieveContext(
                    tenantId, query, RagService.RetrievalConfig.defaults(topK));
            List<Map<String, Object>> results = new ArrayList<>();
            for (RagChunkItem chunk : context.chunks()) {
                if (results.size() >= topK) {
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", chunk.title() == null || chunk.title().isBlank()
                        ? chunk.documentId() : chunk.title());
                item.put("documentId", chunk.documentId() == null ? "" : chunk.documentId());
                item.put("score", chunk.score());
                item.put("text", snippet(chunk.content()));
                results.add(item);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("available", true);
            output.put("query", query);
            output.put("resultCount", results.size());
            output.put("results", results);
            return output;
        } catch (RuntimeException exception) {
            log.warn("knowledge.search degraded for tenant {}: {}", tenantId, exception.getMessage());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("available", false);
            output.put("query", query);
            output.put("resultCount", 0);
            output.put("results", List.of());
            output.put("notice", "知识库检索不可用，已返回空结果，请稍后重试或改用其他信息来源");
            return output;
        }
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("available", root.path("available").asBoolean(false));
            result.put("query", root.path("query").asText(""));
            result.put("resultCount", root.path("resultCount").asInt(0));
            result.put("notice", root.path("notice").asText(""));
            List<Object> results = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                results.add(objectMapper.convertValue(item, Map.class));
            }
            result.put("results", results);
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("knowledge_search result is not valid JSON", exception);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "knowledge-search");
        rendered.put("title", "知识库检索结果");
        rendered.put("available", output.getOrDefault("available", false));
        rendered.put("query", output.getOrDefault("query", ""));
        rendered.put("resultCount", output.getOrDefault("resultCount", 0));
        rendered.put("notice", output.getOrDefault("notice", ""));
        rendered.put("results", output.getOrDefault("results", List.of()));
        return rendered;
    }

    private String snippet(String content) {
        String text = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        return text.length() <= SNIPPET_MAX_LENGTH ? text : text.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }
}
