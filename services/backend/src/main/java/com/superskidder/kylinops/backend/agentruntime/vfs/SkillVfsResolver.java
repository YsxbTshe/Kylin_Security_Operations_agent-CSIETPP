package com.superskidder.kylinops.backend.agentruntime.vfs;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code skill://{skill_id}/path}：只读访问 Skill 静态资源。
 * 读取委托 {@link SkillFileProvider}（Iteration 4.5 由 skill 包 SkillFileStore 装配，
 * 只放行 PUBLISHED 状态的包）；查询为空时收敛为结构化错误"skill 未注册"。
 * URI 解析与安全校验始终完整执行。
 */
@Component
public class SkillVfsResolver implements VfsResolver {

    private final SkillFileProvider provider;

    /** 测试用存根构造：provider 恒为空，读取收敛为"skill 未注册"。 */
    public SkillVfsResolver() {
        this((skillId, relativePath) -> Optional.empty());
    }

    @Autowired
    public SkillVfsResolver(SkillFileProvider provider) {
        this.provider = provider;
    }

    @Override
    public String scheme() {
        return VfsUri.SCHEME_SKILL;
    }

    @Override
    public VfsUri parse(String rawUri) {
        VfsUri uri = VfsUri.parse(rawUri);
        if (!scheme().equals(uri.scheme())) {
            throw new IllegalArgumentException("not a skill vfs uri");
        }
        if (uri.path().isEmpty()) {
            throw new IllegalArgumentException("skill vfs uri must include a relative path");
        }
        return uri;
    }

    @Override
    public VfsResource read(VfsUri uri, VfsContext context) {
        Optional<SkillFile> file = provider.findFile(uri.resourceId(), uri.path());
        if (file.isEmpty()) {
            throw new IllegalArgumentException("skill not registered: " + uri.resourceId());
        }
        SkillFile skillFile = file.get();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("skillId", uri.resourceId());
        metadata.put("path", uri.path());
        metadata.put("contentType", skillFile.contentType());
        metadata.put("sizeBytes", skillFile.content().length);
        return new VfsResource(uri, metadata, new ByteArrayInputStream(skillFile.content()));
    }

    /** 按 skill_id + 相对路径查询 Skill 文件的接口：由 Skill 注册表落地后提供实现。 */
    public interface SkillFileProvider {

        Optional<SkillFile> findFile(String skillId, String relativePath);
    }

    /** Skill 文件内容载体。 */
    public record SkillFile(byte[] content, String contentType) {
    }
}
