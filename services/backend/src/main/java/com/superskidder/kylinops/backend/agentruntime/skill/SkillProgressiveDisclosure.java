package com.superskidder.kylinops.backend.agentruntime.skill;

import com.superskidder.kylinops.backend.agentruntime.vfs.PathSecurity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 渐进式上下文构建：AgentInstance 创建时只暴露 name/description 列表（{@link #listSkills}），
 * SKILL.md 正文（{@link #loadSkillBody}）与 scripts/references/assets 资源
 * （{@link #loadResource}）按需加载。仅 PUBLISHED 状态的 Skill 可读。
 */
@Service
public class SkillProgressiveDisclosure {

    private static final String SKILL_BODY_PATH = "SKILL.md";
    private static final List<String> RESOURCE_PREFIXES = List.of("scripts/", "references/", "assets/");

    private final SkillService skillService;

    public SkillProgressiveDisclosure(SkillService skillService) {
        this.skillService = skillService;
    }

    /** 披露第一层：仅 skillId/name/description，不含正文与资源。 */
    public record SkillSummary(String skillId, String name, String description) {
    }

    public List<SkillSummary> listSkills(String tenantId) {
        requireTenant(tenantId);
        return skillService.listPublished(tenantId).stream()
                .map(pkg -> new SkillSummary(pkg.skillId(), pkg.name(), pkg.description()))
                .toList();
    }

    /** 披露第二层：SKILL.md 全文。 */
    public String loadSkillBody(String tenantId, String skillId) {
        SkillFileRow file = loadFile(tenantId, skillId, SKILL_BODY_PATH);
        return new String(file.content(), StandardCharsets.UTF_8);
    }

    /** 披露第三层：按需读取 scripts/references/assets 下的资源；路径经 PathSecurity 清洗。 */
    public SkillFileRow loadResource(String tenantId, String skillId, String relativePath) {
        String sanitized = PathSecurity.sanitizePath(relativePath);
        boolean allowed = false;
        for (String prefix : RESOURCE_PREFIXES) {
            if (sanitized.startsWith(prefix)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IllegalArgumentException("skill resource path is outside scripts/references/assets");
        }
        return loadFile(tenantId, skillId, sanitized);
    }

    private SkillFileRow loadFile(String tenantId, String skillId, String path) {
        requireTenant(tenantId);
        SkillPackage pkg = skillService.findPackage(tenantId, skillId)
                .orElseThrow(() -> new IllegalArgumentException("skill not registered: " + skillId));
        if (!SkillPackage.STATUS_PUBLISHED.equals(pkg.status())) {
            throw new IllegalArgumentException("skill is not published: " + skillId);
        }
        SkillVersion version = skillService.latestVersion(pkg.id())
                .orElseThrow(() -> new IllegalArgumentException("skill version not found: " + skillId));
        return skillService.findFile(version.id(), path)
                .orElseThrow(() -> new IllegalArgumentException("skill resource not found: " + path));
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant_id is required");
        }
    }
}
