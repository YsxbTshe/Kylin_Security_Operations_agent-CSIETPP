package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillCatalogSigner;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Go 侧 defaultCatalog 中只读感知/诊断工具的 Java 镜像。
 * 节点上行经过 structuredPayload 投影后，载荷要么是 {@code {"output":"..."}}
 * （命令 stdout），要么是工具自己序列化的 JSON 对象；本类对两者都接受。
 */
public final class PerceptionNodeTool implements NodeTool {

    public static final String VERSION = "1.0.0";
    public static final Map<String, Object> OUTPUT_SCHEMA = defaultOutputSchema();

    private final ObjectMapper objectMapper;
    private final String toolId;
    private final String description;
    private final String packageVersionId;
    private final Map<String, Object> inputSchemaMap;
    private final JsonNode inputSchemaNode;
    private final List<ResourceLockTemplate> locks;
    private final String inputSchemaDigest;
    private final String outputSchemaDigest;
    private final String catalogDigest;

    PerceptionNodeTool(ObjectMapper objectMapper, String toolId, String description,
                       String packageVersionId, Map<String, Object> inputSchema,
                       List<ResourceLockTemplate> locks) {
        this.objectMapper = objectMapper;
        this.toolId = toolId;
        this.description = description;
        this.packageVersionId = packageVersionId;
        this.inputSchemaMap = Map.copyOf(inputSchema);
        this.inputSchemaNode = objectMapper.valueToTree(inputSchemaMap);
        this.locks = locks == null ? List.of() : List.copyOf(locks);
        this.inputSchemaDigest = SkillCatalogSigner.schemaDigest(writeJson(inputSchemaMap));
        this.outputSchemaDigest = SkillCatalogSigner.schemaDigest(writeJson(OUTPUT_SCHEMA));
        this.catalogDigest = SkillCatalogSigner.schemaDigest(writeJson(Map.of(
                "packageId", "kylin.core.node",
                "toolId", toolId,
                "version", VERSION,
                "readOnly", true,
                "directDisplay", true)));
    }

    @Override public String toolId() { return toolId; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return packageVersionId; }
    @Override public boolean readOnly() { return true; }
    @Override public String riskFloor() { return "R0"; }
    @Override public boolean alwaysRequireApproval() { return false; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.IDEMPOTENT; }
    @Override public int maxAttempts() { return 2; }
    @Override public String inputSchemaDigest() { return inputSchemaDigest; }
    @Override public String outputSchemaDigest() { return outputSchemaDigest; }
    @Override public String catalogDigest() { return catalogDigest; }
    @Override public List<ResourceLockTemplate> resourceLockTemplates() { return locks; }
    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return OUTPUT_SCHEMA; }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(toolId, description, inputSchemaNode);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        Map<String, Object> incoming = arguments == null ? Map.of() : arguments;
        Map<String, Object> properties = (Map<String, Object>) inputSchemaMap.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) inputSchemaMap.getOrDefault("required", List.of());
        LinkedHashMap<String, Object> validated = new LinkedHashMap<>();
        for (String key : incoming.keySet()) {
            if (!properties.containsKey(key)) {
                throw new IllegalArgumentException(toolId + " argument " + key + " is not allowed");
            }
        }
        for (String key : required) {
            if (!incoming.containsKey(key) || isBlank(incoming.get(key))) {
                throw new IllegalArgumentException(toolId + " argument " + key + " is required");
            }
        }
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            String key = property.getKey();
            if (!incoming.containsKey(key)) {
                continue;
            }
            Map<String, Object> schema = (Map<String, Object>) property.getValue();
            validated.put(key, coerce(key, incoming.get(key), schema));
        }
        return validated;
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(toolId + " result is not a JSON object");
            }
            return toMap(root);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(toolId + " result is not valid JSON", exception);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", toolId.replace('.', '-'));
        rendered.put("title", description);
        rendered.put("directDisplay", true);
        if (output != null) {
            rendered.putAll(output);
        }
        return rendered;
    }

    private Object coerce(String key, Object raw, Map<String, Object> schema) {
        String type = String.valueOf(schema.getOrDefault("type", "string"));
        if ("integer".equals(type)) {
            int value = asInt(key, raw);
            Number minimum = (Number) schema.get("minimum");
            Number maximum = (Number) schema.get("maximum");
            if (minimum != null && value < minimum.intValue()) {
                throw new IllegalArgumentException(key + " must be >= " + minimum.intValue());
            }
            if (maximum != null && value > maximum.intValue()) {
                throw new IllegalArgumentException(key + " must be <= " + maximum.intValue());
            }
            return value;
        }
        String text = String.valueOf(raw).trim();
        Number minLength = (Number) schema.get("minLength");
        if (minLength != null && text.length() < minLength.intValue()) {
            throw new IllegalArgumentException(key + " must be at least " + minLength.intValue() + " characters");
        }
        @SuppressWarnings("unchecked")
        List<String> enums = schema.get("enum") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : null;
        if (enums != null && !enums.contains(text)) {
            throw new IllegalArgumentException(key + " must be one of " + enums);
        }
        return text;
    }

    private int asInt(String key, Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    private static Map<String, Object> defaultOutputSchema() {
        return AbstractServiceActionTool.stdoutOutputSchema();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("schema cannot be serialized", exception);
        }
    }

    static Object toValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return toMap(node);
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(toValue(item)));
            return items;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }

    static LinkedHashMap<String, Object> toMap(JsonNode root) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> result.put(entry.getKey(), toValue(entry.getValue())));
        return result;
    }
}
