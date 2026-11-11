package com.superskidder.kylinops.backend.agentruntime.vfs;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * VFS URI 解析结果。语法：{@code scheme://resourceId[/path][#contentDigest]}。
 * scheme 取 workspace / skill / artifact；resourceId 随 scheme 分别为
 * agent_instance_id / skill_id / artifact_id；path 为安全清洗后的相对路径
 * （artifact 可为空，表示单文件 artifact 本体）；contentDigest 为可选的内容寻址片段。
 */
public record VfsUri(String scheme, String resourceId, String path, String contentDigest) {

    public static final String SCHEME_WORKSPACE = "workspace";
    public static final String SCHEME_SKILL = "skill";
    public static final String SCHEME_ARTIFACT = "artifact";

    private static final Pattern SCHEME_PATTERN = Pattern.compile("[a-z][a-z0-9+.-]*");
    // 与存储层受控段风格一致；字符集天然排除 / \ ? # 与空值。
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[A-Za-z0-9:._-]{1,128}");

    public VfsUri {
        if (scheme == null || resourceId == null || path == null) {
            throw new IllegalArgumentException("vfs uri fields are required");
        }
    }

    /** 结构化解析 + 安全校验；未知 scheme 的识别交由 VfsService 分发层。 */
    public static VfsUri parse(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) {
            throw new IllegalArgumentException("vfs uri is required");
        }
        if (rawUri.indexOf('\0') >= 0) {
            throw new PathSecurity.VfsPathSecurityException("vfs uri contains null byte");
        }
        int separator = rawUri.indexOf("://");
        if (separator <= 0) {
            throw new IllegalArgumentException("unsupported vfs uri");
        }
        // resourceId/path 中出现第二个 :// 即为跨 scheme 注入，直接拒绝。
        if (rawUri.indexOf("://", separator + 3) >= 0) {
            throw new IllegalArgumentException("vfs uri must not embed another scheme");
        }
        String scheme = rawUri.substring(0, separator).toLowerCase(Locale.ROOT);
        if (!SCHEME_PATTERN.matcher(scheme).matches()) {
            throw new IllegalArgumentException("unsupported vfs uri");
        }
        String remainder = rawUri.substring(separator + 3);
        String digest = null;
        int fragment = remainder.indexOf('#');
        if (fragment >= 0) {
            digest = remainder.substring(fragment + 1);
            remainder = remainder.substring(0, fragment);
        }
        int slash = remainder.indexOf('/');
        String resourceId = slash < 0 ? remainder : remainder.substring(0, slash);
        String rawPath = slash < 0 ? "" : remainder.substring(slash + 1);
        if (".".equals(resourceId) || "..".equals(resourceId)
                || !RESOURCE_ID_PATTERN.matcher(resourceId).matches()) {
            throw new IllegalArgumentException("invalid vfs resource id");
        }
        String path = PathSecurity.sanitizePath(rawPath);
        if (digest != null) {
            if (digest.isBlank() || !DIGEST_PATTERN.matcher(digest).matches()) {
                throw new IllegalArgumentException("invalid vfs content digest");
            }
        }
        return new VfsUri(scheme, resourceId, path, digest);
    }
}
