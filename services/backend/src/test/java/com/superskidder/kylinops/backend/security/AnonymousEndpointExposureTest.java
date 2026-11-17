package com.superskidder.kylinops.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.auth.AuthService;
import com.superskidder.kylinops.backend.auth.LoginRequest;
import com.superskidder.kylinops.backend.iam.AvatarStorageService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 匿名可达面的回归护栏（真实容器 + 真实 HTTP，沿用 ReadOnlyAgentRuntimeTest 的方式；
 * Spring Boot 4 的 MockMvc 自动配置不在本模块依赖内，故直接打真实端口）。
 *
 * <p>此前 {@link AuthTokenInterceptor} 对 {@code /actuator} 前缀无条件放行
 * （连 {@code /actuator/prometheus} 的全量指标一起匿名暴露），且
 * {@link WebMvcConfig} 只把鉴权拦截器挂在 {@code /api/**} 与 {@code /actuator/**}，
 * {@code /uploads/**}（头像、知识库 PDF 原件）完全不在鉴权链路。
 *
 * <p>本类锁定收敛后的口径：存活/就绪探针匿名，其余 actuator 端点与全部上传件需认证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=test",
        "app.host-agent.grpc.enabled=false",
        "app.agent-runtime.enabled=false"
})
class AnonymousEndpointExposureTest {

    private static final String AVATAR_CONTENT = "fake-avatar-bytes";

    @LocalServerPort
    int port;

    @Autowired
    AuthService authService;

    @Autowired
    AvatarStorageService avatarStorageService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String avatarPath;

    @BeforeEach
    void seedAvatarFile() throws IOException {
        Path directory = avatarStorageService.directory();
        Files.createDirectories(directory);
        Path file = directory.resolve("exposure-probe.png");
        Files.writeString(file, AVATAR_CONTENT, StandardCharsets.UTF_8);
        avatarPath = avatarStorageService.urlPrefix() + "/" + file.getFileName();
    }

    @Test
    void livenessProbeStaysAnonymous() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void prometheusMetricsRequireToken() throws Exception {
        assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(401);

        HttpResponse<String> authorized = get("/actuator/prometheus", token());
        assertThat(authorized.statusCode()).isEqualTo(200);
        assertThat(authorized.body()).contains("jvm_");
    }

    @Test
    void uploadedAssetsRequireToken() throws Exception {
        assertThat(get(avatarPath, null).statusCode()).isEqualTo(401);

        HttpResponse<String> authorized = get(avatarPath, token());
        assertThat(authorized.statusCode()).isEqualTo(200);
        assertThat(authorized.body()).isEqualTo(AVATAR_CONTENT);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token() {
        return authService.login(new LoginRequest("admin", "admin123")).token();
    }
}
