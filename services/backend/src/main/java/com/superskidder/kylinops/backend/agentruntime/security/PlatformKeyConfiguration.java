package com.superskidder.kylinops.backend.agentruntime.security;

import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformKeyConfiguration {
    @Bean
    public PlatformKeyReferences platformKeyReferences(
            @Value("${app.security.keys.approval-signing.key-id:}") String approvalKeyId,
            @Value("${app.security.keys.approval-signing.private-key-ref:}") String approvalPrivate,
            @Value("${app.security.keys.approval-signing.public-key-ref:}") String approvalPublic,
            @Value("${app.security.keys.catalog-signing.key-id:}") String catalogKeyId,
            @Value("${app.security.keys.catalog-signing.private-key-ref:}") String catalogPrivate,
            @Value("${app.security.keys.catalog-signing.public-key-ref:}") String catalogPublic,
            @Value("${app.security.keys.node-ca.key-id:}") String nodeCaKeyId,
            @Value("${app.security.keys.node-ca.private-key-ref:}") String nodeCaPrivate,
            @Value("${app.security.keys.node-ca.public-key-ref:}") String nodeCaPublic,
            @Value("${app.security.keys.package-signing.key-id:}") String packageKeyId,
            @Value("${app.security.keys.package-signing.private-key-ref:}") String packagePrivate,
            @Value("${app.security.keys.package-signing.public-key-ref:}") String packagePublic
    ) {
        Map<KeyPurpose, PlatformKeyReferences.KeyReference> keys = new EnumMap<>(KeyPurpose.class);
        keys.put(KeyPurpose.APPROVAL_SIGNING, new PlatformKeyReferences.KeyReference(approvalKeyId, approvalPrivate, approvalPublic));
        keys.put(KeyPurpose.CATALOG_SIGNING, new PlatformKeyReferences.KeyReference(catalogKeyId, catalogPrivate, catalogPublic));
        keys.put(KeyPurpose.NODE_CA, new PlatformKeyReferences.KeyReference(nodeCaKeyId, nodeCaPrivate, nodeCaPublic));
        keys.put(KeyPurpose.PACKAGE_SIGNING, new PlatformKeyReferences.KeyReference(packageKeyId, packagePrivate, packagePublic));
        return new PlatformKeyReferences(keys);
    }
}
