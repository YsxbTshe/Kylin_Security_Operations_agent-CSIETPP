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
public class WorkspacePythonTool implements NodeTool {

    public static final String TOOL_ID = "kylin.workspace.python";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-workspace-python-v1";
    /** SM3(RFC8785(inputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String INPUT_SCHEMA_DIGEST = "82694e584c372981a6b793eb41da67c6373d7bc3a3a2a07e0105210c96345aa7";
    /** SM3(RFC8785(outputSchema))，与 V63__workspace_tool_digests.sql 回填值一致。 */
    public static final String OUTPUT_SCHEMA_DIGEST = "597a77b56fd9b89efa64ae8924addfe66195994f758c938a62eb98c53088b7d2";
    /** SM3(RFC8785(目录描述符 {"packageId","toolId","version","readOnly","directDisplay"}))。 */
    public static final String CATALOG_DIGEST = "cbfd77741ad89bce8136e8b77c3306be7efa9b80189a8497aa34e1d0bbb1a898";
    private static final Pattern PATH_SEGMENT = Pattern.compile("[^/\\\\\\x00]+");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public WorkspacePythonTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("script", Map.of("type", "string", "minLength", 1, "maxLength", 100000)),
                "required", List.of("script")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "output", Map.of("type", "string"),
                        "exitCode", Map.of("type", "integer")
                ),
                "required", List.of("output", "exitCode")
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
                "Execute a python3 script inside the sandboxed workspace. The container has no network, no host access, and a read-only root filesystem.",
                inputSchema);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("script")) {
            throw new IllegalArgumentException("workspace.python arguments must contain script");
        }
        String script = String.valueOf(arguments.get("script"));
        if (script.isBlank() || script.length() > 100000) {
            throw new IllegalArgumentException("script must be between 1 and 100000 characters");
        }
        return Map.of("script", script);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            JsonNode output = root.get("output");
            result.put("output", output != null && output.isTextual() ? output.asText() : "");
            JsonNode exitCode = root.get("exitCode");
            result.put("exitCode", exitCode != null && exitCode.isNumber() ? exitCode.asInt() : -1);
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("workspace.python result is not valid JSON", e);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "workspace-python");
        rendered.put("title", "Python 执行结果");
        rendered.put("output", output.get("output"));
        rendered.put("exitCode", output.get("exitCode"));
        return rendered;
    }
}
