package com.superskidder.kylinops.backend.agentruntime.artifact;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileSystemArtifactStorage implements ArtifactStorage {

    static final String TEMP_SUFFIX = ".tmp";
    // 租户目录与文件名都来自受控 id，但仍拒绝路径分隔符等字符，防目录穿越。
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final Path root;

    public FileSystemArtifactStorage(@Value("${app.artifact.storage-dir:./data/artifacts}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Override
    public OutputStream newTempStream(String tenantId, String artifactId) throws IOException {
        Path temp = tempPath(tenantId, artifactId);
        Files.createDirectories(temp.getParent());
        return new BufferedOutputStream(Files.newOutputStream(temp));
    }

    @Override
    public long publish(String tenantId, String artifactId) throws IOException {
        Path temp = tempPath(tenantId, artifactId);
        Path published = publishedPath(tenantId, artifactId);
        Files.createDirectories(published.getParent());
        Files.move(temp, published, StandardCopyOption.ATOMIC_MOVE);
        return Files.size(published);
    }

    @Override
    public void deleteTemp(String tenantId, String artifactId) {
        deleteQuietly(tempPath(tenantId, artifactId));
    }

    @Override
    public void deletePublished(String tenantId, String artifactId) {
        deleteQuietly(publishedPath(tenantId, artifactId));
    }

    @Override
    public Path publishedPath(String tenantId, String artifactId) {
        return root.resolve(segment(tenantId)).resolve(segment(artifactId));
    }

    @Override
    public List<TempFile> listTempFiles() throws IOException {
        List<TempFile> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> tenants = Files.list(root)) {
            for (Path tenantDir : tenants.filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.list(tenantDir)) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(TEMP_SUFFIX)).toList()) {
                        String fileName = file.getFileName().toString();
                        String artifactId = fileName.substring(0, fileName.length() - TEMP_SUFFIX.length());
                        FileTime modified = Files.getLastModifiedTime(file);
                        result.add(new TempFile(tenantDir.getFileName().toString(), artifactId,
                                file, modified.toInstant()));
                    }
                }
            }
        }
        return result;
    }

    private Path tempPath(String tenantId, String artifactId) {
        return root.resolve(segment(tenantId)).resolve(segment(artifactId) + TEMP_SUFFIX);
    }

    private String segment(String value) {
        if (value == null || !SAFE_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid artifact path segment: " + value);
        }
        return value;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不阻塞主流程，GC 会再次兜底。
        }
    }
}
