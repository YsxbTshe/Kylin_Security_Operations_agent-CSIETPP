package com.superskidder.kylinops.backend.agentruntime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SkillServiceTest {

    private static final String TENANT = "org-alpha";
    private static final String SKILL_ID = "kylin.ops.disk-triage";

    private JdbcTemplate jdbcTemplate;
    private SkillService service;
    private SkillImporter importer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = SkillTestDatabase.create();
        service = new SkillService(jdbcTemplate);
        importer = new SkillImporter(service);
    }

    @Test
    void statusFlowDraftToPublished() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThat(service.findPackage(TENANT, SKILL_ID).orElseThrow().status())
                .isEqualTo(SkillPackage.STATUS_DRAFT);

        service.recordManualReview(TENANT, SKILL_ID, true);
        assertThat(service.findPackage(TENANT, SKILL_ID).orElseThrow().status())
                .isEqualTo(SkillPackage.STATUS_REVIEWED);

        service.publish(TENANT, SKILL_ID);
        assertThat(service.findPackage(TENANT, SKILL_ID).orElseThrow().status())
                .isEqualTo(SkillPackage.STATUS_PUBLISHED);
        assertThat(service.listPublished(TENANT)).hasSize(1);
    }

    @Test
    void reviewRequiresPassedSandboxTest() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThatThrownBy(() -> service.recordManualReview(TENANT, SKILL_ID, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox test");
    }

    @Test
    void publishWithoutReviewIsRejected() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThatThrownBy(() -> service.publish(TENANT, SKILL_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transition rejected");
    }

    @Test
    void isolateRemovesPackageFromPublishedViews() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);

        service.isolate(TENANT, SKILL_ID);

        assertThat(service.findPackage(TENANT, SKILL_ID).orElseThrow().status())
                .isEqualTo(SkillPackage.STATUS_ISOLATED);
        assertThat(service.listPublished(TENANT)).isEmpty();
        assertThat(service.findPublishedBySkillId(SKILL_ID)).isEmpty();
    }

    @Test
    void untrustedSkillCannotUseNodeToolsEvenWhenPublished() {
        importAndPublish(SkillTestDatabase.validPackage());

        assertThatThrownBy(() -> service.authorizeTool(TENANT, SKILL_ID, "kylin.workspace.read", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox");
    }

    @Test
    void orgReviewedSkillRejectsWriteTools() {
        importAndPublish(SkillTestDatabase.validPackage());
        service.promoteTrust(TENANT, SKILL_ID, SkillTrustLevel.ORG_REVIEWED);

        assertThatThrownBy(() -> service.authorizeTool(TENANT, SKILL_ID, "kylin.workspace.write", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("write tools");
    }

    @Test
    void orgReviewedSkillAllowsAuthorizedReadOnlyToolsOnly() {
        importAndPublish(SkillTestDatabase.validPackage());
        service.promoteTrust(TENANT, SKILL_ID, SkillTrustLevel.ORG_REVIEWED);

        service.authorizeTool(TENANT, SKILL_ID, "kylin.workspace.read", false);

        assertThatThrownBy(() -> service.authorizeTool(TENANT, SKILL_ID, "kylin.node.service.stop", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed_tools");
    }

    @Test
    void platformSignedSkillMayUseAuthorizedWriteTools() {
        importAndPublish(writeToolPackage());
        SkillCatalogSigner signer = new SkillCatalogSigner(service, "unit-test-signing-key");
        signer.signLatestVersion(TENANT, SKILL_ID);

        service.authorizeTool(TENANT, SKILL_ID, "kylin.workspace.write", true);

        assertThat(service.findPackage(TENANT, SKILL_ID).orElseThrow().trustLevel())
                .isEqualTo(SkillTrustLevel.PLATFORM_SIGNED);
    }

    @Test
    void unpublishedSkillCannotAuthorizeTools() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThatThrownBy(() -> service.authorizeTool(TENANT, SKILL_ID, "kylin.workspace.read", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not published");
    }

    @Test
    void trustLevelCannotBeLoweredOrForged() {
        importAndPublish(SkillTestDatabase.validPackage());
        service.promoteTrust(TENANT, SKILL_ID, SkillTrustLevel.PRIVILEGED);

        assertThatThrownBy(() -> service.promoteTrust(TENANT, SKILL_ID, SkillTrustLevel.ORG_REVIEWED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only be raised");
        assertThatThrownBy(() -> service.promoteTrust(TENANT, SKILL_ID, SkillTrustLevel.PLATFORM_SIGNED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signing flow");
    }

    private void importAndPublish(Map<String, byte[]> files) {
        importer.importFiles(TENANT, files);
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);
    }

    private static Map<String, byte[]> writeToolPackage() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        files.put("kylin-skill.yaml", """
                skill_id: kylin.ops.disk-triage
                trust_level: PRIVILEGED
                allowed_tools:
                  - kylin.workspace.write
                required_commands:
                  - df
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return files;
    }
}
