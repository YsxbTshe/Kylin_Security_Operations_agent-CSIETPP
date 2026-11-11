package com.superskidder.kylinops.backend.agentruntime.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Artifact 清理：过期已发布记录（保留期可配）、卡死的 WRITING 记录、
 * 与元数据对不上的孤儿临时文件。{@link #sweep()} 定时触发，{@link #cleanup()} 可被测试直接驱动。
 */
@Component
public class ArtifactGCService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactGCService.class);

    private final ArtifactRepository repository;
    private final ArtifactStorage storage;
    private final boolean enabled;
    private final Duration retention;
    private final Duration tempMaxAge;

    public ArtifactGCService(
            ArtifactRepository repository,
            ArtifactStorage storage,
            @Value("${app.artifact.gc-enabled:true}") boolean enabled,
            @Value("${app.artifact.retention-days:30}") long retentionDays,
            @Value("${app.artifact.temp-max-age-minutes:60}") long tempMaxAgeMinutes
    ) {
        this.repository = repository;
        this.storage = storage;
        this.enabled = enabled;
        this.retention = Duration.ofDays(Math.max(1, retentionDays));
        this.tempMaxAge = Duration.ofMinutes(Math.max(1, tempMaxAgeMinutes));
    }

    @Scheduled(fixedDelayString = "${app.artifact.gc-sweep-delay-ms:3600000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            CleanupResult result = cleanup();
            if (result.expiredArtifacts() > 0 || result.staleWrites() > 0 || result.orphanTempFiles() > 0) {
                log.info("Artifact GC: {} expired, {} stale write(s), {} orphan temp file(s)",
                        result.expiredArtifacts(), result.staleWrites(), result.orphanTempFiles());
            }
        } catch (IOException | RuntimeException exception) {
            log.error("Artifact GC sweep failed", exception);
        }
    }

    public CleanupResult cleanup() throws IOException {
        Instant now = Instant.now();
        Instant retentionCutoff = now.minus(retention);
        Instant tempCutoff = now.minus(tempMaxAge);

        int expiredArtifacts = 0;
        for (ArtifactRecord record : repository.findPublishedBefore(retentionCutoff)) {
            storage.deletePublished(record.tenantId(), record.id());
            repository.delete(record.tenantId(), record.id());
            expiredArtifacts++;
        }

        int staleWrites = 0;
        for (ArtifactRecord record : repository.findWritingBefore(tempCutoff)) {
            repository.markFailed(record.tenantId(), record.id());
            storage.deleteTemp(record.tenantId(), record.id());
            staleWrites++;
        }

        int orphanTempFiles = 0;
        for (ArtifactStorage.TempFile tempFile : storage.listTempFiles()) {
            if (!isActiveWriting(tempFile, tempCutoff)) {
                Files.deleteIfExists(tempFile.path());
                orphanTempFiles++;
            }
        }
        return new CleanupResult(expiredArtifacts, staleWrites, orphanTempFiles);
    }

    private boolean isActiveWriting(ArtifactStorage.TempFile tempFile, Instant tempCutoff) {
        Optional<ArtifactRecord> record = repository.findGlobalById(tempFile.artifactId());
        return record.isPresent()
                && ArtifactService.STATUS_WRITING.equals(record.get().status())
                && record.get().tenantId().equals(tempFile.tenantId())
                && record.get().createdAt().isAfter(tempCutoff);
    }

    public record CleanupResult(int expiredArtifacts, int staleWrites, int orphanTempFiles) {
    }
}
