package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FirewallBlockTool implements NodeTool {

    public static final String TOOL_ID = "kylin.firewall.block_ip";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-firewall-block-v1";
    public static final String INPUT_SCHEMA_DIGEST = "a1b2c3d4e5f60718293a4b5c6d7e8f900112233445566778899aabbccddeeff";
    public static final String OUTPUT_SCHEMA_DIGEST = "b2c3d4e5f6071829304a5b6c7d8e9f0011223344556677889900aabbccddeeff";
    public static final String CATALOG_DIGEST = "c3d4e5f607182930415a6b7c8d9e0f102132435465768798a9b0c1d2e3f4a5b6";

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public FirewallBlockTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "ip", Map.of("type", "string", "minLength", 1),
                        "scope", Map.of("type", "string", "minLength", 1),
                        "duration", Map.of("type", "string", "minLength", 1)
                ),
                "required", List.of("ip", "scope", "duration")
        );
        this.outputSchemaMap = AbstractServiceActionTool.stdoutOutputSchema();
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
        return List.of(new ResourceLockTemplate("firewall:{ip}", "WRITE_EXCLUSIVE"));
    }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Block an IP via firewalld for a duration such as 10m or 1h.", inputSchema);
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
        if (arguments == null
                || !arguments.containsKey("ip")
                || !arguments.containsKey("scope")
                || !arguments.containsKey("duration")) {
            throw new IllegalArgumentException("firewall.block_ip arguments must contain ip, scope, and duration");
        }
        String ip = String.valueOf(arguments.get("ip")).trim();
        try {
            InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new IllegalArgumentException("ip must be a valid IP address");
        }
        String scope = String.valueOf(arguments.get("scope")).trim();
        if (scope.isEmpty()) {
            throw new IllegalArgumentException("scope is required");
        }
        String duration = String.valueOf(arguments.get("duration")).trim();
        if (!duration.matches("\\d+[smh]")) {
            throw new IllegalArgumentException("duration must look like 10m, 30s, or 1h");
        }
        return Map.of("ip", ip, "scope", scope, "duration", duration);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        return AbstractServiceActionTool.requireStdoutOutput(objectMapper, TOOL_ID, resultJson);
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "firewall-block");
        rendered.put("title", "防火墙封禁");
        rendered.put("output", output.get("output"));
        rendered.put("directDisplay", false);
        return rendered;
    }
}
