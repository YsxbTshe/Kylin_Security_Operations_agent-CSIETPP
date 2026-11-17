package com.superskidder.kylinops.backend.agentruntime.security;

import java.util.EnumMap;
import java.util.Map;

public record PlatformKeyReferences(Map<KeyPurpose, KeyReference> keys) {
    public PlatformKeyReferences {
        keys = Map.copyOf(new EnumMap<>(keys));
        for (KeyPurpose purpose : KeyPurpose.values()) {
            if (!keys.containsKey(purpose)) {
                throw new IllegalArgumentException("missing key configuration for " + purpose.wireName());
            }
        }
    }

    public KeyReference forPurpose(KeyPurpose purpose) {
        return keys.get(purpose);
    }

    public record KeyReference(String keyId, String privateKeyRef, String publicKeyRef) {}
}
