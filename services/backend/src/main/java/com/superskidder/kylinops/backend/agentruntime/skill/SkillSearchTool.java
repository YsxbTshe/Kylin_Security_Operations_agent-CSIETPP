package com.superskidder.kylinops.backend.agentruntime.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.RuntimeTool;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * kylin.skill.search：Skill 渐进式披露的**第一层**，只读 RUNTIME 工具。
 *
 * <p>按关键词检索本租户 <b>已发布</b> 的 Skill，只返回元数据（skillId / name / description /
 * trustLevel / version），<b>不返回任何正文或资源内容</b>——正文由 {@link SkillLoadTool}
 * 或 {@code kylin.vfs.read} 按需加载，这正是渐进式披露要省的上下文。
 *
 * <p>候选集来自 {@link SkillProgressiveDisclosure#listSkills}（内部走
 * {@link SkillService#listPublished}，天然按 tenant_id 隔离且只含 PUBLISHED），
 * 因此 DRAFT / REVIEWED / ISOLATED 的包在这一层就不可见；trustLevel 与 version
 * 由 {@link SkillService} 补齐，供模型在加载正文前判断该 Skill 的可信度。
 */
@Component
public class SkillSearchTool implements RuntimeTool {

    public static final String TOOL_ID = "kylin.skill.search";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-runtime-skill-search-v1";
    /** 结果来源标注：模型据此知道这些条目来自租户 Skill 注册表而非模型自身知识。 */
    public static final String SOURCE = "skill-registry";

    public static final int DEFAULT_LIMIT = 10;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 50;
    private static final int QUERY_MAX_LENGTH = 200;
    private static final int DESCRIPTION_MAX_LENGTH = 300;
    private static final List<String> SUPPORTED_FIELDS = List.of("query", "limit");

    private final SkillProgressiveDisclosure disclosure;
    private final SkillService skillService;
    private final ObjectMapper objectMapper;
    private final JsonNode inputSchemaNode;
    private final Map<String, Object> inputSchemaMap;
    private final Map<String, Object> outputSchemaMap;
    private final String inputSchemaDigest;
    private final String outputSchemaDigest;

    public SkillSearchTool(SkillProgressiveDisclosure disclosure, SkillService skillService,
            ObjectMapper objectMapper) {
        this.disclosure = disclosure;
        this.skillService = skillService;
        this.objectMapper = objectMapper;
        this.inputSchemaMap = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1, "maxLength", QUERY_MAX_LENGTH),
                        "limit", Map.of("type", "integer", "minimum", MIN_LIMIT, "maximum", MAX_LIMIT)
                ),
                "required", List.of()
        );
        this.outputSchemaMap = Map.of(
                "type", "object",
                "properties", Map.of(
                        "source", Map.of("type", "string"),
                        "query", Map.of("type", "string"),
                        "resultCount", Map.of("type", "integer"),
                        "notice", Map.of("type", "string"),
                        "results", Map.of("type", "array")
                ),
                "required", List.of("source", "resultCount", "results")
        );
        this.inputSchemaNode = objectMapper.valueToTree(inputSchemaMap);
        this.inputSchemaDigest = SkillCatalogSigner.schemaDigest(writeJson(inputSchemaMap));
        this.outputSchemaDigest = SkillCatalogSigner.schemaDigest(writeJson(outputSchemaMap));
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public boolean readOnly() { return true; }
    @Override public String riskFloor() { return "R0"; }
    @Override public boolean alwaysRequireApproval() { return false; }
    @Override public ToolIdempotency idempotency() { return ToolIdempotency.IDEMPOTENT; }
    @Override public int maxAttempts() { return 3; }
    @Override public String inputSchemaDigest() { return inputSchemaDigest; }
    @Override public String outputSchemaDigest() { return outputSchemaDigest; }
    @Override public String catalogDigest() { return "pending"; }
    @Override public List<ResourceLockTemplate> resourceLockTemplates() { return List.of(); }

    @Override
    public LlmTool llmTool() {
        return new LlmTool(TOOL_ID,
                "Search published operations Skills of the current tenant by keyword. Returns metadata only "
                        + "(skillId, name, description, trust level, version) and never the Skill body — "
                        + "call kylin.skill.load afterwards to read the body of a specific Skill. "
                        + "Omit query to list every published Skill.",
                inputSchemaNode);
    }

    @Override public Map<String, Object> inputSchema() { return inputSchemaMap; }
    @Override public Map<String, Object> outputSchema() { return outputSchemaMap; }

    @Override
    public Map<String, Object> validateArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("skill.search arguments must not be null");
        }
        for (String key : arguments.keySet()) {
            if (!SUPPORTED_FIELDS.contains(key)) {
                throw new IllegalArgumentException("skill.search arguments contain unsupported field: " + key);
            }
        }
        String query = "";
        if (arguments.containsKey("query")) {
            Object queryValue = arguments.get("query");
            if (!(queryValue instanceof String rawQuery) || rawQuery.isBlank()
                    || rawQuery.length() > QUERY_MAX_LENGTH) {
                throw new IllegalArgumentException(
                        "query must be a non-blank string of 1-" + QUERY_MAX_LENGTH + " characters");
            }
            query = rawQuery.strip();
        }
        int limit = DEFAULT_LIMIT;
        if (arguments.containsKey("limit")) {
            Object limitValue = arguments.get("limit");
            if (!(limitValue instanceof Number limitNumber)) {
                throw new IllegalArgumentException("limit must be an integer between " + MIN_LIMIT
                        + " and " + MAX_LIMIT);
            }
            limit = limitNumber.intValue();
            if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be an integer between " + MIN_LIMIT
                        + " and " + MAX_LIMIT);
            }
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        validated.put("query", query);
        validated.put("limit", limit);
        return validated;
    }

    /**
     * 执行检索。第一层披露：只读、只出元数据。
     *
     * <p>与 knowledge.search 的 fail-open 不同，这里**不吞异常**——Skill 注册表读不到
     * 时返回空列表会让模型误以为"本租户没有可用 Skill"并据此推进；由分派层收敛为结构化
     * 错误，模型能看见"检索失败"与"没有结果"的区别。
     */
    public Map<String, Object> search(String tenantId, Map<String, Object> validatedArguments) {
        String query = String.valueOf(validatedArguments.getOrDefault("query", ""));
        int limit = validatedArguments.get("limit") instanceof Number number
                ? number.intValue() : DEFAULT_LIMIT;
        List<String> tokens = tokenize(query);

        List<Map<String, Object>> results = new ArrayList<>();
        for (SkillProgressiveDisclosure.SkillSummary summary : disclosure.listSkills(tenantId)) {
            if (!matches(summary, tokens)) {
                continue;
            }
            if (results.size() >= limit) {
                break;
            }
            results.add(describe(tenantId, summary));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("source", SOURCE);
        output.put("query", query);
        output.put("resultCount", results.size());
        output.put("results", results);
        if (results.isEmpty()) {
            output.put("notice", "本租户没有匹配的已发布 Skill；未发布/已隔离的 Skill 不会出现在检索结果中");
        }
        return output;
    }

    /** 元数据层条目：绝不含正文/资源内容，只给模型判断"要不要加载"所需的信息。 */
    private Map<String, Object> describe(String tenantId, SkillProgressiveDisclosure.SkillSummary summary) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("skillId", summary.skillId());
        item.put("name", summary.name() == null ? "" : summary.name());
        item.put("description", truncate(summary.description()));
        item.put("status", SkillPackage.STATUS_PUBLISHED);
        item.put("trustLevel", SkillTrustLevel.UNTRUSTED.name());
        item.put("version", 0);
        item.put("signed", false);
        skillService.findPackage(tenantId, summary.skillId()).ifPresent(pkg -> {
            item.put("trustLevel", pkg.trustLevel().name());
            skillService.latestVersion(pkg.id()).ifPresent(version -> {
                item.put("version", version.version());
                item.put("signed", version.isSigned());
            });
        });
        // 正文入口：vfs 读取与 skill.load 都接受这个 skillId，第二层披露按需触发。
        item.put("bodyUri", "skill://" + summary.skillId() + "/SKILL.md");
        return item;
    }

    private boolean matches(SkillProgressiveDisclosure.SkillSummary summary, List<String> tokens) {
        if (tokens.isEmpty()) {
            return true;
        }
        String haystack = (summary.skillId() + " " + nullToEmpty(summary.name()) + " "
                + nullToEmpty(summary.description())).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    @Override
    public Map<String, Object> validateOutput(String resultJson) {
        try {
            JsonNode root = objectMapper.readTree(resultJson);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("source", root.path("source").asText(SOURCE));
            result.put("query", root.path("query").asText(""));
            result.put("resultCount", root.path("resultCount").asInt(0));
            result.put("notice", root.path("notice").asText(""));
            List<Object> results = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                results.add(objectMapper.convertValue(item, Map.class));
            }
            result.put("results", results);
            return result;
        } catch (Exception exception) {
            throw new IllegalArgumentException("skill.search result is not valid JSON", exception);
        }
    }

    @Override
    public Map<String, Object> render(Map<String, Object> output) {
        LinkedHashMap<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", "skill-search");
        rendered.put("title", "Skill 检索结果");
        rendered.put("source", output.getOrDefault("source", SOURCE));
        rendered.put("query", output.getOrDefault("query", ""));
        rendered.put("resultCount", output.getOrDefault("resultCount", 0));
        rendered.put("notice", output.getOrDefault("notice", ""));
        rendered.put("results", output.getOrDefault("results", List.of()));
        return rendered;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("skill.search schema cannot be serialized", exception);
        }
    }

    private static String truncate(String value) {
        String text = nullToEmpty(value).strip();
        return text.length() <= DESCRIPTION_MAX_LENGTH
                ? text : text.substring(0, DESCRIPTION_MAX_LENGTH) + "…";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
