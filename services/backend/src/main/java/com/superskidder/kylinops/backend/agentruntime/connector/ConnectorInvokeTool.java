package com.superskidder.kylinops.backend.agentruntime.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * kylin.connector.invoke：预装连接器统一外呼工具（CONNECTOR 执行器）。
 * 外部副作用默认 R3、NON_RETRYABLE、强制审批；白名单由 ConnectorCatalog 封闭。
 */
@Component
public class ConnectorInvokeTool implements ConnectorTool {

    public static final String TOOL_ID = "kylin.connector.invoke";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-connector-invoke-v1";
    public static final String INPUT_SCHEMA_DIGEST = "pending";
    public static final String OUTPUT_SCHEMA_DIGEST = "pending";
    public static final String CATALOG_DIGEST = "pending";

    private static final int PATH_MAX_LENGTH = 2048;
    private static final Set<String> KNOWN_FIELDS = Set.of("connector_id", "path", "query", "body");
    private static final Set<String> OUTPUT_FIELDS =
            Set.of("connectorId", "target", "status", "body", "truncated", "durationMs", "authorization");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchemaNode;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public ConnectorInvokeTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "connector_id", Map.of("type", "string", "minLength", 1, "maxLength", 64),
                        "path", Map.of("type", "string", "maxLength", PATH_MAX_LENGTH),
                        "query", Map.of("type", "object"),
                        "body", Map.of("type", "object")
                ),
                "required", List.of("connector_id")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "properties", Map.of(
                        "connectorId", Map.of("type", "string"),
                        "target", Map.of("type", "string"),
                        "status", Map.of("type", "integer"),
                        "body", Map.of("type", "string"),
                        "truncated", Map.of("type", "boolean"),
                        "durationMs", Map.of("type", "integer")
                ),
                "required", List.of("connectorId", "status")
        );
        this.inputSchemaNode = objectMapper.valueToTree(inputSchemaMap);
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return false; }
    @Override public String riskFloor() { return "R3"; }
    @Override public boolean alwaysRequireApproval() { return true; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.NON_RETRYABLE; }
    @Override public int maxAttempts() { return 1; }
    @Override public String inputSchemaDigest() { return INPUT_SCHEMA_DIGEST; }
    @Override public String outputSchemaDigest() { return OUTPUT_SCHEMA_DIGEST; }
    @Override public String catalogDigest() { return CATALOG_DIGEST; }
    @Override public List<ResourceLockTemplate> resourceLockTemplates() { return List.of(); }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Invoke a platform-preinstalled connector (webhook for outbound ticket/alert notification, "
                        + "http_read for read-only CMDB/external API queries). Only whitelisted connectors "
                        + "are available; the endpoint host is fixed by platform configuration and arguments "
                        + "can only fill path and query.",
                inputSchemaNode);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("connector.invoke arguments must not be null");
        }
        for (String key : arguments.keySet()) {
            if (!KNOWN_FIELDS.contains(key)) {
                throw new IllegalArgumentException("connector.invoke arguments contain unsupported field: " + key);
            }
        }
        String connectorId = requireString(arguments, "connector_id");
        if (connectorId.isBlank() || connectorId.length() > 64 || !connectorId.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("connector_id must be 1-64 chars of [a-z0-9_]");
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        validated.put("connector_id", connectorId);
        if (arguments.containsKey("path")) {
            String path = requireString(arguments, "path");
            if (path.length() > PATH_MAX_LENGTH) {
                throw new IllegalArgumentException("path must not exceed " + PATH_MAX_LENGTH + " characters");
            }
            validated.put("path", path);
        }
        if (arguments.containsKey("query")) {
            validated.put("query", requireScalarMap(arguments, "query"));
        }
        if (arguments.containsKey("body")) {
            validated.put("body", requireMap(arguments, "body"));
        }
        return validated;
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                if (!OUTPUT_FIELDS.contains(names.next())) {
                    throw new IllegalArgumentException("connector.invoke result contains an unknown field");
                }
            }
            result.put("connectorId", textOrDefault(root.get("connectorId")));
            result.put("target", textOrDefault(root.get("target")));
            result.put("status", root.path("status").asInt());
            result.put("body", textOrDefault(root.get("body")));
            result.put("truncated", root.path("truncated").asBoolean(false));
            result.put("durationMs", root.path("durationMs").asLong(0));
            if (root.has("authorization")) {
                result.put("authorization", textOrDefault(root.get("authorization")));
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("connector.invoke result is not valid JSON", exception);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "connector-invoke");
        rendered.put("title", "Connector 外呼 " + output.get("connectorId"));
        rendered.put("connectorId", output.get("connectorId"));
        rendered.put("target", output.get("target"));
        rendered.put("status", output.get("status"));
        rendered.put("body", output.get("body"));
        rendered.put("truncated", output.get("truncated"));
        rendered.put("durationMs", output.get("durationMs"));
        return rendered;
    }

    private String textOrDefault(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return stringValue;
    }

    private Map<String, Object> requireMap(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof Map<?, ?> mapValue)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        mapValue.forEach((k, v) -> typed.put(String.valueOf(k), v));
        return typed;
    }

    /** query 只接受标量值（string/number/boolean），规范化为字符串。 */
    private Map<String, String> requireScalarMap(Map<String, Object> arguments, String key) {
        Map<String, Object> raw = requireMap(arguments, key);
        Map<String, String> normalized = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v == null || v instanceof Map<?, ?> || v instanceof List<?>) {
                throw new IllegalArgumentException("query values must be scalars");
            }
            normalized.put(k, String.valueOf(v));
        });
        return normalized;
    }
}
