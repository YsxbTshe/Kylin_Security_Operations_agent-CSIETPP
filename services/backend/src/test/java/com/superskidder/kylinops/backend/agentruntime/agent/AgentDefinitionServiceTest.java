package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** AgentDefinitionService 的 H2 测试：preset 规格读取、版本选择、租户隔离、缺失处理。 */
class AgentDefinitionServiceTest {

    private JdbcTemplate jdbc;
    private AgentDefinitionService service;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        service = new AgentDefinitionService(jdbc, AgentTestFixtures.MAPPER);
        seedDefinition("def-triage", "disk-triage", "Disk Triage Agent");
        seedVersion("def-triage", "ver-1", 1, "Triage disk usage v1.",
                List.of("kylin.disk.usage"), Map.of("maxToolInvocations", 4), "digest-v1");
        seedVersion("def-triage", "ver-2", 2, "Triage disk usage v2.",
                List.of("kylin.disk.usage", "kylin.service.status"),
                Map.of("maxToolInvocations", 8), "digest-v2");
    }

    @Test
    void definitionsListsTenantDefinitions() {
        List<AgentDefinitionService.DefinitionSummary> summaries =
                service.definitions(AgentTestDatabase.TENANT_ID);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).id()).isEqualTo("def-triage");
        assertThat(summaries.get(0).definitionKey()).isEqualTo("disk-triage");
        assertThat(summaries.get(0).enabled()).isTrue();
    }

    @Test
    void latestVersionPicksHighestVersion() {
        AgentDefinitionService.VersionView view =
                service.latestVersion(AgentTestDatabase.TENANT_ID, "def-triage").orElseThrow();
        assertThat(view.versionId()).isEqualTo("ver-2");
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.spec().instructions()).isEqualTo("Triage disk usage v2.");
        assertThat(view.spec().allowedSkills())
                .containsExactly("kylin.disk.usage", "kylin.service.status");
        assertThat(view.spec().defaultBudget()).containsEntry("maxToolInvocations", 8);
        assertThat(view.spec().contentDigest()).isEqualTo("digest-v2");
    }

    @Test
    void presetSpecFreezesLatestVersionSnapshot() {
        AgentSpecSnapshot spec = service.presetSpec(AgentTestDatabase.TENANT_ID, "def-triage");
        assertThat(spec.instructions()).isEqualTo("Triage disk usage v2.");
        assertThat(spec.allowedSkills()).hasSize(2);
    }

    @Test
    void otherTenantCannotSeeDefinition() {
        // org-beta 已由新基线 V1__baseline.sql 种子，直接作为「其他租户」使用。
        assertThat(service.definitions("org-beta")).isEmpty();
        assertThat(service.findDefinition("org-beta", "def-triage")).isEmpty();
        assertThat(service.latestVersion("org-beta", "def-triage")).isEmpty();
    }

    @Test
    void missingDefinitionHasNoVersion() {
        assertThat(service.latestVersion(AgentTestDatabase.TENANT_ID, "def-missing")).isEmpty();
        assertThatThrownBy(() -> service.presetSpec(AgentTestDatabase.TENANT_ID, "def-missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("def-missing");
    }

    private void seedDefinition(String definitionId, String key, String displayName) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into agent_definition (id, tenant_id, definition_key, display_name, enabled,
                    created_by, created_at, updated_at)
                values (?, ?, ?, ?, true, ?, ?, ?)
                """, definitionId, AgentTestDatabase.TENANT_ID, key, displayName,
                AgentTestDatabase.USER_ID, now, now);
    }

    private void seedVersion(String definitionId, String versionId, int version, String instructions,
                             List<String> skills, Map<String, Object> budget, String digest) {
        jdbc.update("""
                insert into agent_definition_version (id, definition_id, tenant_id, version, instructions,
                    allowed_skills_json, default_budget_json, content_digest, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, versionId, definitionId, AgentTestDatabase.TENANT_ID, version, instructions,
                AgentTestFixtures.json(skills), AgentTestFixtures.json(budget), digest,
                AgentTestDatabase.USER_ID, Timestamp.from(Instant.now()));
    }
}
