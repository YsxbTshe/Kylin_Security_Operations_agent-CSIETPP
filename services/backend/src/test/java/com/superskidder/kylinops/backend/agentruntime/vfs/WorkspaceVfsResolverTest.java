package com.superskidder.kylinops.backend.agentruntime.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity.VfsPathSecurityException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WorkspaceVfsResolverTest {

    private static final VfsResolver.VfsContext CONTEXT =
            new VfsResolver.VfsContext("org-alpha", "agent-123");

    private final WorkspaceVfsResolver resolver = new WorkspaceVfsResolver(
            (agentInstanceId, relativePath) -> new ByteArrayInputStream(
                    ("payload:" + agentInstanceId + ":" + relativePath).getBytes(StandardCharsets.UTF_8)));

    @Test
    void parsesWorkspaceUriAndSanitizesPath() {
        VfsUri uri = resolver.parse("workspace://agent-123/./logs//app.log");

        assertThat(uri.scheme()).isEqualTo("workspace");
        assertThat(uri.resourceId()).isEqualTo("agent-123");
        assertThat(uri.path()).isEqualTo("logs/app.log");
    }

    @Test
    void readOwnWorkspaceFileReturnsStreamAndMetadata() throws IOException {
        VfsUri uri = resolver.parse("workspace://agent-123/out/report.txt");

        try (VfsResolver.VfsResource resource = resolver.read(uri, CONTEXT)) {
            assertThat(new String(resource.content().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("payload:agent-123:out/report.txt");
            assertThat(resource.metadata())
                    .containsEntry("agentInstanceId", "agent-123")
                    .containsEntry("path", "out/report.txt");
        }
    }

    @Test
    void rejectsTraversalAttempt() {
        assertThatThrownBy(() -> resolver.parse("workspace://agent-123/../../etc/passwd"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void rejectsCrossAgentInstanceAccess() {
        VfsUri uri = resolver.parse("workspace://agent-123/out/report.txt");
        VfsResolver.VfsContext otherAgent = new VfsResolver.VfsContext("org-alpha", "agent-999");

        assertThatThrownBy(() -> resolver.read(uri, otherAgent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access denied");
    }

    @Test
    void requiresNonEmptyPath() {
        assertThatThrownBy(() -> resolver.parse("workspace://agent-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative path");
        assertThatThrownBy(() -> resolver.parse("workspace://agent-123/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsForeignScheme() {
        assertThatThrownBy(() -> resolver.parse("skill://agent-123/a.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a workspace vfs uri");
    }

    @Test
    void stubReaderReportsRunnerNotConnected() {
        WorkspaceVfsResolver stub = new WorkspaceVfsResolver();
        VfsUri uri = stub.parse("workspace://agent-123/a.txt");

        assertThatThrownBy(() -> stub.read(uri, CONTEXT))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("workspace runner is not connected");
    }
}
