package com.superskidder.kylinops.backend.agentruntime.skill;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 导入安全校验：路径归一化（PathSecurity）、文件数/单文件/总大小上限（防压缩炸弹）、
 * 可执行二进制魔数检测、敏感路径引用扫描。目录级检查（符号链接等）由 {@link SkillImporter} 承担。
 */
public final class SkillValidator {

    public static final long MAX_FILE_BYTES = 512L * 1024;
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024;
    public static final int MAX_FILES = 64;

    // 包布局白名单：根级文件 + 三个受控目录（与渐进式披露可读前缀一致）。
    private static final List<String> ALLOWED_DIR_PREFIXES = List.of("scripts/", "references/", "assets/");

    // 可执行二进制魔数：ELF / Mach-O（含 Java class）/ PE。
    private static final byte[][] EXECUTABLE_MAGICS = {
            {0x7f, 'E', 'L', 'F'},
            {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce},
            {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xcf},
            {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe},
            {'M', 'Z'}
    };

    // 文本内容中的敏感路径引用（Linux 路径区分大小写，按 ASCII 原样匹配）。
    private static final List<String> SENSITIVE_REFERENCES =
            List.of("/etc/shadow", "/etc/gshadow", "/etc/sudoers", "~/.ssh", "/.ssh/", "id_rsa");

    private SkillValidator() {
    }

    /** 校验并返回按清洗后路径归一的只读文件表；任一检查失败抛 {@link SkillImportException}。 */
    public static Map<String, byte[]> validate(Map<String, byte[]> files) {
        if (files == null || files.isEmpty()) {
            throw new SkillImportException("skill package is empty");
        }
        if (files.size() > MAX_FILES) {
            throw new SkillImportException("skill package has too many files");
        }
        Map<String, byte[]> normalized = new LinkedHashMap<>();
        long totalBytes = 0;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = PathSecurity.sanitizePath(entry.getKey());
            byte[] content = entry.getValue() == null ? new byte[0] : entry.getValue();
            if (content.length > MAX_FILE_BYTES) {
                throw new SkillImportException("skill file exceeds size limit");
            }
            totalBytes += content.length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new SkillImportException("skill package exceeds total size limit");
            }
            requireAllowedLayout(path);
            requireNotExecutable(path, content);
            requireNoSensitiveReference(path, content);
            if (normalized.put(path, content) != null) {
                throw new SkillImportException("skill package contains duplicate paths");
            }
        }
        return Map.copyOf(normalized);
    }

    private static void requireAllowedLayout(String path) {
        if (!path.contains("/")) {
            return;
        }
        for (String prefix : ALLOWED_DIR_PREFIXES) {
            if (path.startsWith(prefix)) {
                return;
            }
        }
        throw new SkillImportException("skill file path is outside the allowed layout");
    }

    private static void requireNotExecutable(String path, byte[] content) {
        for (byte[] magic : EXECUTABLE_MAGICS) {
            if (content.length >= magic.length && startsWith(content, magic)) {
                throw new SkillImportException("skill package contains an executable binary");
            }
        }
    }

    private static void requireNoSensitiveReference(String path, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        for (String sensitive : SENSITIVE_REFERENCES) {
            if (text.contains(sensitive)) {
                throw new SkillImportException("skill content references a sensitive path");
            }
        }
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
