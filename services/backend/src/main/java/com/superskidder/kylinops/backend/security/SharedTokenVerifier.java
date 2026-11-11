package com.superskidder.kylinops.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SharedTokenVerifier {

    private SharedTokenVerifier() {
    }

    public static String requireConfigured(String token, String propertyName) {
        String normalized = token == null ? "" : token.trim();
        if (normalized.isBlank()) {
            throw new IllegalStateException(propertyName + " must be configured");
        }
        return normalized;
    }

    public static boolean matches(String expectedToken, String actualToken) {
        if (actualToken == null || actualToken.isBlank()) {
            return false;
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = actualToken.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
