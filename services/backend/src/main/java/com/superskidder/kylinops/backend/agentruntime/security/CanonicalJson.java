package com.superskidder.kylinops.backend.agentruntime.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.erdtman.jcs.JsonCanonicalizer;

public final class CanonicalJson {
    private CanonicalJson() {}

    public static byte[] canonicalize(byte[] json) {
        try {
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid JSON for RFC 8785 canonicalization", exception);
        }
    }

    public static String canonicalize(String json) {
        return new String(canonicalize(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
