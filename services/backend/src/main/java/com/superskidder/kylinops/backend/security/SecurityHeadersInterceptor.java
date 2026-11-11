package com.superskidder.kylinops.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class SecurityHeadersInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        // /uploads 下的知识库原件（PDF）需要在本站页面内嵌预览，DENY 会让浏览器直接拒绝渲染。
        // 放宽到 SAMEORIGIN：仍然阻止被第三方站点内嵌（点击劫持防护不变），只允许本站自己嵌。
        // API 与其他路径保持 DENY。
        response.setHeader("X-Frame-Options",
                request.getRequestURI().startsWith("/uploads/") ? "SAMEORIGIN" : "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=(), payment=(), usb=()");
        // API 响应的 CSP 防止被当作 HTML 执行；SPA 页面另有 index.html meta。
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        return true;
    }
}
