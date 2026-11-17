package com.superskidder.kylinops.backend.agentruntime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * recruit_agent：招募 SubAgent 的 RUNTIME 工具。executor 语义为 RUNTIME（实现 RuntimeTool 标记接口）。
 */
@Component
public class RecruitAgentTool implements RuntimeTool {

    public static final String TOOL_ID = "kylin.runtime.recruit_agent";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-runtime-recruit-v1";
    public static final String INPUT_SCHEMA_DIGEST = "pending";
    public static final String OUTPUT_SCHEMA_DIGEST = "pending";
    public static final String CATALOG_DIGEST = "pending";
    public static final String DEFAULT_DYNAMIC_INSTRUCTIONS =
            "You are a recruited sub-agent. Achieve the delegated goal within the granted scope and budget, "
                    + "then report a structured result conforming to the handoff output schema.";

    private static final int GOAL_MAX_LENGTH = 4_000;
    private static final int INSTRUCTIONS_MAX_LENGTH = 100_000;
    /** 与 app_agent_registry.agent_key / agent_instance.registry_agent_key 的列宽一致。 */
    private static final int AGENT_KEY_MAX_LENGTH = 64;
    private static final List<String> SUPPORTED_FIELDS =
            List.of("goal", "spec_instructions", "budget", "output_schema", "agent_key");

    private final ObjectMapper objectMapper;
    private final JsonNode inputSchemaNode;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;

    public RecruitAgentTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "goal", Map.of("type", "string", "minLength", 1, "maxLength", GOAL_MAX_LENGTH),
                        "spec_instructions", Map.of("type", "string", "maxLength", INSTRUCTIONS_MAX_LENGTH),
                        "budget", Map.of("type", "object"),
                        "output_schema", Map.of("type", "object"),
                        // 注册表专家身份：把子 Agent 钉进该专家的能力封套（fail-closed）。
                        //
                        // **必填**。此前可省略，而省略即无身份、不受封套约束——等于把
                        // 「要不要接受约束」交给被约束方自己决定，一个不声明身份的 Agent
                        // 就绕过了整套专家 IAM。现在改为必填，并在招募时对注册表校验：
                        // 未注册 / 已停用一律拒绝招募，拒绝信息里会列出可用专家。
                        "agent_key", Map.of("type", "string", "minLength", 1,
                                "maxLength", AGENT_KEY_MAX_LENGTH)
                ),
                "required", List.of("goal", "agent_key")
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "properties", Map.of(
                        "handoffId", Map.of("type", "string"),
                        "agentInstanceId", Map.of("type", "string"),
                        "childRunId", Map.of("type", "string"),
                        "status", Map.of("type", "string")
                ),
                "required", List.of("handoffId", "agentInstanceId")
        );
        this.inputSchemaNode = objectMapper.valueToTree(inputSchemaMap);
    }

    public record RecruitRequest(
            String goal,
            String specInstructions,
            Map<String, Object> budget,
            Map<String, Object> outputSchema,
            /** 注册表专家 key，可空；非空时子 Agent 的工具调用受该专家能力封套约束。 */
            String agentKey
    ) {}

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return false; }
    @Override public String riskFloor() { return "R2"; }
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
                "Recruit a sub-agent to pursue a delegated sub-goal. The sub-agent runs with an attenuated "
                        + "grant and budget derived from the current run; depth, concurrency and total-count "
                        + "limits are enforced by the runtime. Set agent_key to the registry key of the "
                        + "expert agent that should own this sub-goal (e.g. sense-collector, "
                        + "incident-responder, cleanup-operator); every tool call the sub-agent makes is then "
                        + "additionally constrained to that expert's registered tool set and risk ceiling.",
                inputSchemaNode);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("recruit_agent arguments must not be null");
        }
        for (String key : arguments.keySet()) {
            if (!SUPPORTED_FIELDS.contains(key)) {
                throw new IllegalArgumentException("recruit_agent arguments contain unsupported field: " + key);
            }
        }
        String goal = requireString(arguments, "goal", "goal");
        if (goal.isBlank() || goal.length() > GOAL_MAX_LENGTH) {
            throw new IllegalArgumentException("goal must be 1-" + GOAL_MAX_LENGTH + " non-blank characters");
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        validated.put("goal", goal);
        if (arguments.containsKey("spec_instructions")) {
            String instructions = requireString(arguments, "spec_instructions", "spec_instructions");
            if (instructions.length() > INSTRUCTIONS_MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "spec_instructions must not exceed " + INSTRUCTIONS_MAX_LENGTH + " characters");
            }
            validated.put("spec_instructions", instructions);
        }
        if (arguments.containsKey("budget")) {
            validated.put("budget", requireMap(arguments, "budget"));
        }
        if (arguments.containsKey("output_schema")) {
            validated.put("output_schema", requireMap(arguments, "output_schema"));
        }
        // agent_key 必填：不允许「不声明身份」这条绕过整套专家能力封套的通路。
        String agentKey = requireString(arguments, "agent_key", "agent_key").trim();
        if (agentKey.isEmpty() || agentKey.length() > AGENT_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "agent_key must be 1-" + AGENT_KEY_MAX_LENGTH + " non-blank characters");
        }
        validated.put("agent_key", agentKey);
        return validated;
    }

    /** 从校验后的参数组装招募请求（动态规格仅内存传递）。 */
    public RecruitRequest requestFrom(Map<String, Object> arguments) {
        Map<String, Object> validated = validateArguments(arguments);
        return new RecruitRequest(
                (String) validated.get("goal"),
                (String) validated.get("spec_instructions"),
                castMap(validated.get("budget")),
                castMap(validated.get("output_schema")),
                (String) validated.get("agent_key")
        );
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("handoffId", textOrDefault(root.get("handoffId")));
            result.put("agentInstanceId", textOrDefault(root.get("agentInstanceId")));
            result.put("childRunId", textOrDefault(root.get("childRunId")));
            result.put("status", textOrDefault(root.get("status")));
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("recruit_agent result is not valid JSON", exception);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "recruit-agent");
        rendered.put("title", "SubAgent 招募结果");
        rendered.put("handoffId", output.get("handoffId"));
        rendered.put("agentInstanceId", output.get("agentInstanceId"));
        rendered.put("childRunId", output.get("childRunId"));
        rendered.put("status", output.get("status"));
        return rendered;
    }

    private String textOrDefault(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }

    private String requireString(Map<String, Object> arguments, String key, String label) {
        Object value = arguments.get(key);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        return stringValue;
    }

    private Map<String, Object> requireMap(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof Map<?, ?> mapValue)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        mapValue.forEach((k, v) -> typed.put(String.valueOf(k), v));
        return typed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value == null ? null : (Map<String, Object>) value;
    }
}
