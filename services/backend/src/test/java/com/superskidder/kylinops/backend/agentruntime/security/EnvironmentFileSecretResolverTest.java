package com.superskidder.kylinops.backend.agentruntime.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentFileSecretResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesEnvironmentAndAbsoluteFileReferences() throws Exception {
        EnvironmentFileSecretResolver resolver = new EnvironmentFileSecretResolver(
                Map.of("APPROVAL_TEST_KEY", "environment-secret")
        );
        Path keyFile = temporaryDirectory.resolve("catalog-key.pem");
        Files.writeString(keyFile, "file-secret", StandardCharsets.UTF_8);

        try (SecretValue environment = resolver.resolve("env:APPROVAL_TEST_KEY");
             SecretValue file = resolver.resolve("file:" + keyFile)) {
            assertArrayEquals("environment-secret".getBytes(StandardCharsets.UTF_8), environment.copyBytes());
            assertArrayEquals("file-secret".getBytes(StandardCharsets.UTF_8), file.copyBytes());
        }
    }

    @Test
    void rejectsImplicitValuesAndRelativeFiles() {
        EnvironmentFileSecretResolver resolver = new EnvironmentFileSecretResolver(Map.of());

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("plaintext-secret"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("file:relative.pem"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve("env:MISSING_SECRET"));
    }
}
