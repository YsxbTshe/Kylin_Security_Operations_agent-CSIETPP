package com.superskidder.kylinops.backend.security;

import com.superskidder.kylinops.backend.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class AuthTokenInterceptor implements HandlerInterceptor {

    private static final Logger securityLog = LoggerFactory.getLogger("audit.security");

    private final AuthService authService;
    private final ClientIpResolver clientIpResolver;
    private final boolean enforcePasswordChange;

    public AuthTokenInterceptor(
            AuthService authService,
            ClientIpResolver clientIpResolver,
            @Value("${app.auth.enforce-password-change:true}") boolean enforcePasswordChange
    ) {
        this.authService = authService;
        this.clientIpResolver = clientIpResolver;
        this.enforcePasswordChange = enforcePasswordChange;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String requestUri = request.getRequestURI();
        if (isAnonymousSystemEndpoint(requestUri)
                || requestUri.startsWith("/api/v1/auth/login")
                || isAnonymousActuatorEndpoint(requestUri)
                || requestUri.startsWith("/error")) {
            return true;
        }

        String token = authService.resolveRequestToken(request);
        if (token == null) {
            logAnonymousReject(request, "MISSING_TOKEN");
            throw new ResponseStatusException(UNAUTHORIZED, "缺少认证令牌");
        }

        try {
            RequestContextHolder.set(new RequestContext(token, authService.requireAuthenticated(token)));
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == UNAUTHORIZED.value()) {
                logAnonymousReject(request, "INVALID_TOKEN");
            }
            throw exception;
        }
        if (enforcePasswordChange
                && RequestContextHolder.required().user().mustChangePassword()
                && !isPasswordChangeAllowed(requestUri)) {
            throw new ResponseStatusException(FORBIDDEN, "必须先修改初始密码");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContextHolder.clear();
    }

    private boolean isAnonymousSystemEndpoint(String requestUri) {
        return "/api/v1/system/health".equals(requestUri) || "/api/v1/system/screen".equals(requestUri);
    }

    /**
     * actuator 只对存活/就绪探针匿名放行。
     *
     * <p>此前是 {@code startsWith("/actuator")} 无条件放行，等于把
     * {@code /actuator/prometheus} 的全量业务指标（主机数、告警数、Agent 运行、
     * 端点耗时分布等）匿名暴露在控制面端口上。除 health/info 外一律需要认证。
     *
     * <p>Prometheus 抓取需要携带凭据：{@code deploy/observability/prometheus.yml} 与
     * {@code deploy/observability-155/prometheus.yml} 的 backend job 需补
     * {@code authorization: type: Bearer / credentials: <token>}（或 Cookie 头）。
     * 仓库内暂无既有抓取凭据约定，故这里只做最小改动：health/info 匿名 + 其余需 token，
     * 凭据下发由部署侧决定。
     */
    private boolean isAnonymousActuatorEndpoint(String requestUri) {
        return "/actuator/health".equals(requestUri)
                || requestUri.startsWith("/actuator/health/")
                || "/actuator/info".equals(requestUri);
    }

    private boolean isPasswordChangeAllowed(String requestUri) {
        return requestUri.startsWith("/api/v1/auth/me")
                || requestUri.startsWith("/api/v1/auth/logout")
                || requestUri.startsWith("/api/v1/auth/change-password");
    }

    private void logAnonymousReject(HttpServletRequest request, String reason) {
        securityLog.warn(
                "auth_reject reason={} method={} uri={} sourceIp={} userAgent=\"{}\"",
                reason,
                request.getMethod(),
                request.getRequestURI(),
                clientIpResolver.resolve(request),
                sanitize(request.getHeader("User-Agent"))
        );
    }

    private String sanitize(String value) {
        if (value == null) {
            return "-";
        }
        String normalized = value.trim().replaceAll("[\r\n\t]", " ");
        return normalized.length() > 256 ? normalized.substring(0, 256) : normalized;
    }
}
