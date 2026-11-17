package com.superskidder.kylinops.backend.audit;

import com.superskidder.kylinops.backend.security.RequestContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class PlatformAuditInterceptor implements HandlerInterceptor {

    private static final Set<String> IGNORED_PREFIXES = Set.of(
            "/api/v1/auth/me"
    );

    private final AuditService auditService;

    public PlatformAuditInterceptor(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!shouldAudit(request, response)) {
            return;
        }
        RequestContextHolder.optional().ifPresent(context -> auditService.record(
                context.user().currentOrganizationId(),
                context.user().username(),
                action(request, response),
                target(request)
        ));
    }

    private boolean shouldAudit(HttpServletRequest request, HttpServletResponse response) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/v1/")) {
            return false;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return false;
        }
        for (String prefix : IGNORED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private String action(HttpServletRequest request, HttpServletResponse response) {
        String action = request.getMethod() + " " + request.getRequestURI();
        if (response.getStatus() >= 400) {
            return action + " -> " + response.getStatus();
        }
        return action;
    }

    private String target(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return request.getRequestURI();
        }
        return request.getRequestURI() + "?" + query;
    }
}
