package com.superskidder.kylinops.backend.agentruntime.security;

import java.util.Arrays;

public final class SecretValue implements AutoCloseable {
    private byte[] value;

    public SecretValue(byte[] value) {
        this.value = value.clone();
    }

    public byte[] copyBytes() {
        if (value == null) throw new IllegalStateException("secret value is closed");
        return value.clone();
    }

    @Override
    public void close() {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
            value = null;
        }
    }
}
