package com.superskidder.kylinops.backend.agentruntime.skill;

import com.superskidder.kylinops.backend.agentruntime.vfs.SkillVfsResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link SkillVfsResolver.SkillFileProvider} 的落地实现：把 skill:// VFS 接到 Skill 注册表。
 * 只放行 PUBLISHED 状态的包、只读；未发布/未注册的 skill_id 返回空，
 * 由 resolver 收敛为结构化错误"skill not registered"。
 */
@Component
public class SkillFileStore implements SkillVfsResolver.SkillFileProvider {

    private final SkillService skillService;

    public SkillFileStore(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public Optional<SkillVfsResolver.SkillFile> findFile(String skillId, String relativePath) {
        Optional<SkillPackage> pkg = skillService.findPublishedBySkillId(skillId);
        if (pkg.isEmpty()) {
            return Optional.empty();
        }
        Optional<SkillVersion> version = skillService.latestVersion(pkg.get().id());
        if (version.isEmpty()) {
            return Optional.empty();
        }
        return skillService.findFile(version.get().id(), relativePath)
                .map(file -> new SkillVfsResolver.SkillFile(file.content(), file.contentType()));
    }
}
