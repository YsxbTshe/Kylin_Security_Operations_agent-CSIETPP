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
public class WorkspaceGlobTool implements NodeTool {

    public static final String TOOL_ID = "kylin.workspace.glob";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-workspace-glob-v1";
    /** SM3(RFC8785(inputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String INPUT_SCHEMA_DIGEST = "295bbcd8d5c022277cf366f00f3c195a458c81d5b280098961fa4f4ad8e3d16a";
    /** SM3(RFC8785(outputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String OUTPUT_SCHEMA_DIGEST = "def19f8e792b286edfe0a0ac1506de0114c15f999946688040c740e188e60489";
    /** SM3(RFC8785(目录描述符 {"packageId","toolId","version","readOnly","directDisplay"}))。 */
    public static final String CATALOG_DIGEST = "7264506ed094dc988f2c306bdd5fabf9073955f43384592fae2c643532d6b7d9";
    private static final Pattern PATH_SEGMENT = Pattern.compile("[^/\\\\\\x00]+");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public WorkspaceGlobTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("pattern", Map.of("type", "string", "minLength", 1, "maxLength", 1024)),
                "required", List.of("pattern")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "files", Map.of("type", "array", "items", Map.of("type", "string"))
                ),
                "required", List.of("files")
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
                "Find files matching a glob pattern inside the sandboxed workspace.",
                inputSchema);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("pattern")) {
            throw new IllegalArgumentException("workspace.glob arguments must contain pattern");
        }
        String pattern = String.valueOf(arguments.get("pattern"));
        if (pattern.isBlank() || pattern.length() > 1024) {
            throw new IllegalArgumentException("pattern must be between 1 and 1024 characters");
        }
        return Map.of("pattern", pattern);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            JsonNode files = root.get("files");
            if (files != null && files.isArray()) {
                List<String> fileList = new java.util.ArrayList<>();
                for (JsonNode f : files) {
                    fileList.add(f.isTextual() ? f.asText() : "");
                }
                result.put("files", fileList);
            } else {
                result.put("files", List.of());
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("workspace.glob result is not valid JSON", e);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "workspace-glob");
        rendered.put("title", "文件匹配结果");
        rendered.put("files", output.get("files"));
        return rendered;
    }
}
