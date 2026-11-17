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
public class WorkspaceReadTool implements NodeTool {

    public static final String TOOL_ID = "kylin.workspace.read";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-workspace-read-v1";
    /** SM3(RFC8785(inputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String INPUT_SCHEMA_DIGEST = "4c108b380ea95f9265d4a6daf3e2eadcea8ecba4609bc48a4819c6c7f4a99b8e";
    /** SM3(RFC8785(outputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String OUTPUT_SCHEMA_DIGEST = "860b5669f121eb70e811f15f6809dfec981cf26e7e0188077850e2f3a448ae6c";
    /** SM3(RFC8785(目录描述符 {"packageId","toolId","version","readOnly","directDisplay"}))。 */
    public static final String CATALOG_DIGEST = "c6499f333d1409dca3066444d16bc65c2d90b7767fef427cc7e2527545805604";
    private static final Pattern PATH_SEGMENT = Pattern.compile("[^/\\\\\\x00]+");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public WorkspaceReadTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("path", Map.of("type", "string", "minLength", 1, "maxLength", 1024)),
                "required", List.of("path")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "content", Map.of("type", "string")
                ),
                "required", List.of("content")
        );
        this.inputSchema = objectMapper.valueToTree(inputSchemaMap);
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return true; }
    @Override public String riskFloor() { return "R0"; }
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
                "Read the contents of a file inside the sandboxed workspace.",
                inputSchema);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("path")) {
            throw new IllegalArgumentException("workspace.read arguments must contain path");
        }
        String path = String.valueOf(arguments.get("path"));
        validateWorkspacePath(path);
        return Map.of("path", path);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            JsonNode content = root.get("content");
            if (content != null && content.isTextual()) {
                result.put("content", content.asText());
            } else {
                JsonNode output = root.get("output");
                result.put("content", output != null && output.isTextual() ? output.asText() : "");
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("workspace.read result is not valid JSON", e);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "workspace-read");
        rendered.put("title", "文件内容");
        rendered.put("content", output.get("content"));
        return rendered;
    }

    private static void validateWorkspacePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        if (path.contains("\0")) throw new IllegalArgumentException("path contains null byte");
        if (path.startsWith("/")) throw new IllegalArgumentException("path must be relative");
        if (path.contains("..")) throw new IllegalArgumentException("path must not contain ..");
    }
}
