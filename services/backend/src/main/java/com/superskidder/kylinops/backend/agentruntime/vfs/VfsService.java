package com.superskidder.kylinops.backend.agentruntime.vfs;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * VFS 统一入口：解析 URI scheme 并分发到对应 resolver。
 * 未知 scheme 拒绝；URI 中嵌入其他 scheme（跨 scheme 注入）拒绝。
 */
@Service
public class VfsService {

    private final Map<String, VfsResolver> resolvers;

    public VfsService(List<VfsResolver> resolverList) {
        Map<String, VfsResolver> byScheme = new HashMap<>();
        for (VfsResolver resolver : resolverList) {
            String scheme = resolver.scheme().toLowerCase(Locale.ROOT);
            if (byScheme.put(scheme, resolver) != null) {
                throw new IllegalStateException("duplicate vfs resolver for scheme: " + scheme);
            }
        }
        this.resolvers = byScheme;
    }

    /** 解析 + 安全校验，返回结构化 VfsUri。 */
    public VfsUri parse(String rawUri) {
        String scheme = schemeOf(rawUri);
        return resolverFor(scheme).parse(rawUri);
    }

    /** 解析并读取资源；context 提供租户与 AgentInstance 归属判定依据。 */
    public VfsResolver.VfsResource read(String rawUri, VfsResolver.VfsContext context) {
        if (context == null) {
            throw new IllegalArgumentException("vfs context is required");
        }
        String scheme = schemeOf(rawUri);
        VfsResolver resolver = resolverFor(scheme);
        return resolver.read(resolver.parse(rawUri), context);
    }

    private String schemeOf(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) {
            throw new IllegalArgumentException("vfs uri is required");
        }
        rejectEmbeddedScheme(rawUri);
        int separator = rawUri.indexOf("://");
        if (separator <= 0) {
            throw new IllegalArgumentException("unsupported vfs uri");
        }
        return rawUri.substring(0, separator).toLowerCase(Locale.ROOT);
    }

    private VfsResolver resolverFor(String scheme) {
        VfsResolver resolver = resolvers.get(scheme);
        if (resolver == null) {
            throw new IllegalArgumentException("unknown vfs scheme: " + scheme);
        }
        return resolver;
    }

    // path/resourceId 中出现第二个 :// 即视为跨 scheme 注入（如 .../skill://other/path）。
    private void rejectEmbeddedScheme(String rawUri) {
        int first = rawUri.indexOf("://");
        if (first >= 0 && rawUri.indexOf("://", first + 3) >= 0) {
            throw new IllegalArgumentException("vfs uri must not embed another scheme");
        }
    }
}
