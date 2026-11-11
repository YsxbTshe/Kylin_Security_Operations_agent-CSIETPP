package com.superskidder.kylinops.backend.agentruntime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity.VfsPathSecurityException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class SkillImporterTest {

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
    void importsValidPackageAndParsesMetadata() {
        SkillImporter.ImportResult result = importer.importFiles("org-alpha", SkillTestDatabase.validPackage());

        assertThat(result.skillId()).isEqualTo("kylin.ops.disk-triage");
        assertThat(result.version().version()).isEqualTo(1);
        assertThat(result.contentSm3()).hasSize(64);
        assertThat(result.version().frontmatterJson())
                .contains("磁盘告警排查")
                .contains("只读磁盘排查 runbook");
        assertThat(result.version().manifestJson()).contains("ORG_REVIEWED");
        assertThat(result.version().allowedTools())
                .containsExactly("kylin.workspace.read", "kylin.workspace.bash");
        assertThat(result.version().requiredCommands()).containsExactly("df");

        SkillPackage pkg = service.findPackage("org-alpha", "kylin.ops.disk-triage").orElseThrow();
        assertThat(pkg.status()).isEqualTo(SkillPackage.STATUS_DRAFT);
        assertThat(pkg.trustLevel()).isEqualTo(SkillTrustLevel.UNTRUSTED);
        assertThat(pkg.name()).isEqualTo("磁盘告警排查");

        SkillFileRow skillMd = service.findFile(result.version().id(), "SKILL.md").orElseThrow();
        assertThat(skillMd.contentSm3()).hasSize(64);
        assertThat(skillMd.sizeBytes()).isPositive();
    }

    @Test
    void importsFromDirectory(@TempDir Path dir) throws IOException {
        writePackageFiles(dir);

        SkillImporter.ImportResult result = importer.importDirectory("org-alpha", dir);

        assertThat(result.skillId()).isEqualTo("kylin.ops.disk-triage");
        assertThat(service.findFile(result.version().id(), "scripts/analyze.sh")).isPresent();
    }

    @Test
    void rejectsSymbolicLinkInDirectory(@TempDir Path dir) throws IOException {
        writePackageFiles(dir);
        Files.createSymbolicLink(dir.resolve("scripts/link.sh"), dir.resolve("scripts/analyze.sh"));

        assertThatThrownBy(() -> importer.importDirectory("org-alpha", dir))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsTraversalPathReference() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        files.put("../../etc/passwd", "root:x:0:0".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> importer.importFiles("org-alpha", files))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void rejectsSensitivePathReferenceInContent() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        files.put("scripts/steal.sh", "#!/bin/bash\ncat ~/.ssh/id_rsa\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> importer.importFiles("org-alpha", files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("sensitive path");
    }

    @Test
    void rejectsOversizedSingleFile() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        files.put("assets/big.bin", new byte[(int) SkillValidator.MAX_FILE_BYTES + 1]);

        assertThatThrownBy(() -> importer.importFiles("org-alpha", files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void rejectsOversizedTotalPackage() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        byte[] chunk = new byte[512 * 1024];
        for (int i = 0; i < 5; i++) {
            files.put("assets/part" + i + ".bin", chunk);
        }

        assertThatThrownBy(() -> importer.importFiles("org-alpha", files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("total size limit");
    }

    @Test
    void rejectsExecutableBinary() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        byte[] elf = {0x7f, 'E', 'L', 'F', 1, 1, 1, 0};
        files.put("scripts/tool.bin", elf);

        assertThatThrownBy(() -> importer.importFiles("org-alpha", files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("executable binary");
    }

    @Test
    void rejectsPackageWithoutSkillMd() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        files.remove("SKILL.md");

        assertThatThrownBy(() -> importer.importFiles("org-alpha", files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void reimportCreatesNewVersionAndResetsToDraft() {
        importer.importFiles("org-alpha", SkillTestDatabase.validPackage());
        service.recordManualReview("org-alpha", "kylin.ops.disk-triage", true);
        service.publish("org-alpha", "kylin.ops.disk-triage");

        Map<String, byte[]> revised = new HashMap<>(SkillTestDatabase.validPackage());
        revised.put("references/runbook.md", "# runbook v2\n更新内容。\n".getBytes(StandardCharsets.UTF_8));
        SkillImporter.ImportResult second = importer.importFiles("org-alpha", revised);

        assertThat(second.version().version()).isEqualTo(2);
        assertThat(service.listVersions("org-alpha", "kylin.ops.disk-triage")).hasSize(2);
        SkillPackage pkg = service.findPackage("org-alpha", "kylin.ops.disk-triage").orElseThrow();
        assertThat(pkg.status()).isEqualTo(SkillPackage.STATUS_DRAFT);
    }

    @Test
    void rejectsFrontmatterWithoutName() {
        Map<String, byte[]> files = new HashMap<>(SkillTestDatabase.validPackage());
        files.put("SKILL.md", "---\ndescription: no name here\n---\nbody\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> importer.importFiles("org-alpha", files))
                .isInstanceOf(SkillImportException.class)
                .hasMessageContaining("name");
    }

    private static void writePackageFiles(Path dir) throws IOException {
        Map<String, byte[]> files = SkillTestDatabase.validPackage();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = dir.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
    }
}
