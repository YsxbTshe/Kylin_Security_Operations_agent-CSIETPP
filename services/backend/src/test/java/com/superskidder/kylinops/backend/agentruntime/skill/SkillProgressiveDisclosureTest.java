package com.superskidder.kylinops.backend.agentruntime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity.VfsPathSecurityException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SkillProgressiveDisclosureTest {

    private static final String TENANT = "org-alpha";
    private static final String SKILL_ID = "kylin.ops.disk-triage";

    private SkillService service;
    private SkillImporter importer;
    private SkillProgressiveDisclosure disclosure;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SkillTestDatabase.create();
        service = new SkillService(jdbcTemplate);
        importer = new SkillImporter(service);
        disclosure = new SkillProgressiveDisclosure(service);
    }

    @Test
    void listingExposesOnlyNameAndDescription() {
        publishValidSkill();

        List<SkillProgressiveDisclosure.SkillSummary> summaries = disclosure.listSkills(TENANT);

        assertThat(summaries).hasSize(1);
        SkillProgressiveDisclosure.SkillSummary summary = summaries.get(0);
        assertThat(summary.skillId()).isEqualTo(SKILL_ID);
        assertThat(summary.name()).isEqualTo("磁盘告警排查");
        assertThat(summary.description()).isEqualTo("只读磁盘排查 runbook");
        // 第一层披露不携带正文/资源：summary 只有 skillId/name/description 三个字段。
        assertThat(summary).hasOnlyFields("skillId", "name", "description");
    }

    @Test
    void listingHidesUnpublishedSkills() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThat(disclosure.listSkills(TENANT)).isEmpty();
    }

    @Test
    void loadSkillBodyReturnsFullMarkdown() {
        publishValidSkill();

        String body = disclosure.loadSkillBody(TENANT, SKILL_ID);

        assertThat(body)
                .startsWith("---")
                .contains("磁盘告警排查")
                .contains("scripts/analyze.sh");
    }

    @Test
    void loadBodyRejectsUnpublishedSkill() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThatThrownBy(() -> disclosure.loadSkillBody(TENANT, SKILL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not published");
    }

    @Test
    void loadResourceServesScriptsReferencesAndAssets() {
        publishValidSkill();

        SkillFileRow script = disclosure.loadResource(TENANT, SKILL_ID, "scripts/analyze.sh");
        assertThat(new String(script.content(), StandardCharsets.UTF_8)).contains("df -h");
        assertThat(script.contentType()).isEqualTo("text/x-shellscript");

        SkillFileRow runbook = disclosure.loadResource(TENANT, SKILL_ID, "references/runbook.md");
        assertThat(runbook.contentType()).isEqualTo("text/markdown");
    }

    @Test
    void loadResourceRejectsTraversal() {
        publishValidSkill();

        assertThatThrownBy(() -> disclosure.loadResource(TENANT, SKILL_ID, "../../etc/passwd"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void loadResourceRejectsPathsOutsideDisclosurePrefixes() {
        publishValidSkill();

        assertThatThrownBy(() -> disclosure.loadResource(TENANT, SKILL_ID, "kylin-skill.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scripts/references/assets");
    }

    private void publishValidSkill() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);
    }
}
