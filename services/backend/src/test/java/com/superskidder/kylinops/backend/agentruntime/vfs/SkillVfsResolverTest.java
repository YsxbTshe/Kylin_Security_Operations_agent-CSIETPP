package com.superskidder.kylinops.backend.agentruntime.vfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity.VfsPathSecurityException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SkillVfsResolverTest {

    private static final VfsResolver.VfsContext CONTEXT =
            new VfsResolver.VfsContext("org-alpha", "agent-123");

    @Test
    void parsesSkillUri() {
        SkillVfsResolver resolver = new SkillVfsResolver();

        VfsUri uri = resolver.parse("skill://my-skill/SKILL.md");

        assertThat(uri.scheme()).isEqualTo("skill");
        assertThat(uri.resourceId()).isEqualTo("my-skill");
        assertThat(uri.path()).isEqualTo("SKILL.md");
    }

    @Test
    void unregisteredSkillYieldsStructuredError() {
        SkillVfsResolver resolver = new SkillVfsResolver();
        VfsUri uri = resolver.parse("skill://ghost-skill/SKILL.md");

        assertThatThrownBy(() -> resolver.read(uri, CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill not registered")
                .hasMessageContaining("ghost-skill");
    }

    @Test
    void registeredSkillReadsContent() throws IOException {
        byte[] body = "# kylin skill".getBytes(StandardCharsets.UTF_8);
        SkillVfsResolver resolver = new SkillVfsResolver(
                (skillId, relativePath) -> "my-skill".equals(skillId) && "SKILL.md".equals(relativePath)
                        ? Optional.of(new SkillVfsResolver.SkillFile(body, "text/markdown"))
                        : Optional.empty());
        VfsUri uri = resolver.parse("skill://my-skill/SKILL.md");

        try (VfsResolver.VfsResource resource = resolver.read(uri, CONTEXT)) {
            assertThat(resource.content().readAllBytes()).isEqualTo(body);
            assertThat(resource.metadata())
                    .containsEntry("skillId", "my-skill")
                    .containsEntry("contentType", "text/markdown")
                    .containsEntry("sizeBytes", body.length);
        }
    }

    @Test
    void rejectsTraversalAttempt() {
        SkillVfsResolver resolver = new SkillVfsResolver();

        assertThatThrownBy(() -> resolver.parse("skill://my-skill/../../etc/passwd"))
                .isInstanceOf(VfsPathSecurityException.class);
    }

    @Test
    void requiresNonEmptyPath() {
        SkillVfsResolver resolver = new SkillVfsResolver();

        assertThatThrownBy(() -> resolver.parse("skill://my-skill"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative path");
    }

    @Test
    void rejectsForeignScheme() {
        SkillVfsResolver resolver = new SkillVfsResolver();

        assertThatThrownBy(() -> resolver.parse("artifact://my-skill/SKILL.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a skill vfs uri");
    }
}
