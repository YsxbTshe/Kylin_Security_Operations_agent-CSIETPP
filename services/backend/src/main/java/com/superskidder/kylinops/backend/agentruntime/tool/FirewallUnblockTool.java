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
public class FirewallUnblockTool implements NodeTool {

    public static final String TOOL_ID = "kylin.firewall.unblock_ip";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-firewall-unblock-v1";
    public static final String INPUT_SCHEMA_DIGEST = "d4e5f60718293041526a7b8c9d0e1f2031425364758697a8b9c0d1e2f3a4b5c6";
    public static final String OUTPUT_SCHEMA_DIGEST = "e5f6071829304152637a8b9c0d1e2f30415263748596a7b8c9d0e1f2a3b4c5d6";
    public static final String CATALOG_DIGEST = "f607182930415263748a9b0c1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6";

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public FirewallUnblockTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "ip", Map.of("type", "string"),
                        "scope", Map.of("type", "string", "enum", List.of("ingress"))
                ),
                "required", List.of("ip", "scope")
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
                "Remove an ingress firewall block for a previously blocked IP address.", inputSchema);
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
        if (arguments == null || arguments.size() != 2
                || !arguments.containsKey("ip")
                || !arguments.containsKey("scope")) {
            throw new IllegalArgumentException("firewall.unblock_ip arguments must contain exactly ip and scope");
        }
        String ip = String.valueOf(arguments.get("ip")).trim();
        try {
            InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new IllegalArgumentException("ip must be a valid IP address");
        }
        String scope = String.valueOf(arguments.get("scope")).trim();
        if (!"ingress".equals(scope)) {
            throw new IllegalArgumentException("scope must be 'ingress'");
        }
        return Map.of("ip", ip, "scope", scope);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        return AbstractServiceActionTool.requireStdoutOutput(objectMapper, TOOL_ID, resultJson);
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "firewall-unblock");
        rendered.put("title", "防火墙解封");
        rendered.put("output", output.get("output"));
        rendered.put("directDisplay", false);
        return rendered;
    }
}
