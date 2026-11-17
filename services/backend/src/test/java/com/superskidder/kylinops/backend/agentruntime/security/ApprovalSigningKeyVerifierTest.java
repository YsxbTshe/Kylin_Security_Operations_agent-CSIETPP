package com.superskidder.kylinops.backend.agentruntime.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovalSigningKeyVerifierTest {

    @Test
    void skipsWhenKeysAreNotConfigured() {
        assertDoesNotThrow(() -> new ApprovalSigningKeyVerifier(emptyReferences(), new PemFileKeyLoader()));
    }

    @Test
    void failsFastWhenConfiguredKeyCannotBeParsed(@TempDir Path dir) throws Exception {
        Path privateKey = dir.resolve("approval-private.pem");
        Path publicKey = dir.resolve("approval.pub");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----\n");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\nnot-a-key\n-----END PUBLIC KEY-----\n");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ApprovalSigningKeyVerifier(references(privateKey, publicKey), new PemFileKeyLoader())
        );
        assertTrue(exception.getMessage().contains("cannot load or probe-sign"));
    }

    @Test
    void probeSignsConfiguredTestVector() {
        Path vectors = Path.of("..", "..", "contracts", "test-vectors");
        assertDoesNotThrow(() -> new ApprovalSigningKeyVerifier(
                references(vectors.resolve("TEST_ONLY_sm2_private.pem"), vectors.resolve("TEST_ONLY_sm2_public.pem")),
                new PemFileKeyLoader()
        ));
    }

    private static PlatformKeyReferences emptyReferences() {
        Map<KeyPurpose, PlatformKeyReferences.KeyReference> keys = new EnumMap<>(KeyPurpose.class);
        for (KeyPurpose purpose : KeyPurpose.values()) {
            keys.put(purpose, new PlatformKeyReferences.KeyReference("", "", ""));
        }
        return new PlatformKeyReferences(keys);
    }

    private static PlatformKeyReferences references(Path privateKey, Path publicKey) {
        Map<KeyPurpose, PlatformKeyReferences.KeyReference> keys = new EnumMap<>(KeyPurpose.class);
        for (KeyPurpose purpose : KeyPurpose.values()) {
            keys.put(purpose, new PlatformKeyReferences.KeyReference("", "", ""));
        }
        keys.put(KeyPurpose.APPROVAL_SIGNING, new PlatformKeyReferences.KeyReference(
                "approval-1",
                privateKey.toString(),
                publicKey.toString()
        ));
        return new PlatformKeyReferences(keys);
    }
}
