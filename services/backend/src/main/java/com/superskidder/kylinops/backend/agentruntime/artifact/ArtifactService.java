package com.superskidder.kylinops.backend.agentruntime.artifact;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Artifact 写入编排：WRITING 元数据 → 临时文件流式落盘 + SM3 → 摘要校验 →
 * (tenant_id, sm3_hex) 去重 → 原子 rename 发布。任一步失败置 FAILED 并清理临时文件。
 */
@Service
public class ArtifactService {

    public static final String STATUS_WRITING = "WRITING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final ArtifactRepository repository;
    private final ArtifactStorage storage;

    public ArtifactService(ArtifactRepository repository, ArtifactStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public WriteSession beginWrite(String tenantId, String agentInstanceId, String name, String contentType) {
        requireTenant(tenantId);
        Instant now = Instant.now();
        ArtifactRecord draft = new ArtifactRecord(newArtifactId(), tenantId.trim(),
                blankToNull(agentInstanceId), safeName(name), normalizeContentType(contentType),
                0, null, STATUS_WRITING, null, now, null);
        repository.insertWriting(draft);
        try {
            StreamingDigest digest = new StreamingDigest();
            OutputStream temp = storage.newTempStream(tenantId.trim(), draft.id());
            return new WriteSession(draft, new DigestingOutput(temp, digest), digest);
        } catch (IOException | RuntimeException exception) {
            repository.markFailed(tenantId.trim(), draft.id());
            storage.deleteTemp(tenantId.trim(), draft.id());
            throw new IllegalStateException("cannot open artifact temp file", exception);
        }
    }

    /**
     * 收尾发布。{@code expectedSm3Hex} 非空时校验流式摘要，不匹配即拒绝（篡改检测）。
     * 相同内容已发布时复用已有记录并删除本次临时文件。
     */
    public ArtifactRecord finishWrite(WriteSession session, String expectedSm3Hex) {
        try {
            session.output().close();
        } catch (IOException exception) {
            failWrite(session, "close temp file failed");
            throw new IllegalStateException("cannot finish artifact write", exception);
        }
        String sm3Hex = session.digest().hex();
        if (expectedSm3Hex != null && !expectedSm3Hex.isBlank()
                && !expectedSm3Hex.trim().equalsIgnoreCase(sm3Hex)) {
            failWrite(session, "sm3 mismatch");
            throw new ArtifactDigestMismatchException(session.draft().id(), expectedSm3Hex.trim(), sm3Hex);
        }

        String tenantId = session.draft().tenantId();
        Optional<ArtifactRecord> existing = repository.findPublishedByDigest(tenantId, sm3Hex);
        if (existing.isPresent()) {
            storage.deleteTemp(tenantId, session.draft().id());
            repository.delete(tenantId, session.draft().id());
            return existing.get();
        }

        long sizeBytes;
        try {
            sizeBytes = storage.publish(tenantId, session.draft().id());
        } catch (IOException exception) {
            failWrite(session, "publish artifact failed");
            throw new IllegalStateException("cannot publish artifact", exception);
        }
        String storagePath = tenantId + "/" + session.draft().id();
        try {
            int updated = repository.publish(tenantId, session.draft().id(), sizeBytes, sm3Hex, storagePath);
            if (updated == 0) {
                storage.deletePublished(tenantId, session.draft().id());
                throw new IllegalStateException("artifact left WRITING state concurrently: " + session.draft().id());
            }
        } catch (DataAccessException duplicate) {
            // 并发竞争：另一条相同摘要的记录先发布了，收敛为去重。
            storage.deletePublished(tenantId, session.draft().id());
            repository.delete(tenantId, session.draft().id());
            return repository.findPublishedByDigest(tenantId, sm3Hex)
                    .orElseThrow(() -> duplicate);
        }
        return new ArtifactRecord(session.draft().id(), tenantId, session.draft().agentInstanceId(),
                session.draft().name(), session.draft().contentType(), sizeBytes, sm3Hex,
                STATUS_PUBLISHED, storagePath, session.draft().createdAt(), Instant.now());
    }

    /** 失败路径：状态置 FAILED、清理临时文件，尽力而为不抛异常。 */
    public void failWrite(WriteSession session, String reason) {
        try {
            session.output().close();
        } catch (IOException ignored) {
            // 收尾失败不影响 FAILED 落库与清理。
        }
        repository.markFailed(session.draft().tenantId(), session.draft().id());
        storage.deleteTemp(session.draft().tenantId(), session.draft().id());
    }

    public Optional<ArtifactRecord> findPublished(String tenantId, String artifactId) {
        requireTenant(tenantId);
        return repository.findById(tenantId.trim(), artifactId)
                .filter(record -> STATUS_PUBLISHED.equals(record.status()));
    }

    private String newArtifactId() {
        return "art-" + UUID.randomUUID().toString().replace("-", "");
    }

    private void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant_id is required");
        }
    }

    private String safeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "artifact";
        }
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static final class WriteSession {
        private final ArtifactRecord draft;
        private final OutputStream output;
        private final StreamingDigest digest;

        private WriteSession(ArtifactRecord draft, OutputStream output, StreamingDigest digest) {
            this.draft = draft;
            this.output = output;
            this.digest = digest;
        }

        public ArtifactRecord draft() {
            return draft;
        }

        public OutputStream output() {
            return output;
        }

        public StreamingDigest digest() {
            return digest;
        }
    }

    /** 同时写入临时文件与摘要流的扇出流。 */
    private static final class DigestingOutput extends OutputStream {
        private final OutputStream target;
        private final StreamingDigest digest;

        private DigestingOutput(OutputStream target, StreamingDigest digest) {
            this.target = target;
            this.digest = digest;
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
            digest.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            digest.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
            digest.close();
        }
    }
}
