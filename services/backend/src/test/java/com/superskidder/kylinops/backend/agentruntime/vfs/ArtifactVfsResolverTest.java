package com.superskidder.kylinops.backend.agentruntime.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactRecord;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactRepository;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactService;
import com.superskidder.kylinops.backend.agentruntime.artifact.FileSystemArtifactStorage;
import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity.VfsPathSecurityException;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactVfsResolverTest {

    private static final VfsResolver.VfsContext CONTEXT =
            new VfsResolver.VfsContext("org-alpha", "agent-123");

    @TempDir
    Path storageRoot;

    private ArtifactService artifacts;
    private ArtifactVfsResolver resolver;

    @BeforeEach
    void setUp() {
        ArtifactRepository repository = new ArtifactRepository(VfsTestDatabase.create());
        FileSystemArtifactStorage storage = new FileSystemArtifactStorage(storageRoot.toString());
        artifacts = new ArtifactService(repository, storage);
        resolver = new ArtifactVfsResolver(repository, storage);
    }

    @Test
    void publishedArtifactReadableWithMetadata() throws IOException {
        byte[] content = "artifact payload".getBytes(StandardCharsets.UTF_8);
        ArtifactRecord published = publish("report.txt", content);

        VfsUri uri = resolver.parse("artifact://" + published.id());
        try (VfsResolver.VfsResource resource = resolver.read(uri, CONTEXT)) {
            assertThat(resource.content().readAllBytes()).isEqualTo(content);
            assertThat(resource.metadata())
                    .containsEntry("id", published.id())
                    .containsEntry("name", "report.txt")
                    .containsEntry("contentType", "text/plain")
                    .containsEntry("sizeBytes", (long) content.length)
                    .containsEntry("sm3Hex", Sm3.hex(content))
                    .containsEntry("status", ArtifactService.STATUS_PUBLISHED);
            assertThat(resource.metadata().get("publishedAt")).isNotNull();
        }
    }

    @Test
    void writingArtifactIsRejected() {
        ArtifactService.WriteSession draft = artifacts.beginWrite("org-alpha", "agent-123", "draft.bin", null);

        assertThatThrownBy(() -> resolver.read(resolver.parse("artifact://" + draft.draft().id()), CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found or not published");
        artifacts.failWrite(draft, "test cleanup");
    }

    @Test
    void failedArtifactIsRejected() throws IOException {
        ArtifactService.WriteSession session = artifacts.beginWrite("org-alpha", null, "broken.bin", null);
        session.output().write("partial".getBytes(StandardCharsets.UTF_8));
        artifacts.failWrite(session, "forced failure");

        assertThatThrownBy(() -> resolver.read(resolver.parse("artifact://" + session.draft().id()), CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found or not published");
    }

    @Test
    void unknownArtifactIsRejected() {
        assertThatThrownBy(() -> resolver.read(resolver.parse("artifact://art-does-not-exist"), CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found or not published");
    }

    @Test
    void crossTenantAccessIsRejected() throws IOException {
        ArtifactRecord published = publish("shared.bin", "tenant scoped".getBytes(StandardCharsets.UTF_8));
        VfsResolver.VfsContext otherTenant = new VfsResolver.VfsContext("org-beta", "agent-123");

        assertThatThrownBy(() -> resolver.read(resolver.parse("artifact://" + published.id()), otherTenant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found or not published");
    }

    @Test
    void archiveMemberPathIsSanitizedButReadIsPlaceholder() throws IOException {
        ArtifactRecord published = publish("bundle.bin", "bytes".getBytes(StandardCharsets.UTF_8));

        VfsUri uri = resolver.parse("artifact://" + published.id() + "/report/summary.txt");
        assertThat(uri.path()).isEqualTo("report/summary.txt");
        assertThatThrownBy(() -> resolver.read(uri, CONTEXT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("archive member read not supported");
    }

    @Test
    void archiveMemberTraversalIsRejected() throws IOException {
        ArtifactRecord published = publish("bundle.bin", "bytes".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> resolver.parse("artifact://" + published.id() + "/../../evil"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    private ArtifactRecord publish(String name, byte[] content) throws IOException {
        ArtifactService.WriteSession session = artifacts.beginWrite("org-alpha", "agent-123", name, "text/plain");
        session.output().write(content);
        return artifacts.finishWrite(session, null);
    }
}
