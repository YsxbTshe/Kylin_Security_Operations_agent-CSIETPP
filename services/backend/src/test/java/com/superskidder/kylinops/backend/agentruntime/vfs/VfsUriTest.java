package com.superskidder.kylinops.backend.agentruntime.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity.VfsPathSecurityException;
import org.junit.jupiter.api.Test;

class VfsUriTest {

    @Test
    void parsesWorkspaceUri() {
        VfsUri uri = VfsUri.parse("workspace://agent-123/logs/app.log");

        assertThat(uri.scheme()).isEqualTo("workspace");
        assertThat(uri.resourceId()).isEqualTo("agent-123");
        assertThat(uri.path()).isEqualTo("logs/app.log");
        assertThat(uri.contentDigest()).isNull();
    }

    @Test
    void parsesArtifactUriWithoutPath() {
        VfsUri uri = VfsUri.parse("artifact://art-456");

        assertThat(uri.scheme()).isEqualTo("artifact");
        assertThat(uri.resourceId()).isEqualTo("art-456");
        assertThat(uri.path()).isEmpty();
    }

    @Test
    void parsesArtifactUriWithArchiveMemberPath() {
        VfsUri uri = VfsUri.parse("artifact://art-456/report/summary.txt");

        assertThat(uri.resourceId()).isEqualTo("art-456");
        assertThat(uri.path()).isEqualTo("report/summary.txt");
    }

    @Test
    void parsesOptionalContentDigestFragment() {
        VfsUri uri = VfsUri.parse("workspace://agent-123/out.bin#sm3:abcdef0123456789");

        assertThat(uri.path()).isEqualTo("out.bin");
        assertThat(uri.contentDigest()).isEqualTo("sm3:abcdef0123456789");
    }

    @Test
    void normalizesSchemeCase() {
        VfsUri uri = VfsUri.parse("WORKSPACE://agent-123/a.txt");

        assertThat(uri.scheme()).isEqualTo("workspace");
    }

    @Test
    void rejectsTraversalInPath() {
        assertThatThrownBy(() -> VfsUri.parse("workspace://agent-123/../../etc/passwd"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void rejectsEmbeddedScheme() {
        assertThatThrownBy(() -> VfsUri.parse("workspace://agent-123/../../../skill://other/path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embed another scheme");
    }

    @Test
    void rejectsNullOrBlankUri() {
        assertThatThrownBy(() -> VfsUri.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VfsUri.parse("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingSeparator() {
        assertThatThrownBy(() -> VfsUri.parse("workspace-agent-123/a.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported vfs uri");
    }

    @Test
    void rejectsInvalidResourceId() {
        assertThatThrownBy(() -> VfsUri.parse("workspace://../a.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource id");
        assertThatThrownBy(() -> VfsUri.parse("workspace://agent 123/a.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VfsUri.parse("workspace://agent/../../x"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void rejectsNullByteInUri() {
        assertThatThrownBy(() -> VfsUri.parse("workspace://agent-123/a\u0000b.txt"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void rejectsInvalidDigestFragment() {
        assertThatThrownBy(() -> VfsUri.parse("workspace://agent-123/a.txt#"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content digest");
        assertThatThrownBy(() -> VfsUri.parse("workspace://agent-123/a.txt#bad digest"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
