package com.superskidder.kylinops.backend.agentruntime.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity.VfsPathSecurityException;
import org.junit.jupiter.api.Test;

class PathSecurityTest {

    @Test
    void sanitizesCleanRelativePath() {
        assertThat(PathSecurity.sanitizePath("a/b/c.txt")).isEqualTo("a/b/c.txt");
        assertThat(PathSecurity.sanitizePath("SKILL.md")).isEqualTo("SKILL.md");
    }

    @Test
    void normalizesRedundantSeparatorsAndDots() {
        assertThat(PathSecurity.sanitizePath("a/./b//c/./d.txt")).isEqualTo("a/b/c/d.txt");
        assertThat(PathSecurity.sanitizePath("./leading/dot.txt")).isEqualTo("leading/dot.txt");
    }

    @Test
    void allowsEmptyPathForArtifactRoot() {
        assertThat(PathSecurity.sanitizePath("")).isEmpty();
    }

    @Test
    void rejectsNullPath() {
        assertThatThrownBy(() -> PathSecurity.sanitizePath(null))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void rejectsNullBytes() {
        assertThatThrownBy(() -> PathSecurity.sanitizePath("a/\u0000b/c.txt"))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageContaining("null byte");
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> PathSecurity.sanitizePath("/etc/passwd"))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageContaining("relative");
    }

    @Test
    void rejectsParentTraversalRawAndNormalized() {
        assertThatThrownBy(() -> PathSecurity.sanitizePath(".."))
                .isInstanceOf(VfsPathSecurityException.class);
        assertThatThrownBy(() -> PathSecurity.sanitizePath("a/../.."))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageContaining("traversal");
        assertThatThrownBy(() -> PathSecurity.sanitizePath("../../etc/passwd"))
                .isInstanceOf(VfsPathSecurityException.class);
        assertThatThrownBy(() -> PathSecurity.sanitizePath("a/b/../../c.txt"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void rejectsBackslashSeparator() {
        assertThatThrownBy(() -> PathSecurity.sanitizePath("a\\b\\c.txt"))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageContaining("backslash");
    }

    @Test
    void rejectsDepthAboveLimit() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < PathSecurity.MAX_DEPTH + 1; i++) {
            if (i > 0) {
                deep.append('/');
            }
            deep.append("seg").append(i);
        }
        assertThatThrownBy(() -> PathSecurity.sanitizePath(deep.toString()))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void allowsDepthAtLimit() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < PathSecurity.MAX_DEPTH; i++) {
            if (i > 0) {
                deep.append('/');
            }
            deep.append("seg").append(i);
        }
        assertThatCode(() -> PathSecurity.sanitizePath(deep.toString())).doesNotThrowAnyException();
    }

    @Test
    void rejectsSegmentLongerThanLimit() {
        String longSegment = "a".repeat(PathSecurity.MAX_SEGMENT_LENGTH + 1);
        assertThatThrownBy(() -> PathSecurity.sanitizePath("dir/" + longSegment))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageContaining("segment too long");
    }

    @Test
    void allowsSegmentAtLimit() {
        String segment = "a".repeat(PathSecurity.MAX_SEGMENT_LENGTH);
        assertThat(PathSecurity.sanitizePath("dir/" + segment)).isEqualTo("dir/" + segment);
    }

    @Test
    void errorMessageDoesNotLeakInputPath() {
        assertThatThrownBy(() -> PathSecurity.sanitizePath("/root/.ssh/id_rsa"))
                .isInstanceOf(VfsPathSecurityException.class)
                .hasMessageNotContaining("/root/.ssh/id_rsa")
                .hasMessageNotContaining("ssh");
    }
}
