package com.superskidder.kylinops.backend.security;

import com.superskidder.kylinops.backend.iam.AvatarStorageService;
import com.superskidder.kylinops.backend.rag.RagAssetStorageService;
import com.superskidder.kylinops.backend.audit.PlatformAuditInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthTokenInterceptor authTokenInterceptor;
    private final PlatformAuditInterceptor platformAuditInterceptor;
    private final SecurityHeadersInterceptor securityHeadersInterceptor;
    private final AvatarStorageService avatarStorageService;
    private final RagAssetStorageService ragAssetStorageService;

    public WebMvcConfig(
            AuthTokenInterceptor authTokenInterceptor,
            PlatformAuditInterceptor platformAuditInterceptor,
            SecurityHeadersInterceptor securityHeadersInterceptor,
            AvatarStorageService avatarStorageService,
            RagAssetStorageService ragAssetStorageService
    ) {
        this.authTokenInterceptor = authTokenInterceptor;
        this.platformAuditInterceptor = platformAuditInterceptor;
        this.securityHeadersInterceptor = securityHeadersInterceptor;
        this.avatarStorageService = avatarStorageService;
        this.ragAssetStorageService = ragAssetStorageService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityHeadersInterceptor).addPathPatterns("/api/**", "/actuator/**", "/uploads/**");
        // 静态资源（头像、知识库 PDF 原件）此前完全不在鉴权链路：ResourceHandler 处理的请求
        // 同样经过 HandlerInterceptor，把上传目录挂进来即可要求 token/会话 Cookie
        // （浏览器 <img> 不带 Authorization 头，但会带会话 Cookie，AuthService 两者都认）。
        // 前缀可配（app.assets.*-url-prefix），按实际前缀补挂，避免改配置后又出现匿名口子。
        // /actuator/** 不在这里挂：它对 actuator 的 HandlerMapping 无效，改由下面的
        // MappedInterceptor Bean 统一覆盖（那个 Bean 会被所有 HandlerMapping 捡到）。
        registry.addInterceptor(authTokenInterceptor).addPathPatterns(
                "/api/**", "/uploads/**",
                avatarStorageService.urlPrefix() + "/**",
                ragAssetStorageService.urlPrefix() + "/**");
        registry.addInterceptor(platformAuditInterceptor).addPathPatterns("/api/v1/**");
    }

    /**
     * actuator 端点的鉴权必须走 MappedInterceptor **Bean**，不能只靠上面的
     * {@code addInterceptors}。
     *
     * <p>原因：{@code addInterceptors} 注册的拦截器只会被 WebMvcConfigurationSupport
     * 创建的那几个 HandlerMapping（RequestMapping / Resource / SimpleUrl）持有；actuator
     * 的 {@code WebMvcEndpointHandlerMapping} 是独立创建的，只会通过
     * {@code AbstractHandlerMapping.detectMappedInterceptors} 从容器里**按类型**捡
     * {@link MappedInterceptor} Bean。这正是此前 {@code /actuator/prometheus} 明明被
     * 挂了 {@code /actuator/**} 却依然匿名可达的根因（拦截器压根没进 actuator 的链路）。
     *
     * <p>模式限定在 {@code /actuator/**}，且已从上面的 {@code addInterceptors} 中移除该
     * 模式：两处都挂会让 actuator 下的普通 Controller（如果将来有）被拦两次。
     */
    @Bean
    public MappedInterceptor actuatorAuthTokenInterceptor() {
        return new MappedInterceptor(new String[] {"/actuator/**"}, null, authTokenInterceptor);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(avatarStorageService.urlPrefix() + "/**")
                .addResourceLocations(avatarStorageService.directory().toUri().toString());
        registry.addResourceHandler(ragAssetStorageService.urlPrefix() + "/**")
                .addResourceLocations(ragAssetStorageService.directory().toUri().toString());
    }
}
