package com.superskidder.kylinops.backend.agentruntime.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.crypto.Sm3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ArtifactServiceTest {

    @TempDir
    Path storageRoot;

    private JdbcTemplate jdbcTemplate;
    private ArtifactRepository repository;
    private FileSystemArtifactStorage storage;
    private ArtifactService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = ArtifactTestDatabase.create();
        repository = new ArtifactRepository(jdbcTemplate);
        storage = new FileSystemArtifactStorage(storageRoot.toString());
        service = new ArtifactService(repository, storage);
    }

    @Test
    void streamingWritePublishesWithSizeAndSm3() throws IOException {
        byte[] content = "kylin artifact payload".getBytes(StandardCharsets.UTF_8);

        ArtifactService.WriteSession session = service.beginWrite("org-alpha", "agent-1", "report.txt", "text/plain");
        session.output().write(content);
        ArtifactRecord published = service.finishWrite(session, null);

        assertThat(published.status()).isEqualTo(ArtifactService.STATUS_PUBLISHED);
        assertThat(published.sizeBytes()).isEqualTo(content.length);
        assertThat(published.sm3Hex()).isEqualTo(Sm3.hex(content));
        assertThat(published.storagePath()).isEqualTo("org-alpha/" + published.id());
        assertThat(published.publishedAt()).isNotNull();
        assertThat(Files.readAllBytes(storage.publishedPath("org-alpha", published.id()))).isEqualTo(content);
        assertThat(storage.listTempFiles()).isEmpty();
    }

    @Test
    void duplicateContentReusesExistingArtifact() throws IOException {
        byte[] content = "deduplicate me".getBytes(StandardCharsets.UTF_8);

        ArtifactService.WriteSession first = service.beginWrite("org-alpha", null, "first.log", null);
        first.output().write(content);
        ArtifactRecord original = service.finishWrite(first, null);

        ArtifactService.WriteSession second = service.beginWrite("org-alpha", null, "second.log", null);
        second.output().write(content);
        ArtifactRecord reused = service.finishWrite(second, null);

        assertThat(reused.id()).isEqualTo(original.id());
        assertThat(reused.sm3Hex()).isEqualTo(original.sm3Hex());
        // 第二次上传的 WRITING 草稿被移除，只保留已发布记录。
        assertThat(jdbcTemplate.queryForObject("select count(1) from agent_artifact", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(1) from agent_artifact where status <> 'PUBLISHED'", Integer.class)).isZero();
        assertThat(storage.listTempFiles()).isEmpty();
        assertThat(Files.exists(storage.publishedPath("org-alpha", original.id()))).isTrue();
    }

    @Test
    void sameDigestAcrossTenantsIsNotDeduplicated() throws IOException {
        byte[] content = "shared bytes".getBytes(StandardCharsets.UTF_8);

        ArtifactService.WriteSession first = service.beginWrite("org-alpha", null, "a.bin", null);
        first.output().write(content);
        ArtifactRecord alpha = service.finishWrite(first, null);

        ArtifactService.WriteSession second = service.beginWrite("org-beta", null, "b.bin", null);
        second.output().write(content);
        ArtifactRecord beta = service.finishWrite(second, null);

        assertThat(beta.id()).isNotEqualTo(alpha.id());
        assertThat(beta.tenantId()).isEqualTo("org-beta");
    }

    @Test
    void tamperedContentWithWrongExpectedDigestIsRejected() throws IOException {
        ArtifactService.WriteSession session = service.beginWrite("org-alpha", null, "payload.bin", null);
        writeQuietly(session, "content delivered".getBytes(StandardCharsets.UTF_8));

        String forgedDigest = Sm3.hex("content delivered BUT TAMPERED".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.finishWrite(session, forgedDigest))
                .isInstanceOf(ArtifactDigestMismatchException.class);

        String artifactId = session.draft().id();
        assertThat(jdbcTemplate.queryForObject("select status from agent_artifact where id = ?",
                String.class, artifactId)).isEqualTo(ArtifactService.STATUS_FAILED);
        assertThat(Files.exists(storage.publishedPath("org-alpha", artifactId))).isFalse();
        assertThat(storage.listTempFiles()).isEmpty();
    }

    @Test
    void matchingExpectedDigestPublishes() throws IOException {
        byte[] content = "verified payload".getBytes(StandardCharsets.UTF_8);

        ArtifactService.WriteSession session = service.beginWrite("org-alpha", null, "verified.bin", null);
        session.output().write(content);
        ArtifactRecord published = service.finishWrite(session, Sm3.hex(content));

        assertThat(published.status()).isEqualTo(ArtifactService.STATUS_PUBLISHED);
        assertThat(published.sm3Hex()).isEqualTo(Sm3.hex(content));
    }

    @Test
    void failWriteMarksFailedAndCleansTempFile() throws IOException {
        ArtifactService.WriteSession session = service.beginWrite("org-alpha", null, "aborted.bin", null);
        writeQuietly(session, "partial".getBytes(StandardCharsets.UTF_8));

        service.failWrite(session, "stream aborted");

        assertThat(jdbcTemplate.queryForObject("select status from agent_artifact where id = ?",
                String.class, session.draft().id())).isEqualTo(ArtifactService.STATUS_FAILED);
        assertThat(storage.listTempFiles()).isEmpty();
        assertThat(service.findPublished("org-alpha", session.draft().id())).isEmpty();
    }

    private void writeQuietly(ArtifactService.WriteSession session, byte[] bytes) {
        try {
            session.output().write(bytes);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
