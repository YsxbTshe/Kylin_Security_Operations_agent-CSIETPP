package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class AbstractServiceActionTool implements NodeTool {

    private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9@._-]{1,128}");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchema;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    protected AbstractServiceActionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("service", Map.of("type", "string", "minLength", 1, "maxLength", 128)),
                "required", List.of("service")
        );
        this.outputSchemaMap = serviceActionOutputSchema();
        this.inputSchema = objectMapper.valueToTree(inputSchemaMap);
    }

    static Map<String, Object> stdoutOutputSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("output", Map.of("type", "string")),
                "required", List.of("output")
        );
    }

    static Map<String, Object> serviceActionOutputSchema() {
        Map<String, Object> string = Map.of("type", "string");
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "service", string,
                        "action", string,
                        "loadState", string,
                        "activeState", string,
                        "subState", string,
                        "mainPID", string,
                        "unitFileState", string,
                        "activeEnterTimestamp", string
                ),
                "required", List.of("service", "action", "activeState")
        );
    }

    protected abstract String description();

    @Override public boolean readOnly() { return false; }
    @Override public String riskFloor() { return "R3"; }
    @Override public boolean alwaysRequireApproval() { return true; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.AT_MOST_ONCE; }
    @Override public int maxAttempts() { return 1; }

    @Override
    public List<ResourceLockTemplate> resourceLockTemplates() {
        return List.of(new ResourceLockTemplate("service:{service}", "WRITE_EXCLUSIVE"));
    }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(toolId(), description(), inputSchema);
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
            throw new IllegalArgumentException(toolId() + " arguments must contain only service");
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
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(toolId() + " result must be a JSON object");
            }
            // Backward-compatible with older executors while accepting the current structured
            // systemctl result emitted by node-executor.
            if (root.has("output") && root.get("output").isTextual()) {
                return requireStdoutOutput(objectMapper, toolId(), resultJson);
            }
            for (String field : List.of("service", "action", "activeState")) {
                if (!root.has(field) || !root.get(field).isTextual()) {
                    throw new IllegalArgumentException(
                            toolId() + " result must contain string fields service, action and activeState");
                }
            }
            return objectMapper.convertValue(root, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(toolId() + " result is not valid JSON", exception);
        }
    }

    static Map<String, Object> requireStdoutOutput(ObjectMapper objectMapper, String toolId, String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            if (root == null || !root.isObject() || !root.has("output") || !root.get("output").isTextual()) {
                throw new IllegalArgumentException(toolId + " result must be {\"output\": string}");
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("output", root.get("output").asText());
            return result;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(toolId + " result is not valid JSON", exception);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "service-action");
        rendered.put("title", toolId());
        rendered.put("output", output.containsKey("output")
                ? output.get("output")
                : output.get("service") + " " + output.get("action") + " -> " + output.get("activeState"));
        rendered.put("details", output);
        rendered.put("directDisplay", false);
        return rendered;
    }
}
