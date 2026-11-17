package com.superskidder.kylinops.backend.agentruntime.vfs;

import java.util.ArrayList;
import java.util.List;

/**
 * VFS 统一路径安全闸门：三个 scheme 的路径分量使用前都必须经过这里归一化。
 * 拒绝 null bytes、反斜杠、绝对路径与 {@code ..} 穿越（归一化前先拒绝原始 {@code ..} 组件），
 * 限制路径深度与单段长度。错误信息固定措辞、不回显输入，避免泄露宿主路径信息。
 */
public final class PathSecurity {

    public static final int MAX_DEPTH = 16;
    public static final int MAX_SEGMENT_LENGTH = 255;

    private PathSecurity() {
    }

    /** 归一化为安全的相对路径（等效 filepath.Clean 语义）；违规抛 {@link VfsPathSecurityException}。 */
    public static String sanitizePath(String input) {
        if (input == null) {
            throw new VfsPathSecurityException("vfs path is required");
        }
        if (input.indexOf('\0') >= 0) {
            throw new VfsPathSecurityException("vfs path contains null byte");
        }
        if (input.indexOf('\\') >= 0) {
            throw new VfsPathSecurityException("vfs path contains backslash");
        }
        if (input.startsWith("/")) {
            throw new VfsPathSecurityException("vfs path must be relative");
        }
        // 先拒绝原始 .. 组件（防 a/../.. 这类组合），再折叠空段与 . 完成归一化。
        List<String> cleaned = new ArrayList<>();
        for (String segment : input.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new VfsPathSecurityException("vfs path traversal is not allowed");
            }
            if (segment.length() > MAX_SEGMENT_LENGTH) {
                throw new VfsPathSecurityException("vfs path segment too long");
            }
            cleaned.add(segment);
        }
        String normalized = String.join("/", cleaned);
        // 归一化后复检，纵深防御。
        if (normalized.startsWith("/") || containsParentReference(cleaned)) {
            throw new VfsPathSecurityException("vfs path traversal is not allowed");
        }
        if (cleaned.size() > MAX_DEPTH) {
            throw new VfsPathSecurityException("vfs path exceeds max depth");
        }
        return normalized;
    }

    private static boolean containsParentReference(List<String> segments) {
        for (String segment : segments) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** 路径安全专用异常：消息为固定措辞，不包含输入路径。 */
    public static final class VfsPathSecurityException extends RuntimeException {

        public VfsPathSecurityException(String message) {
            super(message);
        }
    }
}
