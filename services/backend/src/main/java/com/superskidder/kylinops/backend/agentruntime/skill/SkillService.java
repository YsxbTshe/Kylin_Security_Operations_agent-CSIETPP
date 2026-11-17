package com.superskidder.kylinops.backend.agentruntime.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill CRUD 与状态流转（JdbcTemplate 直写，风格跟随 ArtifactRepository）。
 * 状态机：DRAFT → REVIEWED → PUBLISHED；任意状态可进入 ISOLATED。
 * 导入产生新版本即回退到 DRAFT，需重新审核发布；版本行不可变。
 * 信任等级只能经审核/签名流程提升，导入默认 UNTRUSTED。
 */
@Service
public class SkillService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 导入落库：包不存在则创建（DRAFT/UNTRUSTED），随后追加新版本与文件。 */
    @Transactional
    public SkillVersion storeImport(String tenantId, String skillId, String name, String description,
            String frontmatterJson, String manifestJson, String contentSm3,
            List<String> allowedTools, List<String> requiredCommands, Map<String, byte[]> files) {
        Instant now = Instant.now();
        SkillPackage existing = findPackage(tenantId, skillId).orElse(null);
        String packageId;
        if (existing == null) {
            packageId = newId("skp");
            jdbcTemplate.update("""
                    insert into skill_package (id, tenant_id, skill_id, name, description, trust_level, status, created_at)
                    values (?, ?, ?, ?, ?, 'UNTRUSTED', 'DRAFT', ?)
                    """, packageId, tenantId, skillId, name, truncate(description, 2000), Timestamp.from(now));
        } else {
            packageId = existing.id();
            jdbcTemplate.update("""
                    update skill_package set name = ?, description = ?, status = 'DRAFT' where id = ?
                    """, name, truncate(description, 2000), packageId);
        }

        Integer maxVersion = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) from skill_version where skill_package_id = ?",
                Integer.class, packageId);
        int version = (maxVersion == null ? 0 : maxVersion) + 1;
        String versionId = newId("skv");
        jdbcTemplate.update("""
                insert into skill_version (
                    id, skill_package_id, version, frontmatter_json, manifest_json, content_sm3,
                    signature_algorithm, signature_hex, allowed_tools_json, required_commands_json, created_at
                ) values (?, ?, ?, ?, ?, ?, null, null, ?, ?, ?)
                """, versionId, packageId, version, frontmatterJson, manifestJson, contentSm3,
                writeJson(allowedTools), writeJson(requiredCommands), Timestamp.from(now));

        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            jdbcTemplate.update("""
                    insert into skill_file (id, skill_version_id, relative_path, content, content_sm3, size_bytes, content_type)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, newId("skf"), versionId, entry.getKey(), entry.getValue(),
                    com.superskidder.kylinops.backend.crypto.Sm3.hex(entry.getValue()),
                    (long) entry.getValue().length, contentTypeOf(entry.getKey()));
        }
        return new SkillVersion(versionId, packageId, version, frontmatterJson, manifestJson, contentSm3,
                null, null, List.copyOf(allowedTools), List.copyOf(requiredCommands), now);
    }

    public Optional<SkillPackage> findPackage(String tenantId, String skillId) {
        return jdbcTemplate.query("""
                select * from skill_package where tenant_id = ? and skill_id = ?
                """, this::mapPackage, tenantId, skillId).stream().findFirst();
    }

    /** VFS 用：skill:// URI 不带租户，按 skill_id 取最早发布的 PUBLISHED 包。 */
    public Optional<SkillPackage> findPublishedBySkillId(String skillId) {
        return jdbcTemplate.query("""
                select * from skill_package where skill_id = ? and status = 'PUBLISHED'
                order by created_at asc limit 1
                """, this::mapPackage, skillId).stream().findFirst();
    }

    public List<SkillPackage> listPublished(String tenantId) {
        return jdbcTemplate.query("""
                select * from skill_package where tenant_id = ? and status = 'PUBLISHED' order by created_at asc
                """, this::mapPackage, tenantId);
    }

    public List<SkillVersion> listVersions(String tenantId, String skillId) {
        return jdbcTemplate.query("""
                select v.* from skill_version v join skill_package p on p.id = v.skill_package_id
                where p.tenant_id = ? and p.skill_id = ? order by v.version desc
                """, this::mapVersion, tenantId, skillId);
    }

    public Optional<SkillVersion> latestVersion(String packageId) {
        return jdbcTemplate.query("""
                select * from skill_version where skill_package_id = ? order by version desc limit 1
                """, this::mapVersion, packageId).stream().findFirst();
    }

    public Optional<SkillFileRow> findFile(String skillVersionId, String relativePath) {
        return jdbcTemplate.query("""
                select * from skill_file where skill_version_id = ? and relative_path = ?
                """, this::mapFile, skillVersionId, relativePath).stream().findFirst();
    }

    /** DRAFT → REVIEWED。沙箱测试与人工审核在调用方完成，经参数显式确认后才允许推进。 */
    public void recordManualReview(String tenantId, String skillId, boolean sandboxTestPassed) {
        if (!sandboxTestPassed) {
            throw new IllegalStateException("skill review requires a passed sandbox test");
        }
        requireTransition(tenantId, skillId, "DRAFT", "REVIEWED");
    }

    /** REVIEWED → PUBLISHED。 */
    public void publish(String tenantId, String skillId) {
        requireTransition(tenantId, skillId, "REVIEWED", "PUBLISHED");
    }

    /** 任意状态 → ISOLATED：隔离后 VFS 与渐进式披露一律拒绝。 */
    public void isolate(String tenantId, String skillId) {
        int updated = jdbcTemplate.update("""
                update skill_package set status = 'ISOLATED'
                where tenant_id = ? and skill_id = ? and status <> 'ISOLATED'
                """, tenantId, skillId);
        if (updated == 0) {
            throw new IllegalStateException("skill package not found or already isolated");
        }
    }

    /** 运行时工具授权：信任等级 + allowed_tools 双重校验，违规抛结构化错误。 */
    public void authorizeTool(String tenantId, String skillId, String toolId, boolean writeTool) {
        SkillPackage pkg = findPackage(tenantId, skillId)
                .orElseThrow(() -> new IllegalStateException("skill package not found"));
        if (!SkillPackage.STATUS_PUBLISHED.equals(pkg.status())) {
            throw new IllegalStateException("skill is not published");
        }
        if (!pkg.trustLevel().allowsNodeTools()) {
            throw new IllegalStateException("untrusted skill can only run inside the sandbox");
        }
        if (writeTool && !pkg.trustLevel().allowsWriteFlow()) {
            throw new IllegalStateException("skill trust level does not allow write tools");
        }
        SkillVersion version = latestVersion(pkg.id())
                .orElseThrow(() -> new IllegalStateException("skill version not found"));
        if (!version.allowedTools().contains(toolId)) {
            throw new IllegalStateException("tool is not in skill allowed_tools");
        }
    }

    /** 签名落库并提升信任等级至 PLATFORM_SIGNED（仅签名流程可提升信任）。 */
    public void applySignature(String skillVersionId, String algorithm, String signatureHex, String packageId) {
        int updated = jdbcTemplate.update("""
                update skill_version set signature_algorithm = ?, signature_hex = ? where id = ?
                """, algorithm, signatureHex, skillVersionId);
        if (updated == 0) {
            throw new IllegalStateException("skill version not found");
        }
        jdbcTemplate.update("""
                update skill_package set trust_level = 'PLATFORM_SIGNED' where id = ?
                """, packageId);
    }

    /** 组织审核/特权授予：信任等级只可提升，PLATFORM_SIGNED 保留给签名流程。 */
    public void promoteTrust(String tenantId, String skillId, SkillTrustLevel target) {
        if (target == SkillTrustLevel.PLATFORM_SIGNED) {
            throw new IllegalArgumentException("PLATFORM_SIGNED requires the catalog signing flow");
        }
        SkillPackage pkg = findPackage(tenantId, skillId)
                .orElseThrow(() -> new IllegalStateException("skill package not found"));
        if (target.ordinal() <= pkg.trustLevel().ordinal()) {
            throw new IllegalStateException("skill trust level can only be raised");
        }
        jdbcTemplate.update("update skill_package set trust_level = ? where id = ?", target.name(), pkg.id());
    }

    private void requireTransition(String tenantId, String skillId, String from, String to) {
        int updated = jdbcTemplate.update("""
                update skill_package set status = ? where tenant_id = ? and skill_id = ? and status = ?
                """, to, tenantId, skillId, from);
        if (updated == 0) {
            throw new IllegalStateException("skill status transition rejected: " + from + " -> " + to);
        }
    }

    private SkillPackage mapPackage(ResultSet rs, int rowNum) throws SQLException {
        return new SkillPackage(rs.getString("id"), rs.getString("tenant_id"), rs.getString("skill_id"),
                rs.getString("name"), rs.getString("description"),
                SkillTrustLevel.valueOf(rs.getString("trust_level")), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private SkillVersion mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new SkillVersion(rs.getString("id"), rs.getString("skill_package_id"), rs.getInt("version"),
                rs.getString("frontmatter_json"), rs.getString("manifest_json"), rs.getString("content_sm3"),
                rs.getString("signature_algorithm"), rs.getString("signature_hex"),
                readStringList(rs.getString("allowed_tools_json")),
                readStringList(rs.getString("required_commands_json")),
                rs.getTimestamp("created_at").toInstant());
    }

    private SkillFileRow mapFile(ResultSet rs, int rowNum) throws SQLException {
        return new SkillFileRow(rs.getString("relative_path"), rs.getBytes("content"),
                rs.getString("content_type"), rs.getString("content_sm3"), rs.getLong("size_bytes"));
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize skill list field", exception);
        }
    }

    private List<String> readStringList(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> result = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> result.add(item.asText()));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot parse skill list field", exception);
        }
    }

    private static String contentTypeOf(String path) {
        if (path.endsWith(".md")) {
            return "text/markdown";
        }
        if (path.endsWith(".yaml") || path.endsWith(".yml")) {
            return "application/yaml";
        }
        if (path.endsWith(".sh")) {
            return "text/x-shellscript";
        }
        if (path.endsWith(".py")) {
            return "text/x-python";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
    }
}
