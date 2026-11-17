package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CleanupRollbackTool implements NodeTool {

    public static final String TOOL_ID = "kylin.cleanup.rollback";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-cleanup-rollback-v1";
    public static final String INPUT_SCHEMA_DIGEST = "4a5b6c7d8e9f0011223344556677889900aabbccddeeff0011223344556677";
    public static final String OUTPUT_SCHEMA_DIGEST = "5b6c7d8e9f0011223344556677889900aabbccddeeff001122334455667788";
    public static final String CATALOG_DIGEST = "6c7d8e9f0011223344556677889900aabbccddeeff00112233445566778899";

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public CleanupRollbackTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "rollbackId", Map.of("type", "string")
                ),
                "required", List.of("rollbackId")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "rollbackId", Map.of("type", "string"),
                        "restoredFiles", Map.of("type", "integer"),
                        "message", Map.of("type", "string")
                ),
                "required", List.of("rollbackId", "restoredFiles", "message")
        );
        this.inputSchema = objectMapper.valueToTree(inputSchemaMap);
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return false; }
    @Override public String riskFloor() { return "R3"; }
    @Override public boolean alwaysRequireApproval() { return true; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.AT_MOST_ONCE; }
    @Override public int maxAttempts() { return 1; }
    @Override public String inputSchemaDigest() { return INPUT_SCHEMA_DIGEST; }
    @Override public String outputSchemaDigest() { return OUTPUT_SCHEMA_DIGEST; }
    @Override public String catalogDigest() { return CATALOG_DIGEST; }

    @Override
    public List<ResourceLockTemplate> resourceLockTemplates() {
        return List.of(new ResourceLockTemplate("rollback:{rollbackId}", "WRITE_EXCLUSIVE"));
    }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Rollback a previous cleanup operation by its rollback ID, restoring deleted files.", inputSchema);
    }

    @Override
    public Map<String, Object> inputSchema() {
        return inputSchemaMap;
    }

    @Override
    public Map<String, Object> outputSchema() {
        return outputSchemaMap;
    }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("rollbackId")) {
            throw new IllegalArgumentException("cleanup.rollback arguments must contain only rollbackId");
        }
        String rollbackId = String.valueOf(arguments.get("rollbackId")).trim();
        if (rollbackId.isEmpty()) {
            throw new IllegalArgumentException("rollbackId must be non-blank");
        }
        return Map.of("rollbackId", rollbackId);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("cleanup.rollback result is not a JSON object");
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            String rollbackId = firstText(root, "rollbackId", "rollback_id");
            if (rollbackId.isBlank()) {
                throw new IllegalArgumentException("cleanup.rollback result field is missing or blank: rollbackId");
            }
            result.put("rollbackId", rollbackId);
            result.put("restoredFiles", restoredFiles(root));
            String message = firstText(root, "message", "status");
            result.put("message", message.isBlank() ? "ROLLED_BACK" : message);
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("cleanup.rollback result is not valid JSON", exception);
        }
    }

    private static String firstText(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode value = root.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private static int restoredFiles(JsonNode root) {
        JsonNode restored = root.get("restoredFiles");
        if (restored != null && restored.isNumber()) {
            return restored.asInt();
        }
        JsonNode files = root.get("files");
        if (files != null && files.isArray()) {
            return files.size();
        }
        return 0;
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "cleanup-rollback");
        rendered.put("title", "清理回滚 " + output.get("rollbackId"));
        rendered.put("rollbackId", output.get("rollbackId"));
        rendered.put("restoredFiles", output.get("restoredFiles"));
        rendered.put("message", output.get("message"));
        rendered.put("directDisplay", true);
        return rendered;
    }
}
