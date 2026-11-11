package com.superskidder.kylinops.backend.agentruntime.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ArtifactDownloadServiceTest {

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final String KEY = "artifact-download-signing-key";

    @TempDir
    Path storageRoot;

    private ArtifactRepository repository;
    private ArtifactService artifacts;
    private ArtifactDownloadService downloads;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = ArtifactTestDatabase.create();
        repository = new ArtifactRepository(jdbcTemplate);
        artifacts = new ArtifactService(repository, new FileSystemArtifactStorage(storageRoot.toString()));
        downloads = new ArtifactDownloadService(KEY, 900, Clock.fixed(BASE, ZoneOffset.UTC), repository);
    }

    @Test
    void issuedSignatureVerifiesWithinTtl() {
        ArtifactDownloadService.SignedUrl signed = downloads.issue("org-alpha", "art-1");

        assertThat(signed.expiresAtEpochSecond()).isEqualTo(BASE.getEpochSecond() + 900);
        downloads.verify("org-alpha", "art-1", signed.expiresAtEpochSecond(), signed.signature());
        assertThat(signed.path("/api/v2/artifacts"))
                .contains("/api/v2/artifacts/art-1/download")
                .contains("signature=" + signed.signature());
    }

    @Test
    void tamperedSignatureIsRejected() {
        ArtifactDownloadService.SignedUrl signed = downloads.issue("org-alpha", "art-1");

        assertThatThrownBy(() -> downloads.verify("org-alpha", "art-1",
                signed.expiresAtEpochSecond(), signed.signature().replace('a', 'b')))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void signatureIsBoundToTenantAndArtifact() {
        ArtifactDownloadService.SignedUrl signed = downloads.issue("org-alpha", "art-1");

        assertThatThrownBy(() -> downloads.verify("org-alpha", "art-2",
                signed.expiresAtEpochSecond(), signed.signature()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> downloads.verify("org-beta", "art-1",
                signed.expiresAtEpochSecond(), signed.signature()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiredLinkIsRejected() {
        ArtifactDownloadService.SignedUrl signed = downloads.issue("org-alpha", "art-1");
        ArtifactDownloadService later = new ArtifactDownloadService(KEY, 900,
                Clock.fixed(BASE.plusSeconds(901), ZoneOffset.UTC), repository);

        assertThatThrownBy(() -> later.verify("org-alpha", "art-1",
                signed.expiresAtEpochSecond(), signed.signature()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void unconfiguredKeyRefusesIssueAndVerify() {
        ArtifactDownloadService unconfigured = new ArtifactDownloadService("", 900,
                Clock.fixed(BASE, ZoneOffset.UTC), repository);

        assertThatThrownBy(() -> unconfigured.issue("org-alpha", "art-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> unconfigured.verify("org-alpha", "art-1", BASE.getEpochSecond() + 900, "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveOnlyReleasesPublishedArtifacts() throws IOException {
        ArtifactService.WriteSession session = artifacts.beginWrite("org-alpha", null, "out.bin", null);
        session.output().write("payload".getBytes(StandardCharsets.UTF_8));
        ArtifactRecord published = artifacts.finishWrite(session, null);

        ArtifactDownloadService.SignedUrl signed = downloads.issue("org-alpha", published.id());
        ArtifactRecord resolved = downloads.resolve("org-alpha", published.id(),
                signed.expiresAtEpochSecond(), signed.signature());
        assertThat(resolved.id()).isEqualTo(published.id());
        assertThat(resolved.status()).isEqualTo(ArtifactService.STATUS_PUBLISHED);

        // 仅有 WRITING 草稿的记录不放行。
        ArtifactService.WriteSession draft = artifacts.beginWrite("org-alpha", null, "draft.bin", null);
        ArtifactDownloadService.SignedUrl draftSigned = downloads.issue("org-alpha", draft.draft().id());
        assertThatThrownBy(() -> downloads.resolve("org-alpha", draft.draft().id(),
                draftSigned.expiresAtEpochSecond(), draftSigned.signature()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found or not published");
        artifacts.failWrite(draft, "test cleanup");
    }
}
