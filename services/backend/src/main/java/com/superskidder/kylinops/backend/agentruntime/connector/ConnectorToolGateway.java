package com.superskidder.kylinops.backend.agentruntime.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.security.CanonicalJson;
import com.superskidder.kylinops.backend.agentruntime.security.SecretResolver;
import com.superskidder.kylinops.backend.agentruntime.security.SecretValue;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一 Connector 执行入口：解析预装定义 → SecretResolver 解析凭据 → JDK HttpClient 执行 →
 * 结构化结果 + connector_invocation 审计。
 *
 * 安全口径：
 * - 白名单封闭，未知 connector_id 拒绝（CONNECTOR_UNKNOWN）；
 * - 目标 = 配置化 endpoint 基址 + 参数化 path/query；参数经结构化 URI 构造并事后断言
 *   host/scheme/port 未被改变，无法注入到其他主机（防 SSRF）；
 * - 禁用重定向；请求体大小上限；响应体截断 64KB；
 * - 凭据仅出现在 Authorization 头，日志/结果/异常中以 *** 脱敏，绝不进入模型上下文。
 */
@Component
public class ConnectorToolGateway {

    public static final String SECRET_MASK = "***";
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_PATH_LENGTH = 2048;
    private static final Pattern QUERY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final Logger log = LoggerFactory.getLogger(ConnectorToolGateway.class);

    private final ConnectorCatalog catalog;
    private final SecretResolver secretResolver;
    private final ConnectorInvocationAuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final int maxRequestBodyBytes;

    public ConnectorToolGateway(
            ConnectorCatalog catalog,
            SecretResolver secretResolver,
            ConnectorInvocationAuditRepository auditRepository,
            ObjectMapper objectMapper,
            @Value("${app.agent-runtime.connector.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${app.agent-runtime.connector.read-timeout-ms:15000}") long readTimeoutMs,
            @Value("${app.agent-runtime.connector.max-request-body-bytes:262144}") long maxRequestBodyBytes
    ) {
        this.catalog = catalog;
        this.secretResolver = secretResolver;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.readTimeout = Duration.ofMillis(Math.max(1, readTimeoutMs));
        this.maxRequestBodyBytes = (int) Math.max(1, Math.min(maxRequestBodyBytes, Integer.MAX_VALUE));
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
                .build();
    }

    public record Invocation(
            String tenantId,
            String runId,
            String connectorId,
            String path,
            Map<String, String> query,
            Map<String, Object> body
    ) {}

    public Map<String, Object> execute(Invocation invocation) {
        ConnectorDefinition definition = catalog.find(invocation.connectorId())
                .orElseThrow(() -> new ConnectorException("CONNECTOR_UNKNOWN",
                        "unknown connector: " + invocation.connectorId(), false));
        if (!definition.configured()) {
            throw new ConnectorException("CONNECTOR_NOT_CONFIGURED",
                    "connector endpoint is not configured: " + definition.id(), false);
        }

        long startedNanos = System.nanoTime();
        URI target = null;
        Integer responseStatus = null;
        String requestDigest = null;
        try {
            target = buildTarget(definition, invocation.path(), invocation.query());
            byte[] requestBody = buildRequestBody(definition, invocation.body());
            requestDigest = requestDigest(definition.method(), target, invocation.query(), requestBody);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target).timeout(readTimeout);
            boolean authorized = applyCredential(definition, requestBuilder);
            requestBuilder.method(definition.method(),
                    requestBody == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(requestBody));

            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ConnectorException("CONNECTOR_INTERRUPTED", "connector request interrupted", false);
            } catch (IOException ioException) {
                // 异常信息不带目标细节，避免凭据/内网地址外泄到模型上下文。
                throw new ConnectorException("CONNECTOR_IO_FAILED",
                        "connector request failed: " + ioException.getClass().getSimpleName(), true);
            }
            responseStatus = response.statusCode();
            byte[] body;
            try {
                body = readCapped(response);
            } catch (IOException ioException) {
                throw new ConnectorException("CONNECTOR_IO_FAILED",
                        "connector response read failed: " + ioException.getClass().getSimpleName(), true);
            }
            boolean truncated = body.length > MAX_RESPONSE_BYTES;
            byte[] kept = truncated ? Arrays.copyOf(body, MAX_RESPONSE_BYTES) : body;
            long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;

