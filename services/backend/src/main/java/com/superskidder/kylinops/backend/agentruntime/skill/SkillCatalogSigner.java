package com.superskidder.kylinops.backend.agentruntime.skill;

import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Skill catalog 签名：对 skill_version 内容摘要做 HMAC-SM3，签名后将包信任等级提升至
 * PLATFORM_SIGNED。密钥经 {@code app.skill.signing-key} 注入，未配置时拒绝签名（fail-closed）。
 */
@Component
public class SkillCatalogSigner {

    public static final String SIGNATURE_ALGORITHM = "HMAC-SM3";

    private final SkillService skillService;
    private final byte[] signingKey;

    public SkillCatalogSigner(SkillService skillService,
            @Value("${app.skill.signing-key:}") String signingKey) {
        this.skillService = skillService;
        this.signingKey = signingKey == null ? new byte[0] : signingKey.getBytes(StandardCharsets.UTF_8);
    }

    /** 对最新版本签名并提升信任等级至 PLATFORM_SIGNED，返回 signature_hex。 */
    public String signLatestVersion(String tenantId, String skillId) {
        if (signingKey.length == 0) {
            throw new IllegalStateException("catalog signing key is not configured");
        }
        SkillPackage pkg = skillService.findPackage(tenantId, skillId)
                .orElseThrow(() -> new IllegalStateException("skill package not found"));
        SkillVersion version = skillService.latestVersion(pkg.id())
                .orElseThrow(() -> new IllegalStateException("skill version not found"));
        String signatureHex = Sm3.hmacHex(signingKey, version.contentSm3());
        skillService.applySignature(version.id(), SIGNATURE_ALGORITHM, signatureHex, pkg.id());
        return signatureHex;
    }

    /** 验签：按内容摘要重算 HMAC 并与库中 signature_hex 恒定时间比较。 */
    public boolean verify(String tenantId, String skillId) {
        if (signingKey.length == 0) {
            throw new IllegalStateException("catalog signing key is not configured");
        }
        SkillPackage pkg = skillService.findPackage(tenantId, skillId)
                .orElseThrow(() -> new IllegalStateException("skill package not found"));
        SkillVersion version = skillService.latestVersion(pkg.id())
                .orElseThrow(() -> new IllegalStateException("skill version not found"));
        if (!version.isSigned()) {
            return false;
        }
        String expected = Sm3.hmacHex(signingKey, version.contentSm3());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                version.signatureHex().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 统一 schema digest 入口：schema JSON → RFC 8785 规范化 → SM3 hex。
     * 供回填 V58 迁移与 Workspace*Tool 中占位为 'pending' 的 digest
     * （迁移与常量本身不在本迭代改动，回填时用本方法对 V58 中的 schema JSON 字面量计算）。
     */
    public static String schemaDigest(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("schema JSON is required");
        }
        return Sm3.hex(CanonicalJson.canonicalize(schemaJson));
    }
}
