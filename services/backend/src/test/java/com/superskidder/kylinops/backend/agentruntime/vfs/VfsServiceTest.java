package com.superskidder.kylinops.backend.agentruntime.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactRecord;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactRepository;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactService;
import com.superskidder.kylinops.backend.agentruntime.artifact.FileSystemArtifactStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VfsServiceTest {

    private static final VfsResolver.VfsContext CONTEXT =
            new VfsResolver.VfsContext("org-alpha", "agent-123");

    @TempDir
    Path storageRoot;

    private VfsService service;
    private ArtifactService artifacts;

    @BeforeEach
    void setUp() {
        WorkspaceVfsResolver workspace = new WorkspaceVfsResolver(
                (agentInstanceId, relativePath) -> new ByteArrayInputStream(
                        "workspace-bytes".getBytes(StandardCharsets.UTF_8)));
        SkillVfsResolver skill = new SkillVfsResolver(
                (skillId, relativePath) -> Optional.of(new SkillVfsResolver.SkillFile(
                        "# skill".getBytes(StandardCharsets.UTF_8), "text/markdown")));
        ArtifactRepository repository = new ArtifactRepository(VfsTestDatabase.create());
        FileSystemArtifactStorage storage = new FileSystemArtifactStorage(storageRoot.toString());
        artifacts = new ArtifactService(repository, storage);
        service = new VfsService(List.of(workspace, skill, new ArtifactVfsResolver(repository, storage)));
    }

    @Test
    void dispatchesParseByScheme() {
        assertThat(service.parse("workspace://agent-123/out.txt").scheme()).isEqualTo("workspace");
        assertThat(service.parse("skill://my-skill/SKILL.md").scheme()).isEqualTo("skill");
        assertThat(service.parse("artifact://art-456").scheme()).isEqualTo("artifact");
    }

    @Test
    void readRoutesWorkspaceScheme() throws IOException {
        try (VfsResolver.VfsResource resource = service.read("workspace://agent-123/out.txt", CONTEXT)) {
            assertThat(new String(resource.content().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("workspace-bytes");
        }
    }

    @Test
    void readRoutesSkillScheme() throws IOException {
        try (VfsResolver.VfsResource resource = service.read("skill://my-skill/SKILL.md", CONTEXT)) {
            assertThat(resource.metadata()).containsEntry("skillId", "my-skill");
            assertThat(new String(resource.content().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("# skill");
        }
    }

    @Test
    void readRoutesArtifactSchemeAndReturnsMetadata() throws IOException {
        ArtifactService.WriteSession session = artifacts.beginWrite("org-alpha", "agent-123", "r.txt", "text/plain");
        session.output().write("artifact payload".getBytes(StandardCharsets.UTF_8));
        ArtifactRecord published = artifacts.finishWrite(session, null);

        try (VfsResolver.VfsResource resource = service.read("artifact://" + published.id(), CONTEXT)) {
            assertThat(resource.metadata())
                    .containsEntry("id", published.id())
                    .containsEntry("status", ArtifactService.STATUS_PUBLISHED);
            assertThat(new String(resource.content().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("artifact payload");
        }
    }

    @Test
    void unknownSchemeIsRejected() {
        assertThatThrownBy(() -> service.parse("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown vfs scheme");
        assertThatThrownBy(() -> service.parse("http://example.com/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown vfs scheme");
    }

    @Test
    void embeddedSchemeInjectionIsRejected() {
        assertThatThrownBy(() -> service.parse("workspace://agent-123/../../../skill://other/path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embed another scheme");
        assertThatThrownBy(() -> service.read("artifact://art-1/x/artifact://art-2", CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embed another scheme");
    }

    @Test
    void malformedUriIsRejected() {
        assertThatThrownBy(() -> service.parse("workspace-agent-123/out.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported vfs uri");
        assertThatThrownBy(() -> service.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void traversalIsRejectedAtServiceEntry() {
        assertThatThrownBy(() -> service.parse("workspace://agent-123/../../etc/passwd"))
                .isInstanceOf(PathSecurity.VfsPathSecurityException.class);
    }

    @Test
    void duplicateSchemeRegistrationIsRejected() {
        assertThatThrownBy(() -> new VfsService(List.of(new WorkspaceVfsResolver(), new WorkspaceVfsResolver())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate vfs resolver");
    }

    @Test
    void readRequiresContext() {
        assertThatThrownBy(() -> service.read("workspace://agent-123/out.txt", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context");
    }
}
