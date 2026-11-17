package com.superskidder.kylinops.backend.agentruntime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.vfs.SkillVfsResolver;
import com.superskidder.kylinops.backend.agentruntime.vfs.VfsResolver;
import com.superskidder.kylinops.backend.agentruntime.vfs.VfsUri;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** SkillFileProvider 实现与 skill:// VFS 的接线验证。 */
class SkillFileStoreTest {

    private static final String TENANT = "org-alpha";
    private static final String SKILL_ID = "kylin.ops.disk-triage";
    private static final VfsResolver.VfsContext CONTEXT = new VfsResolver.VfsContext("org-alpha", "agent-1");

    private SkillService service;
    private SkillImporter importer;
    private SkillVfsResolver resolver;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = SkillTestDatabase.create();
        service = new SkillService(jdbcTemplate);
        importer = new SkillImporter(service);
        resolver = new SkillVfsResolver(new SkillFileStore(service));
    }

    @Test
    void publishedSkillIsReadableThroughVfs() throws IOException {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);

        VfsUri uri = resolver.parse("skill://" + SKILL_ID + "/SKILL.md");
        try (VfsResolver.VfsResource resource = resolver.read(uri, CONTEXT)) {
            String body = new String(resource.content().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("磁盘告警排查");
            assertThat(resource.metadata())
                    .containsEntry("skillId", SKILL_ID)
                    .containsEntry("path", "SKILL.md")
                    .containsEntry("contentType", "text/markdown");
        }
    }

    @Test
    void draftSkillIsRejectedByVfs() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        VfsUri uri = resolver.parse("skill://" + SKILL_ID + "/SKILL.md");

        assertThatThrownBy(() -> resolver.read(uri, CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill not registered");
    }

    @Test
    void isolatedSkillIsRejectedByVfs() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);
        service.isolate(TENANT, SKILL_ID);
        VfsUri uri = resolver.parse("skill://" + SKILL_ID + "/SKILL.md");

        assertThatThrownBy(() -> resolver.read(uri, CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill not registered");
    }

    @Test
    void missingFileInPublishedSkillIsRejected() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        service.recordManualReview(TENANT, SKILL_ID, true);
        service.publish(TENANT, SKILL_ID);
        VfsUri uri = resolver.parse("skill://" + SKILL_ID + "/assets/absent.txt");

        assertThatThrownBy(() -> resolver.read(uri, CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill not registered");
    }
}
