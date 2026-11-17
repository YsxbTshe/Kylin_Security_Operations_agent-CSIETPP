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
public class CleanupExecuteTool implements NodeTool {

    public static final String TOOL_ID = "kylin.cleanup.execute";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-cleanup-execute-v1";
    public static final String INPUT_SCHEMA_DIGEST = "1728394a5b6c7d8e9f0011223344556677889900aabbccddeeff0011223344";
    public static final String OUTPUT_SCHEMA_DIGEST = "2839405a6b7c8d9e0f102132435465768798a9b0c1d2e3f4a5b6c7d8e9f00112";
    public static final String CATALOG_DIGEST = "3940516a7b8c9d0e1f2031425364758697a8b9c0d1e2f3a4b5c6d7e8f90011223";

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public CleanupExecuteTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "targetDir", Map.of("type", "string", "minLength", 1),
                        "mode", Map.of("type", "string", "enum", List.of("archive", "archive_then_truncate")),
                        "maxFiles", Map.of("type", "integer", "minimum", 1, "maximum", 100)
                )
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "targetDir", Map.of("type", "string"),
                        "removedFiles", Map.of("type", "integer"),
                        "freedBytes", Map.of("type", "integer"),
                        "rollbackId", Map.of("type", "string"),
                        "message", Map.of("type", "string")
                ),
                "required", List.of("targetDir", "removedFiles", "freedBytes", "rollbackId", "message")
        );
        this.inputSchema = objectMapper.valueToTree(inputSchemaMap);
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

    @Override
    public List<ResourceLockTemplate> resourceLockTemplates() {
        return List.of(new ResourceLockTemplate("path:{targetDir}", "WRITE_EXCLUSIVE"));
    }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Archive and truncate approved demo directories with rollback metadata.", inputSchema);
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
        Map<String, Object> incoming = arguments == null ? Map.of() : arguments;
        LinkedHashMap<String, Object> validated = new LinkedHashMap<>();
        if (incoming.containsKey("targetDir")) {
            String targetDir = String.valueOf(incoming.get("targetDir")).trim();
            if (targetDir.isEmpty() || !targetDir.startsWith("/")) {
                throw new IllegalArgumentException("targetDir must be an absolute path");
            }
            validated.put("targetDir", targetDir);
        }
        if (incoming.containsKey("mode")) {
            String mode = String.valueOf(incoming.get("mode")).trim();
            if (!"archive".equals(mode) && !"archive_then_truncate".equals(mode)) {
                throw new IllegalArgumentException("mode must be archive or archive_then_truncate");
            }
            validated.put("mode", mode);
        }
        if (incoming.containsKey("maxFiles")) {
            Object raw = incoming.get("maxFiles");
            int maxFiles;
            if (raw instanceof Number number) {
                maxFiles = number.intValue();
            } else {
                try {
                    maxFiles = Integer.parseInt(String.valueOf(raw).trim());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("maxFiles must be an integer");
                }
            }
            if (maxFiles < 1 || maxFiles > 100) {
                throw new IllegalArgumentException("maxFiles must be between 1 and 100");
            }
            validated.put("maxFiles", maxFiles);
        }
        for (String key : incoming.keySet()) {
            if (!List.of("targetDir", "mode", "maxFiles").contains(key)) {
                throw new IllegalArgumentException("cleanup.execute argument " + key + " is not allowed");
            }
        }
        return validated;
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("cleanup.execute result is not a JSON object");
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("targetDir", firstText(root, "targetDir", "target_dir"));
            result.put("rollbackId", firstText(root, "rollbackId", "rollback_id"));
            result.put("freedBytes", firstNumber(root, "freedBytes", "freed_bytes", "bytes_archived"));
            result.put("removedFiles", removedFiles(root));
            String message = firstText(root, "message", "status");
            result.put("message", message.isBlank() ? "EXECUTED" : message);
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("cleanup.execute result is not valid JSON", exception);
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

    private static long firstNumber(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode value = root.get(key);
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
        }
        return 0L;
    }

    private static int removedFiles(JsonNode root) {
        JsonNode removed = root.get("removedFiles");
        if (removed != null && removed.isNumber()) {
            return removed.asInt();
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
        rendered.put("type", "cleanup-execute");
        rendered.put("title", "清理目录 " + output.get("targetDir"));
        rendered.put("targetDir", output.get("targetDir"));
        rendered.put("removedFiles", output.get("removedFiles"));
        rendered.put("freedBytes", output.get("freedBytes"));
        rendered.put("rollbackId", output.get("rollbackId"));
        rendered.put("message", output.get("message"));
        rendered.put("directDisplay", true);
        return rendered;
    }
}
