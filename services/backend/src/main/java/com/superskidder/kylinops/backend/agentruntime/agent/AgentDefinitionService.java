package com.superskidder.kylinops.backend.agentruntime.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agent 定义读取服务：按 tenant 查 definition/version，presetSpec 冻结为 AgentSpecSnapshot。
 * 动态规格不经过本服务落库，仅以 AgentSpecSnapshot 在内存中传递。
 */
@Component
public class AgentDefinitionService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentDefinitionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record DefinitionSummary(String id, String definitionKey, String displayName, boolean enabled) {}

    public record VersionView(String versionId, int version, AgentSpecSnapshot spec) {}

    public List<DefinitionSummary> definitions(String tenantId) {
        return jdbcTemplate.query("""
                select id, definition_key, display_name, enabled
                from agent_definition where tenant_id = ? order by definition_key
                """, (rs, rowNum) -> new DefinitionSummary(rs.getString("id"),
                rs.getString("definition_key"), rs.getString("display_name"), rs.getBoolean("enabled")), tenantId);
    }

    public Optional<DefinitionSummary> findDefinition(String tenantId, String definitionId) {
        List<DefinitionSummary> rows = jdbcTemplate.query("""
                select id, definition_key, display_name, enabled
                from agent_definition where tenant_id = ? and id = ?
                """, (rs, rowNum) -> new DefinitionSummary(rs.getString("id"),
                rs.getString("definition_key"), rs.getString("display_name"), rs.getBoolean("enabled")),
                tenantId, definitionId);
        return rows.stream().findFirst();
    }

    public Optional<VersionView> latestVersion(String tenantId, String definitionId) {
        List<VersionView> rows = jdbcTemplate.query("""
                select v.id, v.version, v.instructions, v.allowed_skills_json, v.default_budget_json, v.content_digest
                from agent_definition_version v
                join agent_definition d on d.id = v.definition_id and d.tenant_id = v.tenant_id
                where v.tenant_id = ? and v.definition_id = ?
                order by v.version desc limit 1
                """, (rs, rowNum) -> new VersionView(rs.getString("id"), rs.getInt("version"),
                AgentSpecSnapshot.preset(rs.getString("instructions"),
                        readList(rs.getString("allowed_skills_json")),
                        readMap(rs.getString("default_budget_json")),
                        rs.getString("content_digest"))), tenantId, definitionId);
        return rows.stream().findFirst();
    }

    /** 读取 preset 规格；不存在时抛 IllegalArgumentException。 */
    public AgentSpecSnapshot presetSpec(String tenantId, String definitionId) {
        return latestVersion(tenantId, definitionId)
                .map(VersionView::spec)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent definition has no version: tenant=" + tenantId + " definition=" + definitionId));
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid allowed_skills JSON", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid default_budget JSON", exception);
        }
    }
}