            LinkedHashMap<String, Object> structured = new LinkedHashMap<>();
            structured.put("connectorId", definition.id());
            structured.put("target", describeTarget(target));
            structured.put("status", responseStatus);
            structured.put("body", new String(kept, StandardCharsets.UTF_8));
            structured.put("truncated", truncated);
            structured.put("durationMs", durationMs);
            if (authorized) {
                structured.put("authorization", SECRET_MASK);
            }
            log.info("Connector invocation connector={} target={} status={} durationMs={}",
                    definition.id(), describeTarget(target), responseStatus, durationMs);
            return structured;
        } finally {
            long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            try {
                auditRepository.record(invocation.tenantId(), invocation.runId(), definition.id(),
                        target != null ? describeTarget(target) : definition.endpointBaseUrl(),
                        requestDigest, responseStatus, durationMs);
            } catch (RuntimeException auditFailure) {
                log.error("Connector audit insert failed connector={}", definition.id(), auditFailure);
            }
        }
    }

    /** 基址来自预装定义/配置；path/query 只能在其下追加，不能改变 host/scheme/port。 */
    static URI buildTarget(ConnectorDefinition definition, String path, Map<String, String> query) {
        URI base;
        try {
            base = URI.create(definition.endpointBaseUrl().trim());
        } catch (IllegalArgumentException exception) {
            throw new ConnectorException("CONNECTOR_ENDPOINT_INVALID",
                    "connector endpoint base URL is invalid", false);
        }
        String scheme = base.getScheme();
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || base.getHost() == null) {
            throw new ConnectorException("CONNECTOR_ENDPOINT_INVALID",
                    "connector endpoint base URL must be an absolute http(s) URL", false);
        }
        validatePath(path);
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        if (basePath.endsWith("/") && path != null && path.startsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String fullPath = basePath + (path == null ? "" : path);
        String queryString = encodeQuery(query);
        URI target;
        try {
            target = new URI(scheme, null, base.getHost(), base.getPort(),
                    fullPath.isEmpty() ? "/" : fullPath, queryString, null);
        } catch (URISyntaxException exception) {
            throw new ConnectorException("CONNECTOR_TARGET_INVALID",
                    "connector target cannot be constructed from arguments", false);
        }
        if (!scheme.equals(target.getScheme()) || !base.getHost().equals(target.getHost())
                || base.getPort() != target.getPort()) {
            throw new ConnectorException("CONNECTOR_TARGET_TAMPERED",
                    "connector arguments attempted to change endpoint host or scheme", false);
        }
        return target;
    }

    static void validatePath(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        if (path.length() > MAX_PATH_LENGTH) {
            throw new ConnectorException("CONNECTOR_PATH_INVALID",
                    "connector path exceeds " + MAX_PATH_LENGTH + " characters", false);
        }
        if (!path.startsWith("/")) {
            throw new ConnectorException("CONNECTOR_PATH_INVALID", "connector path must start with '/'", false);
        }
        if (path.contains("//") || path.contains("\\") || path.contains(":") || path.contains("#")
                || path.contains("?")) {
            throw new ConnectorException("CONNECTOR_PATH_INVALID",
                    "connector path contains forbidden characters", false);
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new ConnectorException("CONNECTOR_PATH_INVALID",
                        "connector path contains control characters", false);
            }
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                throw new ConnectorException("CONNECTOR_PATH_INVALID",
                        "connector path must not contain '..' segments", false);
            }
        }
    }

    static String encodeQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (!QUERY_KEY_PATTERN.matcher(key).matches()) {
                throw new ConnectorException("CONNECTOR_QUERY_INVALID",
                        "connector query key is invalid", false);
            }
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private byte[] buildRequestBody(ConnectorDefinition definition, Map<String, Object> body) {
        if ("GET".equals(definition.method())) {
            if (body != null && !body.isEmpty()) {
                throw new ConnectorException("CONNECTOR_BODY_NOT_ALLOWED",
                        "read-only connector does not accept a request body", false);
            }
            return null;
        }
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(body == null ? Map.of() : body);
            if (encoded.length > maxRequestBodyBytes) {
                throw new ConnectorException("CONNECTOR_BODY_TOO_LARGE",
                        "connector request body exceeds " + maxRequestBodyBytes + " bytes", false);
            }
            return encoded;
        } catch (ConnectorException connectorException) {
            throw connectorException;
        } catch (Exception exception) {
            throw new ConnectorException("CONNECTOR_BODY_INVALID",
                    "connector request body cannot be serialized", false);
        }
    }

    /** 凭据仅进 Authorization 头；调用方与日志永远只见 ***。 */
    private boolean applyCredential(ConnectorDefinition definition, HttpRequest.Builder requestBuilder) {
        String reference = definition.credentialReference();
        if (reference == null || reference.isBlank()) {
            return false;
        }
        try (SecretValue secret = secretResolver.resolve(reference)) {
            String token = new String(secret.copyBytes(), StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) {
                throw new ConnectorException("CONNECTOR_CREDENTIAL_EMPTY",
                        "connector credential resolved to an empty value", false);
            }
            requestBuilder.header("Authorization", "Bearer " + token);
            return true;
        } catch (ConnectorException connectorException) {
            throw connectorException;
        } catch (RuntimeException resolutionFailure) {
            throw new ConnectorException("CONNECTOR_CREDENTIAL_UNAVAILABLE",
                    "connector credential could not be resolved", false);
        }
    }

    private String requestDigest(String method, URI target, Map<String, String> query, byte[] requestBody) {
        try {
            LinkedHashMap<String, Object> material = new LinkedHashMap<>();
            material.put("method", method);
            material.put("target", describeTarget(target));
            material.put("query", query == null ? Map.of() : query);
            material.put("bodySha", requestBody == null ? "" : Sm3.hex(requestBody));
            return Sm3.hex(CanonicalJson.canonicalize(objectMapper.writeValueAsString(material)));
        } catch (Exception exception) {
            return null;
        }
    }

    private static byte[] readCapped(HttpResponse<InputStream> response) throws IOException {
        try (InputStream input = response.body()) {
            return input.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
    }

    /** scheme://host[:port]/path —— 无 userinfo、无 query、无凭据。 */
    static String describeTarget(URI target) {
        if (target == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(target.getScheme()).append("://").append(target.getHost());
        if (target.getPort() != -1) {
            builder.append(':').append(target.getPort());
        }
        if (target.getRawPath() != null && !target.getRawPath().isEmpty()) {
            builder.append(target.getRawPath());
        }
        return builder.toString();
    }
}
