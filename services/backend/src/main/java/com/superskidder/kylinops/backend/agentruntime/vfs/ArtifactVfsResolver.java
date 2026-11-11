package com.superskidder.kylinops.backend.agentruntime.vfs;

import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactRecord;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactRepository;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactService;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code artifact://{artifact_id}[/archive 内路径]}：只读，且仅放行 PUBLISHED 状态的 artifact。
 * 单文件 artifact 直接返回存储流；archive 内路径先经 PathSecurity 校验，
 * 本迭代解包读取仅为占位（抛 UnsupportedOperationException）。
 */
@Component
public class ArtifactVfsResolver implements VfsResolver {

    private final ArtifactRepository repository;
    private final ArtifactStorage storage;

    public ArtifactVfsResolver(ArtifactRepository repository, ArtifactStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Override
    public String scheme() {
        return VfsUri.SCHEME_ARTIFACT;
    }

    @Override
    public VfsUri parse(String rawUri) {
        VfsUri uri = VfsUri.parse(rawUri);
        if (!scheme().equals(uri.scheme())) {
            throw new IllegalArgumentException("not an artifact vfs uri");
        }
        return uri;
    }

    @Override
    public VfsResource read(VfsUri uri, VfsContext context) {
        // 仅放行 PUBLISHED：WRITING/FAILED/不存在一律按不可读收敛。
        ArtifactRecord record = repository.findById(context.tenantId(), uri.resourceId())
                .filter(candidate -> ArtifactService.STATUS_PUBLISHED.equals(candidate.status()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "artifact not found or not published: " + uri.resourceId()));
        if (!uri.path().isEmpty()) {
            // archive 内路径已在解析阶段 sanitize；实际解包读取留待后续迭代。
            throw new UnsupportedOperationException("archive member read not supported yet");
        }
        Path published = storage.publishedPath(record.tenantId(), record.id());
        try {
            InputStream content = Files.newInputStream(published);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", record.id());
            metadata.put("name", record.name());
            metadata.put("contentType", record.contentType());
            metadata.put("sizeBytes", record.sizeBytes());
            metadata.put("sm3Hex", record.sm3Hex());
            metadata.put("status", record.status());
            metadata.put("publishedAt", record.publishedAt());
            return new VfsResource(uri, metadata, content);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read artifact file", exception);
        }
    }
}
