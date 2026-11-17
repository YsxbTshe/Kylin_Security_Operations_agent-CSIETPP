package com.superskidder.kylinops.backend.agentruntime.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.artifact.StreamingDigest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Skill 导入管线：目录读取（拒绝符号链接）→ 安全校验 → frontmatter 解析 → manifest 解析 →
 * 计算内容 SM3 → 落库（status=DRAFT、trust_level=UNTRUSTED）。
 * Skill 导入后不可修改，再次导入产生新版本并回退到 DRAFT；沙箱测试与人工审核是
 * {@link SkillService#recordManualReview} 的前置条件，本迭代不接真实容器测试。
 */
@Component
public class SkillImporter {

    private final SkillService skillService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillImporter(SkillService skillService) {
        this.skillService = skillService;
    }

    public record ImportResult(String skillId, SkillVersion version, String contentSm3) {
    }

    /** 从磁盘目录导入；符号链接与非普通文件直接拒绝。 */
    public ImportResult importDirectory(String tenantId, Path packageDir) {
        requireTenant(tenantId);
        if (packageDir == null || !Files.isDirectory(packageDir)) {
            throw new SkillImportException("skill package directory is required");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try {
            Files.walkFileTree(packageDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                        throw new SkillImportException("skill package contains a symbolic link");
                    }
                    if (!attributes.isRegularFile()) {
                        throw new SkillImportException("skill package contains a non-regular file");
                    }
                    String relative = packageDir.relativize(file).toString().replace('\\', '/');
                    files.put(relative, Files.readAllBytes(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new SkillImportException("cannot read skill package directory", exception);
        }
        return importFiles(tenantId, files);
    }

    /** 内存文件表导入（测试与非磁盘来源使用），管线与目录导入完全一致。 */
    public ImportResult importFiles(String tenantId, Map<String, byte[]> rawFiles) {
        requireTenant(tenantId);
        Map<String, byte[]> files = SkillValidator.validate(rawFiles);

        byte[] skillMarkdown = files.get(SkillFrontmatterParser.SKILL_FILE);
        if (skillMarkdown == null) {
            throw new SkillImportException("skill package must contain SKILL.md");
        }
        byte[] manifestYaml = files.get(SkillManifestParser.MANIFEST_FILE);
        if (manifestYaml == null) {
            throw new SkillImportException("skill package must contain kylin-skill.yaml");
        }

        SkillFrontmatterParser.Frontmatter frontmatter =
                SkillFrontmatterParser.parse(new String(skillMarkdown, StandardCharsets.UTF_8));
        SkillManifestParser.SkillManifest manifest =
                SkillManifestParser.parse(new String(manifestYaml, StandardCharsets.UTF_8));

        String skillId = manifest.skillId() != null ? manifest.skillId() : slugify(frontmatter.name());
        String contentSm3 = canonicalSm3(files);
        String frontmatterJson = writeJson(frontmatter.attributes());
        String manifestJson = writeJson(Map.of(
                "requested_trust_level", manifest.requestedTrustLevel().name(),
                "allowed_tools", manifest.allowedTools(),
                "required_commands", manifest.requiredCommands(),
                "resources", manifest.resources()));

        SkillVersion version = skillService.storeImport(tenantId.trim(), skillId,
                frontmatter.name(), frontmatter.description(), frontmatterJson, manifestJson, contentSm3,
                manifest.allowedTools(), manifest.requiredCommands(), files);
        return new ImportResult(skillId, version, contentSm3);
    }

    /** 内容 SM3：按路径排序的确定性串接（path \0 length \0 bytes \0）。 */
    static String canonicalSm3(Map<String, byte[]> files) {
        StreamingDigest digest = new StreamingDigest();
        try {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(files).entrySet()) {
                byte[] pathBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                digest.write(pathBytes);
                digest.write(0);
                digest.write(Long.toString(entry.getValue().length).getBytes(StandardCharsets.UTF_8));
                digest.write(0);
                digest.write(entry.getValue());
                digest.write(0);
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("digest stream failed", impossible);
        }
        return digest.finish();
    }

    static String slugify(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new SkillImportException("skill_id cannot be derived from the skill name");
        }
        return slug.length() <= 128 ? slug : slug.substring(0, 128);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SkillImportException("cannot serialize skill metadata", exception);
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant_id is required");
        }
    }
}
