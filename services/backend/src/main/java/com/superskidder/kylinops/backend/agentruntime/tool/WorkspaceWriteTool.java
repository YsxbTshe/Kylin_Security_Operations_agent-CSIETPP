package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceWriteTool implements NodeTool {

    public static final String TOOL_ID = "kylin.workspace.write";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-workspace-write-v1";
    /** SM3(RFC8785(inputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String INPUT_SCHEMA_DIGEST = "11488d49a0e1ecd3bae9f4aab3a13d9628444ff6961d9dc3039113154be382a2";
    /** SM3(RFC8785(outputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String OUTPUT_SCHEMA_DIGEST = "827fce61a08b845f0c6d15455aa369d09fec4570d7fbcd0355d8999353825a05";
    /** SM3(RFC8785(目录描述符 {"packageId","toolId","version","readOnly","directDisplay"}))。 */
    public static final String CATALOG_DIGEST = "bdfef1b8dfcafa20a9811afb732c4e32626b4b29df3f621c87bd579559e186d1";
    private static final Pattern PATH_SEGMENT = Pattern.compile("[^/\\\\\\x00]+");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public WorkspaceWriteTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "path", Map.of("type", "string", "minLength", 1, "maxLength", 1024),
                        "content", Map.of("type", "string")
                ),
                "required", List.of("path", "content")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "sizeBytes", Map.of("type", "integer")
                ),
                "required", List.of("path", "sizeBytes")
        );
        this.inputSchema = objectMapper.valueToTree(inputSchemaMap);
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return false; }
    @Override public String riskFloor() { return "R1"; }
    @Override public boolean alwaysRequireApproval() { return false; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.IDEMPOTENT; }
    @Override public int maxAttempts() { return 2; }
    @Override public String inputSchemaDigest() { return INPUT_SCHEMA_DIGEST; }
    @Override public String outputSchemaDigest() { return OUTPUT_SCHEMA_DIGEST; }
    @Override public String catalogDigest() { return CATALOG_DIGEST; }
    @Override public List<ResourceLockTemplate> resourceLockTemplates() { return List.of(); }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Write content to a file inside the sandboxed workspace.",
                inputSchema);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("path") || !arguments.containsKey("content")) {
            throw new IllegalArgumentException("workspace.write arguments must contain path and content");
        }
        String path = String.valueOf(arguments.get("path"));
        validateWorkspacePath(path);
        String content = String.valueOf(arguments.get("content"));
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        return Map.of("path", path, "content", content);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            JsonNode path = root.get("path");
            result.put("path", path != null && path.isTextual() ? path.asText() : "");
            JsonNode sizeBytes = root.get("sizeBytes");
            result.put("sizeBytes", sizeBytes != null && sizeBytes.isNumber() ? sizeBytes.asInt() : 0);
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("workspace.write result is not valid JSON", e);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "workspace-write");
        rendered.put("title", "文件写入结果");
        rendered.put("path", output.get("path"));
        rendered.put("sizeBytes", output.get("sizeBytes"));
        return rendered;
    }

    private static void validateWorkspacePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        if (path.contains("\0")) throw new IllegalArgumentException("path contains null byte");
        if (path.startsWith("/")) throw new IllegalArgumentException("path must be relative");
        if (path.contains("..")) throw new IllegalArgumentException("path must not contain ..");
    }
}
