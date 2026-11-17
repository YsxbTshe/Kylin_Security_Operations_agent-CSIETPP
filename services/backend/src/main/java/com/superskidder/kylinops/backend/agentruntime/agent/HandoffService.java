package com.superskidder.kylinops.backend.agentruntime.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SubAgent 招募契约（handoff）服务。
 * 负责权限衰减（子 grant ⊆ 父 grant）、预算衰减（子预算 ≤ 父剩余预算的固定比例）、
 * 以及按 output_schema 对子 Agent 结果做结构级校验。
 */
@Component
public class HandoffService {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final Map<String, Object> DEFAULT_OUTPUT_SCHEMA = Map.of("type", "object");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final double budgetRatio;

    public HandoffService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${app.agent-runtime.subagent-budget-ratio:0.5}") double budgetRatio
    ) {
        if (budgetRatio <= 0.0 || budgetRatio > 1.0) {
            throw new IllegalArgumentException("subagent-budget-ratio must be in (0, 1]");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.budgetRatio = budgetRatio;
    }

    public record Handoff(
            String id,
            String parentInstanceId,
            String childInstanceId,
            Map<String, Object> childGrant,
            Map<String, Object> childBudget,
            Map<String, Object> outputSchema,
            String status
    ) {}

    /**
     * 权限衰减：子 grant 必须是父 grant 的子集。requestedGrant 为空时直接继承父 grant。
     * 请求超出父范围的 tool/node/读写级别会被拒绝。
     */
    public Map<String, Object> attenuateGrant(Map<String, Object> parentGrant, Map<String, Object> requestedGrant) {
        boolean parentReadOnly = Boolean.TRUE.equals(parentGrant.get("readOnly"));
        List<String> parentTools = stringList(parentGrant.get("tools"));
        List<String> parentNodes = stringList(parentGrant.get("nodes"));

        if (requestedGrant == null || requestedGrant.isEmpty()) {
            return buildGrant(parentTools, parentNodes, parentReadOnly);
        }

        boolean requestedReadOnly = requestedGrant.containsKey("readOnly")
                ? Boolean.TRUE.equals(requestedGrant.get("readOnly"))
                : parentReadOnly;
        if (parentReadOnly && !requestedReadOnly) {
            throw new SubAgentContractException("SUB_AGENT_GRANT_NOT_SUBSET",
                    "parent grant is read-only; child cannot request write access");
        }

        List<String> requestedTools = stringList(requestedGrant.get("tools"));
        List<String> childTools = attenuateList("tools", parentTools, requestedTools);
        List<String> requestedNodes = stringList(requestedGrant.get("nodes"));
        List<String> childNodes = attenuateList("nodes", parentNodes, requestedNodes);

        return buildGrant(childTools, childNodes, requestedReadOnly);
    }

    private List<String> attenuateList(String field, List<String> parentValues, List<String> requestedValues) {
        if (requestedValues.isEmpty()) {
            return parentValues;
        }
        if (parentValues.isEmpty()) {
            return requestedValues;
        }
        List<String> rejected = requestedValues.stream().filter(v -> !parentValues.contains(v)).toList();
        if (!rejected.isEmpty()) {
            throw new SubAgentContractException("SUB_AGENT_GRANT_NOT_SUBSET",
                    "requested " + field + " exceed parent grant: " + rejected);
        }
        return requestedValues;
    }

    private Map<String, Object> buildGrant(List<String> tools, List<String> nodes, boolean readOnly) {
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("tools", List.copyOf(tools));
        grant.put("nodes", List.copyOf(nodes));
        grant.put("readOnly", readOnly);
        return grant;
    }

    /**
     * 预算衰减：子预算每一维 ≤ 父剩余预算 × budgetRatio。
     * requestedBudget 为空时取各维允许上限；超出上限的维度会被拒绝。
     */
    public Map<String, Object> attenuateBudget(
            Map<String, Object> parentBudget,
            Map<String, Object> usage,
            Map<String, Object> requestedBudget
    ) {
        Map<String, Object> allowed = new LinkedHashMap<>();
        for (String key : BUDGET_DIMENSIONS.keySet()) {
            long budget = longValue(parentBudget.get(key));
            long used = longValue(usage.get(BUDGET_DIMENSIONS.get(key)));
            long remaining = Math.max(0, budget - used);
            allowed.put(key, Math.max(0, Math.round(Math.floor(remaining * budgetRatio))));
        }

        if (requestedBudget == null || requestedBudget.isEmpty()) {
            return allowed;
        }

        Map<String, Object> child = new LinkedHashMap<>(allowed);
        for (Map.Entry<String, Object> entry : requestedBudget.entrySet()) {
            String key = entry.getKey();
            if (!allowed.containsKey(key)) {
                throw new SubAgentContractException("SUB_AGENT_BUDGET_EXCEEDED",
                        "unknown budget dimension: " + key);
            }
            long requested = longValue(entry.getValue());
            long cap = longValue(allowed.get(key));
            if (requested > cap) {
                throw new SubAgentContractException("SUB_AGENT_BUDGET_EXCEEDED",
                        "requested budget for " + key + " (" + requested + ") exceeds child cap (" + cap + ")");
            }
            child.put(key, requested);
        }
        return child;
    }

    /**
     * 创建 handoff（status OPEN）。先做权限/预算衰减，超出父范围即拒绝；随后写入 handoff 表。
     */
    public Handoff createHandoff(
            LeasedAgentRun parentRun,
            String parentInstanceId,
            String childInstanceId,
            String goal,
            AgentSpecSnapshot spec,
            Map<String, Object> requestedGrant,
            Map<String, Object> requestedBudget,
            Map<String, Object> outputSchema
    ) {
        Map<String, Object> parentGrant = readMap(parentRun.grantSnapshotJson());
        // 从库中读取父预算：同一租约内多次招募时预算可能已被扣减，快照会过期。
        Map<String, Object> parentBudget = runBudget(parentRun.id());
        Map<String, Object> usage = runUsage(parentRun.id());

        Map<String, Object> childGrant = attenuateGrant(parentGrant, requestedGrant);
        Map<String, Object> childBudget = attenuateBudget(parentBudget, usage, requestedBudget);
        Map<String, Object> schema = outputSchema == null || outputSchema.isEmpty()
                ? DEFAULT_OUTPUT_SCHEMA : outputSchema;

        String handoffId = "handoff-" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String grantJson = canonical(childGrant);
        jdbcTemplate.update("""
                insert into handoff (
                    id, run_id, tenant_id, parent_agent_instance_id, child_agent_instance_id, objective,
                    scope_json, input_artifact_refs_json, context_summary, pinned_skills_json,
                    grant_snapshot_json, grant_digest, budget_json, output_schema_json,
                    stop_conditions_json, status, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, '[]', ?, ?, ?, ?, ?, ?, '[]', ?, ?)
                """, handoffId, parentRun.id(), parentRun.tenantId(), parentInstanceId, childInstanceId,
                goal, parentRun.targetScopeJson(), goal, canonical(spec.allowedSkills()),
                grantJson, Sm3.hex(grantJson), canonical(childBudget), canonical(schema),
                STATUS_OPEN, Timestamp.from(now));
        return new Handoff(handoffId, parentInstanceId, childInstanceId, childGrant, childBudget, schema, STATUS_OPEN);
    }

    /**
     * 子 Agent 完成后回填结果：按 output_schema 做结构级校验。
     * 校验通过置 COMPLETED 并保存结果；不匹配置 FAILED 并拒绝。
     */
    public Map<String, Object> completeHandoff(String handoffId, String resultJson) {
        HandoffRow row = requireHandoff(handoffId);
        if (!STATUS_OPEN.equals(row.status)) {
            throw new SubAgentContractException("SUB_AGENT_HANDOFF_NOT_OPEN",
                    "handoff " + handoffId + " is not OPEN (current " + row.status + ")");
        }
        JsonNode resultNode = readNode(resultJson);
        List<String> violations = validateResult(readNode(row.outputSchemaJson), resultNode);
        Instant now = Instant.now();
        if (!violations.isEmpty()) {
            jdbcTemplate.update("""
                    update handoff set status = ?, completed_at = ? where id = ?
                    """, STATUS_FAILED, Timestamp.from(now), handoffId);
            throw new SubAgentContractException("SUB_AGENT_OUTPUT_SCHEMA_VIOLATION",
                    "handoff result violates output schema: " + String.join("; ", violations));
        }
        jdbcTemplate.update("""
                update handoff set status = ?, result_json = ?, completed_at = ? where id = ?
                """, STATUS_COMPLETED, resultJson, Timestamp.from(now), handoffId);
        return readMap(resultJson);
    }

    public void cancelHandoff(String handoffId) {
        HandoffRow row = requireHandoff(handoffId);
        if (!STATUS_OPEN.equals(row.status)) {
            throw new SubAgentContractException("SUB_AGENT_HANDOFF_NOT_OPEN",
                    "handoff " + handoffId + " is not OPEN (current " + row.status + ")");
        }
        Instant now = Instant.now();
        jdbcTemplate.update("""
                update handoff set status = ?, completed_at = ? where id = ?
                """, STATUS_CANCELLED, Timestamp.from(now), handoffId);
    }

    /**
     * 结构化失败记录：OPEN→FAILED 并写入 {errorCode, message} 结果；
     * 若已是 FAILED（schema 校验拒绝路径）则补齐失败原因。
     * @return 是否发生了 OPEN→FAILED 流转（用于预算归还的幂等判定）
     */
    public boolean failHandoff(String handoffId, String errorCode, String message) {
        HandoffRow row = requireHandoff(handoffId);
        String resultJson = canonical(Map.of(
                "errorCode", errorCode == null || errorCode.isBlank() ? "SUB_AGENT_FAILED" : errorCode,
                "message", message == null ? "" : message));
        if (STATUS_OPEN.equals(row.status)) {
            jdbcTemplate.update("""
                    update handoff set status = ?, result_json = ?, completed_at = ? where id = ?
                    """, STATUS_FAILED, resultJson, Timestamp.from(Instant.now()), handoffId);
            return true;
        }
        if (STATUS_FAILED.equals(row.status)) {
            jdbcTemplate.update("""
                    update handoff set result_json = ? where id = ? and result_json is null
                    """, resultJson, handoffId);
        }
        return false;
    }

    /** 子预算中未使用的部分（逐维 clamp ≥ 0），用于终态归还父预算。 */
    public Map<String, Object> unusedBudget(Map<String, Object> childBudget, Map<String, Object> childUsage) {
        Map<String, Object> unused = new LinkedHashMap<>();
        for (Map.Entry<String, String> dimension : BUDGET_DIMENSIONS.entrySet()) {
            long remaining = longValue(childBudget.get(dimension.getKey()))
                    - longValue(childUsage.get(dimension.getValue()));
            unused.put(dimension.getKey(), Math.max(0, remaining));
        }
        return unused;
    }

    /** output_schema 结构级校验：required 字段存在、声明类型的字段类型匹配。 */
    List<String> validateResult(JsonNode schemaNode, JsonNode resultNode) {
        List<String> violations = new ArrayList<>();
        if (schemaNode == null || schemaNode.isNull() || schemaNode.isEmpty()) {
            return violations;
        }
        String expectedType = schemaNode.path("type").asText("");
        if (!expectedType.isBlank() && !typeMatches(expectedType, resultNode)) {
            violations.add("root expected type " + expectedType);
            return violations;
        }
        if (!resultNode.isObject()) {
            return violations;
        }
        JsonNode required = schemaNode.path("required");
        if (required.isArray()) {
            for (JsonNode field : required) {
                String name = field.asText();
                if (!resultNode.has(name) || resultNode.get(name).isNull()) {
                    violations.add("missing required field: " + name);
                }
            }
        }
        JsonNode properties = schemaNode.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(property -> {
                JsonNode value = resultNode.get(property.getKey());
                if (value == null || value.isNull()) {
                    return;
                }
                String propertyType = property.getValue().path("type").asText("");
                if (!propertyType.isBlank() && !typeMatches(propertyType, value)) {
                    violations.add("field " + property.getKey() + " expected type " + propertyType);
                }
            });
        }
        return violations;
    }

    private boolean typeMatches(String type, JsonNode node) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            default -> true;
        };
    }

    private HandoffRow requireHandoff(String handoffId) {
        List<HandoffRow> rows = jdbcTemplate.query("""
                select id, status, output_schema_json from handoff where id = ?
                """, (rs, rowNum) -> new HandoffRow(rs.getString("id"), rs.getString("status"),
                rs.getString("output_schema_json")), handoffId);
        if (rows.isEmpty()) {
            throw new SubAgentContractException("SUB_AGENT_HANDOFF_NOT_FOUND", "handoff not found: " + handoffId);
        }
        return rows.get(0);
    }

    public Map<String, Object> runUsage(String runId) {
        List<String> rows = jdbcTemplate.query(
                "select usage_json from agent_run where id = ?",
                (rs, rowNum) -> rs.getString("usage_json"), runId);
        return rows.isEmpty() ? Map.of() : readMap(rows.get(0));
    }

    public Map<String, Object> runBudget(String runId) {
        List<String> rows = jdbcTemplate.query(
                "select budget_json from agent_run where id = ?",
                (rs, rowNum) -> rs.getString("budget_json"), runId);
        return rows.isEmpty() ? Map.of() : readMap(rows.get(0));
    }

    private record HandoffRow(String id, String status, String outputSchemaJson) {}

    private static final Map<String, String> BUDGET_DIMENSIONS = Map.of(
            "maxModelInvocations", "modelInvocations",
            "maxToolInvocations", "toolInvocations",
            "maxInputTokens", "inputTokens",
            "maxOutputTokens", "outputTokens",
            "maxWallClockSeconds", "wallClockSeconds"
    );

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String canonical(Object value) {
        return CanonicalJson.canonicalize(json(value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid JSON object", exception);
        }
    }

    private JsonNode readNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new SubAgentContractException("SUB_AGENT_OUTPUT_SCHEMA_VIOLATION",
                    "value is not valid JSON: " + exception.getMessage());
        }
    }
}
