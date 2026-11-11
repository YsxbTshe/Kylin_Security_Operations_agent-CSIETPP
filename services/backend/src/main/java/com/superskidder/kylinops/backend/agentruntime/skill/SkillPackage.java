package com.superskidder.kylinops.backend.agentruntime.skill;

import java.time.Instant;

/** skill_package 行：按 (tenant_id, skill_id) 唯一的 Skill 包及其当前信任/发布状态。 */
public record SkillPackage(
        String id,
        String tenantId,
        String skillId,
        String name,
        String description,
        SkillTrustLevel trustLevel,
        String status,
        Instant createdAt) {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ISOLATED = "ISOLATED";
}
