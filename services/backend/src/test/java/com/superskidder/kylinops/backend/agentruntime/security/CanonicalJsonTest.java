package com.superskidder.kylinops.backend.agentruntime.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void matchesSharedRfc8785Vector() throws Exception {
        Path vectors = Path.of("..", "..", "contracts", "test-vectors");
        byte[] input = Files.readAllBytes(vectors.resolve("canonical-json-input.json"));
        String expected = Files.readString(vectors.resolve("canonical-json-expected.json"), StandardCharsets.UTF_8).trim();
        assertEquals(expected, new String(CanonicalJson.canonicalize(input), StandardCharsets.UTF_8));
    }
}
