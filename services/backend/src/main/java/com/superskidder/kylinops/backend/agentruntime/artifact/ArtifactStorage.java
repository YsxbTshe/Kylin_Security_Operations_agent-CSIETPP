package com.superskidder.kylinops.backend.agentruntime.artifact;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Artifact 字节存储抽象：先写 {@code .tmp} 临时文件，校验通过后原子 rename 为正式文件。
 * 正式文件按租户分目录、以 artifact id 命名；{@code storage_path} 只记录相对路径。
 */
public interface ArtifactStorage {

    OutputStream newTempStream(String tenantId, String artifactId) throws IOException;

    /** 将临时文件原子发布为正式文件，返回字节数。 */
    long publish(String tenantId, String artifactId) throws IOException;

    void deleteTemp(String tenantId, String artifactId);

    void deletePublished(String tenantId, String artifactId);

    Path publishedPath(String tenantId, String artifactId);

    /** 遍历全部租户目录下的 {@code .tmp} 文件，供 GC 识别孤儿。 */
    List<TempFile> listTempFiles() throws IOException;

    record TempFile(String tenantId, String artifactId, Path path, Instant lastModified) {
    }
}
