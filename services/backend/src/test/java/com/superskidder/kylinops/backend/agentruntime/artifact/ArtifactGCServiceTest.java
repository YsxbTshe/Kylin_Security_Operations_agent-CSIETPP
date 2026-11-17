package com.superskidder.kylinops.backend.agentruntime.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ArtifactGCServiceTest {

    @TempDir
    Path storageRoot;

    private JdbcTemplate jdbcTemplate;
    private ArtifactRepository repository;
    private FileSystemArtifactStorage storage;
    private ArtifactService artifacts;
    private ArtifactGCService gc;

    @BeforeEach
    void setUp() {
        jdbcTemplate = ArtifactTestDatabase.create();
        repository = new ArtifactRepository(jdbcTemplate);
        storage = new FileSystemArtifactStorage(storageRoot.toString());
        artifacts = new ArtifactService(repository, storage);
        gc = new ArtifactGCService(repository, storage, true, 30, 60);
    }

    @Test
    void orphanTempFileIsCleaned() throws IOException {
        Path tenantDir = storageRoot.resolve("org-ghost");
        Files.createDirectories(tenantDir);
        Path orphan = tenantDir.resolve("art-orphan.tmp");
        Files.writeString(orphan, "nobody owns me");

        ArtifactGCService.CleanupResult result = gc.cleanup();

        assertThat(result.orphanTempFiles()).isEqualTo(1);
        assertThat(orphan).doesNotExist();
    }

    @Test
    void tempFileOfActiveWritingIsKept() throws IOException {
        ArtifactService.WriteSession session = artifacts.beginWrite("org-alpha", null, "live.bin", null);
        session.output().write("in flight".getBytes(StandardCharsets.UTF_8));
        session.output().flush();
        assertThat(storage.listTempFiles()).hasSize(1);

        ArtifactGCService.CleanupResult result = gc.cleanup();

        assertThat(result.orphanTempFiles()).isZero();
        assertThat(storage.listTempFiles()).hasSize(1);
        artifacts.failWrite(session, "test cleanup");
    }

    @Test
    void publishedArtifactPastRetentionIsDeleted() throws IOException {
        ArtifactService.WriteSession session = artifacts.beginWrite("org-alpha", null, "old.bin", null);
        session.output().write("aged content".getBytes(StandardCharsets.UTF_8));
        ArtifactRecord published = artifacts.finishWrite(session, null);
        jdbcTemplate.update("update agent_artifact set published_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)), published.id());

        ArtifactGCService.CleanupResult result = gc.cleanup();

        assertThat(result.expiredArtifacts()).isEqualTo(1);
        assertThat(repository.findById("org-alpha", published.id())).isEmpty();
        assertThat(storage.publishedPath("org-alpha", published.id())).doesNotExist();
    }

    @Test
    void staleWritingIsMarkedFailedAndTempRemoved() throws IOException {
        ArtifactService.WriteSession session = artifacts.beginWrite("org-alpha", null, "stuck.bin", null);
        session.output().write("stuck".getBytes(StandardCharsets.UTF_8));
        session.output().flush();
        jdbcTemplate.update("update agent_artifact set created_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS)), session.draft().id());

        ArtifactGCService.CleanupResult result = gc.cleanup();

        assertThat(result.staleWrites()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from agent_artifact where id = ?",
                String.class, session.draft().id())).isEqualTo(ArtifactService.STATUS_FAILED);
        assertThat(storage.listTempFiles()).isEmpty();
    }
}
