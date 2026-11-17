package com.superskidder.kylinops.backend.agentruntime.artifact;

import com.superskidder.kylinops.backend.crypto.Sm3;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 带 HMAC-SM3 签名的 artifact 下载链接：签发时附带过期时间（默认 15 分钟），
 * 下载放行前必须验签 + 过期检查。签名密钥来自配置/环境变量，未配置时拒绝签发与放行。
 */
@Component
public class ArtifactDownloadService {

    private final byte[] signingKey;
    private final Duration ttl;
    private final Clock clock;
    private final ArtifactRepository repository;

    @Autowired
    public ArtifactDownloadService(
            @Value("${app.artifact.download.signing-key:}") String signingKey,
            @Value("${app.artifact.download.ttl-seconds:900}") long ttlSeconds,
            ArtifactRepository repository
    ) {
        this(signingKey, ttlSeconds, Clock.systemUTC(), repository);
    }

    ArtifactDownloadService(String signingKey, long ttlSeconds, Clock clock, ArtifactRepository repository) {
        this.signingKey = signingKey == null ? new byte[0] : signingKey.getBytes(StandardCharsets.UTF_8);
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.clock = clock;
        this.repository = repository;
    }

    public SignedUrl issue(String tenantId, String artifactId) {
        requireConfigured();
        long expiresAtEpochSecond = clock.instant().plus(ttl).getEpochSecond();
        String signature = sign(tenantId, artifactId, expiresAtEpochSecond);
        return new SignedUrl(tenantId, artifactId, expiresAtEpochSecond, signature);
    }

    /** 验签 + 过期检查，任一不通过即拒绝。 */
    public void verify(String tenantId, String artifactId, long expiresAtEpochSecond, String signature) {
        requireConfigured();
        if (clock.instant().getEpochSecond() >= expiresAtEpochSecond) {
            throw new IllegalArgumentException("artifact download link expired");
        }
        String expected = sign(tenantId, artifactId, expiresAtEpochSecond);
        String provided = signature == null ? "" : signature.trim();
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("invalid artifact download signature");
        }
    }

    /** 验签通过后返回已发布的 artifact 元数据，作为下载放行依据。 */
    public ArtifactRecord resolve(String tenantId, String artifactId, long expiresAtEpochSecond, String signature) {
        verify(tenantId, artifactId, expiresAtEpochSecond, signature);
        return repository.findById(tenantId, artifactId)
                .filter(record -> ArtifactService.STATUS_PUBLISHED.equals(record.status()))
                .orElseThrow(() -> new IllegalArgumentException("artifact not found or not published: " + artifactId));
    }

    private String sign(String tenantId, String artifactId, long expiresAtEpochSecond) {
        return Sm3.hmacHex(signingKey, tenantId + ":" + artifactId + ":" + expiresAtEpochSecond);
    }

    private void requireConfigured() {
        if (signingKey.length == 0) {
            throw new IllegalStateException("artifact download signing key is not configured");
        }
    }

    public record SignedUrl(String tenantId, String artifactId, long expiresAtEpochSecond, String signature) {

        /** 相对下载路径，前缀由 HTTP 层决定。 */
        public String path(String urlPrefix) {
            return urlPrefix + "/" + artifactId + "/download?tenant_id=" + tenantId
                    + "&expires=" + expiresAtEpochSecond + "&signature=" + signature;
        }
    }
}
