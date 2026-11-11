package com.superskidder.kylinops.backend.agentruntime;

import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactDownloadService;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactRecord;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactService;
import com.superskidder.kylinops.backend.agentruntime.artifact.ArtifactStorage;
import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Artifact 只读查询与签名下载端点（Phase 6.5 时间线 UI 配套）。
 *
 * agent_artifact 此前只有写入链路（ArtifactService）与签名工具（ArtifactDownloadService），
 * 没有任何 REST 查询入口。本控制器只做只读补全：
 *   GET  /                      当前组织的 artifact 列表
 *   GET  /{artifactId}/download-url  签发 HMAC-SM3 限时下载链接
 *   GET  /{artifactId}/download      验签 + 过期检查通过后流式下载
 */
@RestController
@RequestMapping("/api/v2/agent-runtime/artifacts")
public class AgentArtifactQueryController {

    /** download-url 返回的相对路径前缀，与 {@link #download} 的映射保持一致。 */
    private static final String DOWNLOAD_URL_PREFIX = "/api/v2/agent-runtime/artifacts";

    private final JdbcTemplate jdbcTemplate;
    private final AccessControlService accessControl;
    private final ArtifactDownloadService downloadService;
    private final ArtifactStorage artifactStorage;

    public AgentArtifactQueryController(JdbcTemplate jdbcTemplate, AccessControlService accessControl,
                                        ArtifactDownloadService downloadService, ArtifactStorage artifactStorage) {
        this.jdbcTemplate = jdbcTemplate;
        this.accessControl = accessControl;
        this.downloadService = downloadService;
        this.artifactStorage = artifactStorage;
    }

    @GetMapping
    public ApiResponse<List<ArtifactItem>> list(@RequestParam(required = false, defaultValue = "50") int limit) {
        accessControl.requirePermission("agent:read");
        String tenantId = accessControl.currentOrganizationId();
        List<ArtifactItem> rows = jdbcTemplate.query("""
                select id, name, content_type, size_bytes, sm3_hex, status, agent_instance_id,
                       created_at, published_at
                from agent_artifact where tenant_id = ?
                order by created_at desc limit ?
                """, (rs, n) -> new ArtifactItem(rs.getString("id"), rs.getString("name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sm3_hex"),
                rs.getString("status"), rs.getString("agent_instance_id"),
                text(rs.getTimestamp("created_at")), text(rs.getTimestamp("published_at"))),
                tenantId, Math.max(1, Math.min(limit, 200)));
        return ApiResponse.success(rows);
    }

    @GetMapping("/{artifactId}/download-url")
    public ApiResponse<DownloadUrlItem> downloadUrl(@PathVariable String artifactId) {
        accessControl.requirePermission("agent:read");
        String tenantId = accessControl.currentOrganizationId();
        requirePublished(tenantId, artifactId);
        try {
            ArtifactDownloadService.SignedUrl signed = downloadService.issue(tenantId, artifactId);
            return ApiResponse.success(
                    new DownloadUrlItem(signed.path(DOWNLOAD_URL_PREFIX), signed.expiresAtEpochSecond()));
        } catch (IllegalStateException exception) {
            // 未配置签名密钥：明确告知前端下载能力未启用，而不是吐 500。
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "Artifact 下载未启用: " + exception.getMessage());
        }
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<FileSystemResource> download(
            @PathVariable String artifactId,
            @RequestParam("tenant_id") String tenantId,
            @RequestParam("expires") long expiresAtEpochSecond,
            @RequestParam("signature") String signature) {
        accessControl.requirePermission("agent:read");
        ArtifactRecord record;
        try {
            record = downloadService.resolve(tenantId, artifactId, expiresAtEpochSecond, signature);
        } catch (IllegalArgumentException exception) {
            throw new AppException(HttpStatus.FORBIDDEN, exception.getMessage());
        }
        Path path = artifactStorage.publishedPath(tenantId, artifactId);
        if (!Files.isRegularFile(path)) {
            throw new AppException(HttpStatus.NOT_FOUND, "Artifact 文件不存在: " + artifactId);
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(record.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(record.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(record.sizeBytes())
                .body(new FileSystemResource(path));
    }

    private void requirePublished(String tenantId, String artifactId) {
        boolean published = jdbcTemplate.query("""
                select status from agent_artifact where tenant_id = ? and id = ?
                """, (rs, n) -> rs.getString("status"), tenantId, artifactId)
                .stream().findFirst()
                .map(ArtifactService.STATUS_PUBLISHED::equals)
                .orElse(false);
        if (!published) {
            throw new AppException(HttpStatus.NOT_FOUND, "Artifact 不存在或未发布: " + artifactId);
        }
    }

    private String text(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public record ArtifactItem(
            String id, String name, String contentType, long sizeBytes, String sm3Hex,
            String status, String agentInstanceId, String createdAt, String publishedAt
    ) {}

    public record DownloadUrlItem(String downloadUrl, long expiresAtEpochSecond) {}
}
