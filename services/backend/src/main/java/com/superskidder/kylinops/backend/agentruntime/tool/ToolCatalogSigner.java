package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.security.ApprovalKeyLoader;
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.agentruntime.security.KeyPurpose;
import com.superskidder.kylinops.backend.agentruntime.security.PlatformKeyReferences;
import com.superskidder.kylinops.backend.agentruntime.security.Sm2WithSm3;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillCatalogSigner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * tool_package_version（input/output schema 分列新形态行）的目录签名管线。
 *
 * 签名负载是行内绑定字段的 RFC 8785 规范化 JSON（id、toolPackageId、toolId、
 * version、inputSchemaDigest、outputSchemaDigest），经 SM3withSM2（密钥用途
 * CATALOG_SIGNING）签名后以 DER hex 写入 signature_hex。'pending' 的 schema
 * digest 在签名时按 SM3(规范化 schema JSON) 顺带回填。
 *
 * 密钥经 app.security.keys.catalog-signing（PlatformKeyReferences）注入，
 * PEM 文件由 ApprovalKeyLoader 加载；未配置时签名/验签拒绝（fail-closed），
 * 与 SkillCatalogSigner 同口径。
 */
@Component
public class ToolCatalogSigner {

    public static final String SIGNATURE_ALGORITHM = "SM2_WITH_SM3";
    static final String PENDING = "pending";

    private final JdbcTemplate jdbcTemplate;
    private final PlatformKeyReferences keyReferences;
    private final ApprovalKeyLoader keyLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolCatalogSigner(JdbcTemplate jdbcTemplate,
            PlatformKeyReferences keyReferences, ApprovalKeyLoader keyLoader) {
        this.jdbcTemplate = jdbcTemplate;
        this.keyReferences = keyReferences;
        this.keyLoader = keyLoader;
    }

    /** 对单行签名并回写 digest/signature_hex/signing_key_id，返回 signature_hex。 */
    @Transactional
    public String sign(String versionId) {
        PlatformKeyReferences.KeyReference key = catalogKey();
        if (key.keyId() == null || key.keyId().isBlank()) {
            throw new IllegalStateException("catalog signing key id is not configured");
        }
        VersionRow row = findRow(versionId);
        String inputDigest = resolveDigest(row.inputSchemaJson(), row.inputSchemaDigest());
        String outputDigest = resolveDigest(row.outputSchemaJson(), row.outputSchemaDigest());
        byte[] payload = bindingPayload(row.id(), row.toolPackageId(), row.toolId(),
                row.version(), inputDigest, outputDigest);
        PrivateKey privateKey = keyLoader.loadPrivateKey(key.privateKeyRef());
        String signatureHex = HexFormat.of().formatHex(
                Sm2WithSm3.sign(privateKey, KeyPurpose.CATALOG_SIGNING, payload));
        jdbcTemplate.update("""
                update tool_package_version
                set input_schema_digest = ?, output_schema_digest = ?,
                    signature_algorithm = ?, signing_key_id = ?, signature_hex = ?
                where id = ?
                """,
                inputDigest, outputDigest, SIGNATURE_ALGORITHM, key.keyId(), signatureHex, versionId);
        return signatureHex;
    }

    /** 对所有新形态未签名行（signature_hex 为 'pending' 或空）签名，返回已签名行 id。 */
    @Transactional
    public List<String> signAllPending() {
        List<String> ids = jdbcTemplate.queryForList("""
                select id from tool_package_version
                where tool_id is not null
                  and (signature_hex is null or signature_hex = ?)
                order by id
                """, String.class, PENDING);
        for (String id : ids) {
            sign(id);
        }
        return ids;
    }

    /**
     * 验签：重算 schema digest 并与库值恒定时间比较，再按绑定字段重建负载做
     * SM2 验签。未签名（pending/空）、digest 失配或签名无效一律返回 false。
     */
    public boolean verify(String versionId) {
        PlatformKeyReferences.KeyReference key = catalogKey();
        if (key.publicKeyRef() == null || key.publicKeyRef().isBlank()) {
            throw new IllegalStateException("catalog verification public key is not configured");
        }
        VersionRow row = findRow(versionId);
        if (row.signatureHex() == null || row.signatureHex().isBlank()
                || PENDING.equals(row.signatureHex())) {
            return false;
        }
        if (!digestMatches(row.inputSchemaJson(), row.inputSchemaDigest())
                || !digestMatches(row.outputSchemaJson(), row.outputSchemaDigest())) {
            return false;
        }
        byte[] payload = bindingPayload(row.id(), row.toolPackageId(), row.toolId(),
                row.version(), row.inputSchemaDigest(), row.outputSchemaDigest());
        PublicKey publicKey = keyLoader.loadPublicKey(key.publicKeyRef());
        return Sm2WithSm3.verify(publicKey, KeyPurpose.CATALOG_SIGNING, payload,
                HexFormat.of().parseHex(row.signatureHex()));
    }

    private PlatformKeyReferences.KeyReference catalogKey() {
        return keyReferences.forPurpose(KeyPurpose.CATALOG_SIGNING);
    }

    private VersionRow findRow(String versionId) {
        List<VersionRow> rows = jdbcTemplate.query("""
                select id, tool_package_id, tool_id, version,
                       input_schema_json, output_schema_json,
                       input_schema_digest, output_schema_digest, signature_hex
                from tool_package_version where id = ?
                """,
                (rs, rowNum) -> new VersionRow(
                        rs.getString("id"),
                        rs.getString("tool_package_id"),
                        rs.getString("tool_id"),
                        rs.getString("version"),
                        rs.getString("input_schema_json"),
                        rs.getString("output_schema_json"),
                        rs.getString("input_schema_digest"),
                        rs.getString("output_schema_digest"),
                        rs.getString("signature_hex")),
                versionId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("tool package version not found: " + versionId);
        }
        VersionRow row = rows.get(0);
        if (row.toolId() == null || row.inputSchemaJson() == null || row.outputSchemaJson() == null) {
            throw new IllegalArgumentException(
                    "tool package version is not in schema-split form: " + versionId);
        }
        return row;
    }

    private String resolveDigest(String schemaJson, String stored) {
        if (stored != null && !stored.isBlank() && !PENDING.equals(stored)) {
            return stored;
        }
        return SkillCatalogSigner.schemaDigest(schemaJson);
    }

    private boolean digestMatches(String schemaJson, String stored) {
        if (stored == null || stored.isBlank() || PENDING.equals(stored)) {
            return false;
        }
        String expected = SkillCatalogSigner.schemaDigest(schemaJson);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] bindingPayload(String id, String toolPackageId, String toolId,
            String version, String inputDigest, String outputDigest) {
        TreeMap<String, String> binding = new TreeMap<>();
        binding.put("id", id);
        binding.put("toolPackageId", toolPackageId == null ? "" : toolPackageId);
        binding.put("toolId", toolId);
        binding.put("version", version);
        binding.put("inputSchemaDigest", inputDigest);
        binding.put("outputSchemaDigest", outputDigest);
        try {
            return CanonicalJson.canonicalize(objectMapper.writeValueAsBytes(binding));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize catalog binding payload", exception);
        }
    }

    record VersionRow(String id, String toolPackageId, String toolId, String version,
            String inputSchemaJson, String outputSchemaJson,
            String inputSchemaDigest, String outputSchemaDigest, String signatureHex) {}
}
