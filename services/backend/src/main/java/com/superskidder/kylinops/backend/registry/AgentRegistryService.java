package com.superskidder.kylinops.backend.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.policy.RiskCode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 专家注册表 + 知识域 + Agent IAM。
 *
 * 提供专家 Agent 的常驻目录（CRUD/启停）与**能力授权判定** {@link #authorize}：给定专家、工具与
 * 风险，按其注册封套（allowed_tools + max_risk + enabled）做 fail-closed 判定。这是 Agent 版的最小
 * 权限——与人类 RBAC/ABAC 正交：ABAC 约束「人对资源」，本判定约束「Agent 对工具」。
 *
 * <p><b>{@link #authorize} 在执行路径上</b>：{@code DispatchGate} 的第 8 步（工具维 ABAC 之后、
 * 主机维 ABAC 之前）对每一次工具下发调用它。判定主体经 {@code agent_instance.registry_agent_key}
 * （迁移 V10 的可空列，招募子 Agent 时由 {@code recruit_agent} 的 {@code agent_key} 参数写入）确定。
 * 因此这里的启停、工具封套、风险上限都是**实时生效**的，不是只给页面看的目录。
 *
 * <p>边界：没有注册身份的执行主体（人直接发起的根 Agent、存量 run、招募时未声明专家的子 Agent）
 * 不做本判定——把「无身份」当作「拒绝」会一次性打断全部既有链路。fail-closed 的落点是
 * 「有身份就必须在封套内」。
 */
@Service
public class AgentRegistryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentRegistryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // --- 专家 Agent ---

    public List<AgentRegistryEntry> listAgents(String organizationId) {
        return jdbcTemplate.query("""
                select id, agent_key, name, role, description, knowledge_domains, allowed_tools,
                       max_risk, budget_tokens, enabled, version, created_at, updated_at
                from app_agent_registry where organization_id = ? order by role, agent_key
                """, (rs, n) -> mapEntry(rs), organizationId);
    }

    public AgentRegistryEntry getAgent(String organizationId, String agentKey) {
        List<AgentRegistryEntry> rows = jdbcTemplate.query("""
                select id, agent_key, name, role, description, knowledge_domains, allowed_tools,
                       max_risk, budget_tokens, enabled, version, created_at, updated_at
                from app_agent_registry where organization_id = ? and agent_key = ?
                """, (rs, n) -> mapEntry(rs), organizationId, agentKey);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "专家 Agent 不存在: " + agentKey);
        }
        return rows.get(0);
    }

    /** 按 agentKey upsert。 */
    public AgentRegistryEntry upsert(String organizationId, AgentUpsertRequest request) {
        String allowedToolsJson = writeJson(dedupeTrim(request.allowedTools()));
        String domainsCsv = String.join(",", dedupeTrim(request.knowledgeDomains()));
        String maxRisk = RiskCode.normalize(request.maxRisk()).name();
        long budget = request.budgetTokens() == null ? 0L : Math.max(0L, request.budgetTokens());
        boolean enabled = request.enabled() == null || request.enabled();
        String version = request.version() == null || request.version().isBlank() ? "1.0.0" : request.version().trim();
        LocalDateTime now = LocalDateTime.now();

        Integer existing = jdbcTemplate.queryForObject(
                "select count(1) from app_agent_registry where organization_id = ? and agent_key = ?",
                Integer.class, organizationId, request.agentKey());
        if (existing != null && existing > 0) {
            jdbcTemplate.update("""
                    update app_agent_registry
                    set name = ?, role = ?, description = ?, knowledge_domains = ?, allowed_tools = ?,
                        max_risk = ?, budget_tokens = ?, enabled = ?, version = ?, updated_at = ?
                    where organization_id = ? and agent_key = ?
                    """,
                    request.name(), request.role(), request.description(), domainsCsv, allowedToolsJson,
                    maxRisk, budget, enabled, version, Timestamp.valueOf(now),
                    organizationId, request.agentKey());
        } else {
            jdbcTemplate.update("""
                    insert into app_agent_registry (id, organization_id, agent_key, name, role, description,
                        knowledge_domains, allowed_tools, max_risk, budget_tokens, enabled, version, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    "ag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    organizationId, request.agentKey(), request.name(), request.role(), request.description(),
                    domainsCsv, allowedToolsJson, maxRisk, budget, enabled, version,
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        }
        return getAgent(organizationId, request.agentKey());
    }

    public AgentRegistryEntry setEnabled(String organizationId, String agentKey, boolean enabled) {
        int updated = jdbcTemplate.update("""
                update app_agent_registry set enabled = ?, updated_at = ?
                where organization_id = ? and agent_key = ?
                """, enabled, Timestamp.valueOf(LocalDateTime.now()), organizationId, agentKey);
        if (updated == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "专家 Agent 不存在: " + agentKey);
        }
        return getAgent(organizationId, agentKey);
    }

    public void delete(String organizationId, String agentKey) {
        jdbcTemplate.update("delete from app_agent_registry where organization_id = ? and agent_key = ?",
                organizationId, agentKey);
    }

    /**
     * 能力授权判定（Agent IAM 核心）：fail-closed。
     * 未注册/停用 → 拒；工具不在允许集 → 拒；风险超过声明上限 → 拒；否则允许。
     */
    public AgentAuthorization authorize(String organizationId, String agentKey, String toolName, String riskCode) {
        List<AgentRegistryEntry> rows = jdbcTemplate.query("""
                select id, agent_key, name, role, description, knowledge_domains, allowed_tools,
                       max_risk, budget_tokens, enabled, version, created_at, updated_at
                from app_agent_registry where organization_id = ? and agent_key = ?
                """, (rs, n) -> mapEntry(rs), organizationId, agentKey);
        if (rows.isEmpty()) {
            return AgentAuthorization.deny("专家 Agent 未注册: " + agentKey);
        }
        AgentRegistryEntry entry = rows.get(0);
        if (!entry.enabled()) {
            return AgentAuthorization.deny("专家 Agent 已停用: " + agentKey);
        }
        String tool = toolName == null ? "" : toolName.trim();
        boolean toolAllowed = entry.allowedTools().stream().anyMatch(t -> t != null && t.trim().equalsIgnoreCase(tool));
        if (!toolAllowed) {
            return AgentAuthorization.deny("工具不在该 Agent 能力封套内: " + tool);
        }
        RiskCode requested = RiskCode.normalize(riskCode);
        RiskCode ceiling = RiskCode.normalize(entry.maxRisk());
        if (requested.ordinal() > ceiling.ordinal()) {
            return AgentAuthorization.deny("动作风险 " + requested + " 超过该 Agent 上限 " + ceiling);
        }
        return AgentAuthorization.allow();
    }

    /** 仅工具成员判定（忽略风险上限），供快速能力检查。停用或未注册返回 false。 */
    public boolean permits(String organizationId, String agentKey, String toolName) {
        try {
            AgentRegistryEntry entry = getAgent(organizationId, agentKey);
            String tool = toolName == null ? "" : toolName.trim();
            return entry.enabled()
                    && entry.allowedTools().stream().anyMatch(t -> t != null && t.trim().equalsIgnoreCase(tool));
        } catch (AppException exception) {
            return false;
        }
    }

    // --- 知识域 ---

    public List<KnowledgeDomain> listDomains(String organizationId) {
        return jdbcTemplate.query("""
                select id, code, name, description from app_knowledge_domain
                where organization_id = ? order by code
                """, (rs, n) -> new KnowledgeDomain(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("description")),
                organizationId);
    }

    public KnowledgeDomain upsertDomain(String organizationId, String code, String name, String description) {
        if (code == null || code.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "知识域 code 不能为空");
        }
        String normalized = code.trim();
        Integer existing = jdbcTemplate.queryForObject(
                "select count(1) from app_knowledge_domain where organization_id = ? and code = ?",
                Integer.class, organizationId, normalized);
        if (existing != null && existing > 0) {
            jdbcTemplate.update("update app_knowledge_domain set name = ?, description = ? where organization_id = ? and code = ?",
                    name, description, organizationId, normalized);
        } else {
            jdbcTemplate.update("""
                    insert into app_knowledge_domain (id, organization_id, code, name, description, created_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, "kd-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    organizationId, normalized, name, description, Timestamp.valueOf(LocalDateTime.now()));
        }
        return listDomains(organizationId).stream()
                .filter(d -> d.code().equalsIgnoreCase(normalized)).findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "知识域写入失败"));
    }

    // --- internals ---

    private AgentRegistryEntry mapEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentRegistryEntry(
                rs.getString("id"),
                rs.getString("agent_key"),
                rs.getString("name"),
                rs.getString("role"),
                rs.getString("description"),
                splitCsv(rs.getString("knowledge_domains")),
                readTools(rs.getString("allowed_tools")),
                rs.getString("max_risk"),
                rs.getLong("budget_tokens"),
                rs.getBoolean("enabled"),
                rs.getString("version"),
                iso(rs.getTimestamp("created_at")),
                iso(rs.getTimestamp("updated_at"))
        );
    }

    private List<String> readTools(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> tools = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return tools == null ? List.of() : tools;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> dedupeTrim(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String iso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }
}
