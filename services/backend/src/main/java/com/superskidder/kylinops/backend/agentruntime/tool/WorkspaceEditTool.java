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
public class WorkspaceEditTool implements NodeTool {

    public static final String TOOL_ID = "kylin.workspace.edit";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-workspace-edit-v1";
    /** SM3(RFC8785(inputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String INPUT_SCHEMA_DIGEST = "a1beb6136b5b1c0cfb685908346b002179cfb98b04f39ba01653155beeaf0383";
    /** SM3(RFC8785(outputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String OUTPUT_SCHEMA_DIGEST = "d6b370631f41d55b611bc249f4d0c5a1687370655a7a91a3fdc8e68869d100e2";
    /** SM3(RFC8785(目录描述符 {"packageId","toolId","version","readOnly","directDisplay"}))。 */
    public static final String CATALOG_DIGEST = "779b3cef49416b9dc31043a6e79382087b8f18bab1f6af6f0e630ab922e3f57a";
    private static final Pattern PATH_SEGMENT = Pattern.compile("[^/\\\\\\x00]+");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public WorkspaceEditTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "path", Map.of("type", "string", "minLength", 1, "maxLength", 1024),
                        "oldText", Map.of("type", "string"),
                        "newText", Map.of("type", "string")
                ),
                "required", List.of("path", "oldText", "newText")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "replaced", Map.of("type", "integer")
                ),
                "required", List.of("path", "replaced")
        );
        this.inputSchema = objectMapper.valueToTree(inputSchemaMap);
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return false; }
    @Override public String riskFloor() { return "R1"; }
    @Override public boolean alwaysRequireApproval() { return false; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.NON_RETRYABLE; }
    @Override public int maxAttempts() { return 1; }
    @Override public String inputSchemaDigest() { return INPUT_SCHEMA_DIGEST; }
    @Override public String outputSchemaDigest() { return OUTPUT_SCHEMA_DIGEST; }
    @Override public String catalogDigest() { return CATALOG_DIGEST; }
    @Override public List<ResourceLockTemplate> resourceLockTemplates() { return List.of(); }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Edit a file inside the sandboxed workspace by replacing oldText with newText.",
                inputSchema);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("path")
                || !arguments.containsKey("oldText") || !arguments.containsKey("newText")) {
            throw new IllegalArgumentException("workspace.edit arguments must contain path, oldText, and newText");
        }
        String path = String.valueOf(arguments.get("path"));
        validateWorkspacePath(path);
        String oldText = String.valueOf(arguments.get("oldText"));
        if (oldText == null) {
            throw new IllegalArgumentException("oldText is required");
        }
        String newText = String.valueOf(arguments.get("newText"));
        if (newText == null) {
            throw new IllegalArgumentException("newText is required");
        }
        return Map.of("path", path, "oldText", oldText, "newText", newText);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            JsonNode path = root.get("path");
            result.put("path", path != null && path.isTextual() ? path.asText() : "");
            JsonNode replaced = root.get("replaced");
            result.put("replaced", replaced != null && replaced.isNumber() ? replaced.asInt() : 0);
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("workspace.edit result is not valid JSON", e);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "workspace-edit");
        rendered.put("title", "文件编辑结果");
        rendered.put("path", output.get("path"));
        rendered.put("replaced", output.get("replaced"));
        return rendered;
    }

    private static void validateWorkspacePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is required");
        if (path.contains("\0")) throw new IllegalArgumentException("path contains null byte");
        if (path.startsWith("/")) throw new IllegalArgumentException("path must be relative");
        if (path.contains("..")) throw new IllegalArgumentException("path must not contain ..");
    }
}
