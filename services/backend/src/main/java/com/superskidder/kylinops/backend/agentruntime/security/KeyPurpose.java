package com.superskidder.kylinops.backend.agentruntime.security;

import java.nio.charset.StandardCharsets;

public enum KeyPurpose {
    APPROVAL_SIGNING("approval-signing-key", "KYLIN-APPROVAL-V1"),
    CATALOG_SIGNING("catalog-signing-key", "KYLIN-CATALOG-V1"),
    NODE_CA("node-ca-key", "KYLIN-NODE-CA-V1"),
    PACKAGE_SIGNING("package-signing-key", "KYLIN-PACKAGE-V1");

    private final String wireName;
    private final byte[] sm2UserId;

    KeyPurpose(String wireName, String sm2UserId) {
        this.wireName = wireName;
        this.sm2UserId = sm2UserId.getBytes(StandardCharsets.US_ASCII);
    }

    public String wireName() {
        return wireName;
    }

    public byte[] sm2UserId() {
        return sm2UserId.clone();
    }
}
