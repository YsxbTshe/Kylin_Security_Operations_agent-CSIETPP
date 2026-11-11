package com.superskidder.kylinops.backend.agentruntime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolExecutor;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * kylin.skill.search / kylin.skill.load 的工具层验收：参数边界、渐进式披露分层
 * （第一层只出元数据）、未发布/已隔离拒绝、路径穿越与越权路径拒绝。
 */
class SkillRuntimeToolTest {

    private static final String TENANT = "org-alpha";
    private static final String OTHER_TENANT = "org-beta";
    private static final String SKILL_ID = "kylin.ops.disk-triage";

    private SkillService service;
    private SkillImporter importer;
    private SkillSearchTool searchTool;
    private SkillLoadTool loadTool;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SkillTestDatabase.create();
        ObjectMapper objectMapper = new ObjectMapper();
        service = new SkillService(jdbcTemplate);
        importer = new SkillImporter(service);
        SkillProgressiveDisclosure disclosure = new SkillProgressiveDisclosure(service);
        searchTool = new SkillSearchTool(disclosure, service, objectMapper);
        loadTool = new SkillLoadTool(disclosure, service, objectMapper);
    }

    @Test
    void toolsDeclareReadOnlyRuntimeSemantics() {
        for (var tool : List.of(searchTool, loadTool)) {
            assertThat(tool.executor()).isEqualTo(ToolExecutor.RUNTIME);
            assertThat(tool.readOnly()).isTrue();
            assertThat(tool.riskFloor()).isEqualTo("R0");
            assertThat(tool.alwaysRequireApproval()).isFalse();
            assertThat(tool.idempotency()).isEqualTo(ToolIdempotency.IDEMPOTENT);
            assertThat(tool.llmTool()).isNotNull();
            assertThat(tool.resourceLockTemplates()).isEmpty();
            // schema digest 由真实 schema 现算，不留 'pending' 占位。
            assertThat(tool.inputSchemaDigest()).matches("[0-9a-f]{64}");
            assertThat(tool.outputSchemaDigest()).matches("[0-9a-f]{64}");
        }
        assertThat(searchTool.toolId()).isEqualTo("kylin.skill.search");
        assertThat(loadTool.toolId()).isEqualTo("kylin.skill.load");
    }

    @Test
    void searchValidatesArguments() {
        assertThatThrownBy(() -> searchTool.validateArguments(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> searchTool.validateArguments(Map.of("unknown", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field");
        assertThatThrownBy(() -> searchTool.validateArguments(Map.of("query", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> searchTool.validateArguments(Map.of("query", "x", "limit", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> searchTool.validateArguments(Map.of("query", "x", "limit", 999)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> searchTool.validateArguments(Map.of("query", "x", "limit", "5")))
                .isInstanceOf(IllegalArgumentException.class);

        // query 可省：省略即列出全部已发布 Skill。
        assertThat(searchTool.validateArguments(Map.of()))
                .containsEntry("query", "")
                .containsEntry("limit", SkillSearchTool.DEFAULT_LIMIT);
    }

    @Test
    void searchReturnsMetadataLayerOnly() {
        publishValidSkill();
        service.promoteTrust(TENANT, SKILL_ID, SkillTrustLevel.ORG_REVIEWED);

        Map<String, Object> output = searchTool.search(TENANT, searchTool.validateArguments(Map.of("query", "磁盘")));

        assertThat(output).containsEntry("source", SkillSearchTool.SOURCE);
        assertThat(output).containsEntry("resultCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        Map<String, Object> item = results.get(0);
        assertThat(item).containsEntry("skillId", SKILL_ID)
                .containsEntry("name", "磁盘告警排查")
                .containsEntry("description", "只读磁盘排查 runbook")
                .containsEntry("trustLevel", "ORG_REVIEWED")
                .containsEntry("version", 1)
                .containsEntry("status", "PUBLISHED");
        // 第一层披露的核心不变量：元数据层绝不带正文。
        assertThat(item).doesNotContainKeys("content", "body");
        assertThat(item.values().stream().map(String::valueOf))
                .noneMatch(value -> value.contains("scripts/analyze.sh 采集信息"));
    }

    @Test
    void searchHidesUnpublishedAndOtherTenantSkills() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        Map<String, Object> draftOutput = searchTool.search(TENANT, searchTool.validateArguments(Map.of()));
        assertThat(draftOutput).containsEntry("resultCount", 0);
        assertThat(String.valueOf(draftOutput.get("notice"))).contains("未发布");

        publishValidSkill();
        Map<String, Object> otherTenant = searchTool.search(OTHER_TENANT, searchTool.validateArguments(Map.of()));
        assertThat(otherTenant).containsEntry("resultCount", 0);
    }

    @Test
    void searchFiltersByKeywordAcrossIdNameAndDescription() {
        publishValidSkill();

        assertThat(searchTool.search(TENANT, searchTool.validateArguments(Map.of("query", "disk-triage"))))
                .containsEntry("resultCount", 1);
        assertThat(searchTool.search(TENANT, searchTool.validateArguments(Map.of("query", "runbook"))))
                .containsEntry("resultCount", 1);
        assertThat(searchTool.search(TENANT, searchTool.validateArguments(Map.of("query", "网络抓包"))))
                .containsEntry("resultCount", 0);
    }

    @Test
    void loadValidatesArguments() {
        assertThatThrownBy(() -> loadTool.validateArguments(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> loadTool.validateArguments(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill_id");
        assertThatThrownBy(() -> loadTool.validateArguments(Map.of("skill_id", SKILL_ID, "extra", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field");
        // skill_id 字符集与 VfsUri 的 resourceId 一致：斜杠/空格/超长一律拒绝。
        for (String bad : List.of("kylin/ops", "kylin ops", "", "x".repeat(129))) {
            assertThatThrownBy(() -> loadTool.validateArguments(Map.of("skill_id", bad)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> loadTool.validateArguments(Map.of("skill_id", SKILL_ID, "path", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadReturnsSkillBodyWithProvenance() {
        publishValidSkill();
        service.promoteTrust(TENANT, SKILL_ID, SkillTrustLevel.ORG_REVIEWED);

        Map<String, Object> output = loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID)));

        assertThat(output).containsEntry("source", "skill://" + SKILL_ID + "/SKILL.md")
                .containsEntry("skillId", SKILL_ID)
                .containsEntry("path", "SKILL.md")
                .containsEntry("trustLevel", "ORG_REVIEWED")
                .containsEntry("untrustedContent", false)
                .containsEntry("encoding", "utf-8")
                .containsEntry("truncated", false)
                .containsEntry("provenance", SkillLoadTool.PROVENANCE);
        assertThat(String.valueOf(output.get("content"))).contains("磁盘告警排查");
    }

    @Test
    void loadMarksUntrustedSkillContentExplicitly() {
        publishValidSkill();

        Map<String, Object> output = loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID)));

        assertThat(output).containsEntry("trustLevel", "UNTRUSTED")
                .containsEntry("untrustedContent", true);
    }

    @Test
    void loadReadsDisclosedResourcesOnly() {
        publishValidSkill();

        Map<String, Object> script = loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID, "path", "scripts/analyze.sh")));
        assertThat(String.valueOf(script.get("content"))).contains("df -h");
        assertThat(script).containsEntry("contentType", "text/x-shellscript");

        // 清单文件不在 scripts/references/assets 三个前缀内：拒绝。
        assertThatThrownBy(() -> loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID, "path", "kylin-skill.yaml"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scripts/references/assets");
    }

    @Test
    void loadRejectsPathTraversal() {
        publishValidSkill();

        for (String traversal : List.of("../../etc/passwd", "scripts/../../../etc/shadow",
                "/etc/passwd", "scripts\\..\\..\\etc")) {
            assertThatThrownBy(() -> loadTool.load(TENANT,
                    loadTool.validateArguments(Map.of("skill_id", SKILL_ID, "path", traversal))))
                    .isInstanceOf(PathSecurity.VfsPathSecurityException.class);
        }
    }

    @Test
    void loadRejectsUnpublishedIsolatedAndUnknownSkills() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        // DRAFT
        assertThatThrownBy(() -> loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not published");

        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);
        service.isolate(TENANT, SKILL_ID);

        // ISOLATED
        assertThatThrownBy(() -> loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not published");

        // 未注册
        assertThatThrownBy(() -> loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", "kylin.ops.unknown"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    void loadIsScopedToTheCallingTenant() {
        publishValidSkill();

        // 同一 skill_id 在别的租户下不可读：工具侧按 (tenant_id, skill_id) 定位，
        // 不走 VFS 那条不带租户的 findPublishedBySkillId。
        assertThatThrownBy(() -> loadTool.load(OTHER_TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    void renderAndValidateOutputRoundTrip() throws Exception {
        publishValidSkill();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> loaded = loadTool.load(TENANT,
                loadTool.validateArguments(Map.of("skill_id", SKILL_ID)));
        Map<String, Object> parsed = loadTool.validateOutput(mapper.writeValueAsString(loaded));
        assertThat(parsed).containsEntry("skillId", SKILL_ID);
        assertThat(loadTool.render(parsed)).containsEntry("type", "skill-load");

        Map<String, Object> found = searchTool.search(TENANT, searchTool.validateArguments(Map.of()));
        Map<String, Object> parsedSearch = searchTool.validateOutput(mapper.writeValueAsString(found));
        assertThat(parsedSearch).containsEntry("resultCount", 1);
        assertThat(searchTool.render(parsedSearch)).containsEntry("type", "skill-search");
    }

    private void publishValidSkill() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);
    }
}
