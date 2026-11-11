package com.superskidder.kylinops.backend.agentruntime.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentFileSecretResolver implements SecretResolver {
    private final Map<String, String> environment;

    public EnvironmentFileSecretResolver() {
        this(System.getenv());
    }

    EnvironmentFileSecretResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public SecretValue resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("secret reference is required");
        }
        if (reference.startsWith("env:")) {
            String name = reference.substring("env:".length());
            if (!name.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("invalid environment secret reference");
            }
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("secret environment variable is not configured: " + name);
            }
            return new SecretValue(value.getBytes(StandardCharsets.UTF_8));
        }
        if (reference.startsWith("file:")) {
            Path path = Path.of(reference.substring("file:".length())).normalize();
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("secret file reference must be absolute");
            }
            try {
                return new SecretValue(Files.readAllBytes(path));
            } catch (IOException exception) {
                throw new IllegalStateException("cannot resolve secret file", exception);
            }
        }
        throw new IllegalArgumentException("unsupported secret reference scheme");
    }
}
