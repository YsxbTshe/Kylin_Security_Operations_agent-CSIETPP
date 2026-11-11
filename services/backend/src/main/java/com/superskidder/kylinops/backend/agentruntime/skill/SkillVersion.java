package com.superskidder.kylinops.backend.agentruntime.skill;

import java.time.Instant;
import java.util.List;

/** skill_version 行：不可变版本快照；version 在同一包内自增。 */
public record SkillVersion(
        String id,
        String skillPackageId,
        int version,
        String frontmatterJson,
        String manifestJson,
        String contentSm3,
        String signatureAlgorithm,
        String signatureHex,
        List<String> allowedTools,
        List<String> requiredCommands,
        Instant createdAt) {

    public boolean isSigned() {
        return signatureHex != null && !signatureHex.isBlank();
    }
}
