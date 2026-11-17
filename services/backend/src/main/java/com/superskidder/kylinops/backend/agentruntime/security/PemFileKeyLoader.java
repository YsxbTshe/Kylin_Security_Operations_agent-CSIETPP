package com.superskidder.kylinops.backend.agentruntime.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.springframework.stereotype.Component;

@Component
public class PemFileKeyLoader implements ApprovalKeyLoader {

    @Override
    public PrivateKey loadPrivateKey(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException("approval signing private key reference is not configured");
        }
        try {
            byte[] pem = Files.readAllBytes(Path.of(reference));
            return Sm2WithSm3.parsePrivateKey(pem);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load approval signing private key: " + reference, exception);
        }
    }

    @Override
    public PublicKey loadPublicKey(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException("approval signing public key reference is not configured");
        }
        try {
            byte[] pem = Files.readAllBytes(Path.of(reference));
            return Sm2WithSm3.parsePublicKey(pem);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load approval signing public key: " + reference, exception);
        }
    }
}
