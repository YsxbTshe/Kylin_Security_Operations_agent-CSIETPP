package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ServiceStatusTool implements NodeTool {

    public static final String TOOL_ID = "kylin.service.status";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-service-status-v1";
    public static final String INPUT_SCHEMA_DIGEST = "3346f6fc951d9f8960552380493f3219306f7d40e06a2140b62cad77a7de2e99";
    public static final String OUTPUT_SCHEMA_DIGEST = "4457dc33e680417c37ded0084841fe05486311be0cf5264afcb0a2c44f576608";
    public static final String CATALOG_DIGEST = "37a500279d431d8c98da56d8de86ce67acb5ff4dde852260086e6293d5cdba80";
    private static final Set<String> OUTPUT_FIELDS = Set.of("service", "activeState", "subState", "loadState", "unitFileState");
    private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9@._-]{1,128}");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public ServiceStatusTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("service", Map.of("type", "string", "minLength", 1, "maxLength", 128)),
                "required", java.util.List.of("service")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "service", Map.of("type", "string"),
                        "activeState", Map.of("type", "string"),
                        "subState", Map.of("type", "string"),
                        "loadState", Map.of("type", "string"),
                        "unitFileState", Map.of("type", "string")
                ),
                "required", java.util.List.of("service", "activeState", "subState", "loadState", "unitFileState")
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
                "Read the active, sub, load and unit-file state of one allowlisted systemd service.", inputSchema);
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
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("service")) {
            throw new IllegalArgumentException("service.status arguments must contain only service");
        }
        String service = String.valueOf(arguments.get("service")).trim();
        if (!SERVICE_NAME.matcher(service).matches()) {
            throw new IllegalArgumentException("service must be a valid systemd unit name with at most 128 characters");
        }
        return Map.of("service", service);
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            if (!root.isObject() || root.size() != OUTPUT_FIELDS.size()) {
                throw new IllegalArgumentException("service.status result does not match the strict output schema");
            }
            Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                if (!OUTPUT_FIELDS.contains(names.next())) {
                    throw new IllegalArgumentException("service.status result contains an unknown field");
                }
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (String field : java.util.List.of("service", "activeState", "subState", "loadState", "unitFileState")) {
                JsonNode value = root.get(field);
                if (value == null || !value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalArgumentException("service.status result field is missing or blank: " + field);
                }
                result.put(field, value.asText());
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("service.status result is not valid JSON", exception);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "service-status");
        rendered.put("title", output.get("service") + " 服务状态");
        rendered.put("service", output.get("service"));
        rendered.put("activeState", output.get("activeState"));
        rendered.put("subState", output.get("subState"));
        rendered.put("loadState", output.get("loadState"));
        rendered.put("unitFileState", output.get("unitFileState"));
        rendered.put("directDisplay", true);
        return rendered;
    }
}
