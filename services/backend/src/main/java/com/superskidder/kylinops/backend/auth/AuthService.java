package com.superskidder.kylinops.backend.auth;

import com.superskidder.kylinops.backend.iam.IdentityService;
import com.superskidder.kylinops.backend.audit.AuditService;
import com.superskidder.kylinops.backend.crypto.Sm3;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {

    private final IdentityService identityService;
    private final PasswordHasher passwordHasher;
    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final Duration sessionLifetime;
    private final int maxFailedLoginAttempts;
    private final Duration failedLoginLockDuration;
    private final String sessionCookieName;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, FailedLoginState> failedLogins = new ConcurrentHashMap<>();

    public AuthService(
            IdentityService identityService,
            PasswordHasher passwordHasher,
            JdbcTemplate jdbcTemplate,
            AuditService auditService,
            @Value("${app.auth.session-hours:12}") long sessionHours,
            @Value("${app.auth.max-failed-login-attempts:5}") int maxFailedLoginAttempts,
            @Value("${app.auth.failed-login-lock-minutes:10}") long failedLoginLockMinutes,
            @Value("${app.auth.cookie-name:kylin_session}") String sessionCookieName
    ) {
        this.identityService = identityService;
        this.passwordHasher = passwordHasher;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.sessionLifetime = Duration.ofHours(sessionHours);
        this.maxFailedLoginAttempts = Math.max(1, maxFailedLoginAttempts);
        this.failedLoginLockDuration = Duration.ofMinutes(Math.max(1, failedLoginLockMinutes));
        this.sessionCookieName = sessionCookieName == null || sessionCookieName.isBlank()
                ? "kylin_session" : sessionCookieName.trim();
    }

    public LoginResponse login(LoginRequest request) {
        return login(request, null);
    }

    public LoginResponse login(LoginRequest request, String sourceIp) {
        String username = request.username().trim();
        String ip = sourceIp == null || sourceIp.isBlank() ? "-" : sourceIp.trim();
        assertLoginNotLocked(username, ip);
        Optional<IdentityService.UserAccount> accountOptional = identityService.findUserByUsername(username);
        if (accountOptional.isEmpty()) {
            recordFailedLogin(username, ip);
            throw new ResponseStatusException(UNAUTHORIZED, "用户名或密码错误");
        }
        IdentityService.UserAccount account = accountOptional.get();
        if (!account.enabled()) {
            throw new ResponseStatusException(UNAUTHORIZED, "用户已被禁用");
        }
        if (!passwordHasher.matches(request.password(), account.password())) {
            recordFailedLogin(username, ip);
            auditLoginFailure(account, "BAD_CREDENTIALS");
            throw new ResponseStatusException(UNAUTHORIZED, "用户名或密码错误");
        }

        clearFailedLogin(username, ip);
        AuthenticatedUser user = identityService.buildAuthenticatedUser(account.id(), null);
        String token = generateSessionToken();
        storeSession(new SessionPrincipal(token, account.id(), user.currentOrganizationId()), Instant.now().plus(sessionLifetime));
        auditService.record(user.currentOrganizationId(), user.username(), "POST /api/v1/auth/login", "/api/v1/auth/login");
        CurrentUserResponse currentUser = identityService.buildCurrentUserResponse(user);
        return new LoginResponse(token, currentUser, user.mustChangePassword());
    }

    public void changePassword(String token, ChangePasswordRequest request) {
        AuthenticatedUser user = requireAuthenticated(token);
        identityService.changeOwnPassword(user.userId(), request.currentPassword(), request.newPassword());
        auditService.record(user.currentOrganizationId(), user.username(),
                "POST /api/v1/auth/change-password", "/api/v1/auth/change-password");
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            jdbcTemplate.update("delete from app_user_session where token = ?", hashSessionToken(token));
        }
    }

    public CurrentUserResponse currentUser(String token) {
        return identityService.buildCurrentUserResponse(requireAuthenticated(token));
    }

    public CurrentUserResponse switchOrganization(String token, String organizationId) {
        SessionState sessionState = requireActiveSession(token);
        if (!identityService.canAccessOrganization(sessionState.principal().userId(), organizationId)) {
            throw new ResponseStatusException(BAD_REQUEST, "当前用户不能切换到指定组织");
        }

        SessionPrincipal principal = new SessionPrincipal(token, sessionState.principal().userId(), organizationId);
        storeSession(principal, Instant.now().plus(sessionLifetime));
        return identityService.buildCurrentUserResponse(requireAuthenticated(token));
    }

    public AuthenticatedUser requireAuthenticated(String token) {
        SessionState sessionState = requireActiveSession(token);
        AuthenticatedUser user = identityService.buildAuthenticatedUser(
                sessionState.principal().userId(),
                sessionState.principal().currentOrganizationId());
        if (!user.enabled()) {
            jdbcTemplate.update("delete from app_user_session where token = ?", hashSessionToken(token));
            throw new ResponseStatusException(UNAUTHORIZED, "用户已被禁用");
        }
        return user;
    }

    public String resolveBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7).trim();
    }

    public String resolveRequestToken(HttpServletRequest request) {
        String bearer = resolveBearerToken(request.getHeader("Authorization"));
        if (bearer != null && !bearer.isBlank()) {
            return bearer;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (sessionCookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    public void writeSessionCookie(HttpServletResponse response, String token, boolean secure) {
        Cookie cookie = new Cookie(sessionCookieName, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge((int) Math.min(Integer.MAX_VALUE, sessionLifetime.toSeconds()));
        cookie.setAttribute("SameSite", "Lax");
        cookie.setSecure(secure);
        response.addCookie(cookie);
    }

    public void clearSessionCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(sessionCookieName, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private SessionState requireActiveSession(String token) {
        String tokenHash = hashSessionToken(token);
        SessionState sessionState = jdbcTemplate.query(
                        """
                        select token, user_id, current_organization_id, expires_at
                        from app_user_session
                        where token = ?
                        """,
                        (resultSet, rowNum) -> new SessionState(
                                new SessionPrincipal(
                                        resultSet.getString("token"),
                                        resultSet.getString("user_id"),
                                        resultSet.getString("current_organization_id")
                                ),
                                resultSet.getTimestamp("expires_at").toInstant()
                        ),
                        tokenHash)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "登录状态已失效，请重新登录"));
        if (sessionState.expiresAt().isBefore(Instant.now())) {
            jdbcTemplate.update("delete from app_user_session where token = ?", tokenHash);
            throw new ResponseStatusException(UNAUTHORIZED, "登录状态已过期，请重新登录");
        }
        return sessionState;
    }

    private void storeSession(SessionPrincipal principal, Instant expiresAt) {
        String tokenHash = hashSessionToken(principal.token());
        jdbcTemplate.update("delete from app_user_session where token = ?", tokenHash);
        jdbcTemplate.update(
                """
                insert into app_user_session (token, user_id, current_organization_id, expires_at)
                values (?, ?, ?, ?)
                """,
                tokenHash,
                principal.userId(),
                principal.currentOrganizationId(),
                java.sql.Timestamp.from(expiresAt)
        );
        jdbcTemplate.update("delete from app_user_session where expires_at <= ?", java.sql.Timestamp.from(Instant.now()));
    }

    private String generateSessionToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void assertLoginNotLocked(String username, String sourceIp) {
        Instant now = Instant.now();
        String key = lockKey(username, sourceIp);
        FailedLoginState state = failedLogins.get(key);
        if (state == null || state.lockedUntil() == null) {
            return;
        }
        if (state.lockedUntil().isAfter(now)) {
            identityService.findUserByUsername(username)
                    .ifPresent(account -> auditLoginFailure(account, "LOCKED"));
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "登录失败次数过多，请稍后再试");
        }
        failedLogins.remove(key);
    }

    private void auditLoginFailure(IdentityService.UserAccount account, String reason) {
        identityService.findPrimaryOrganizationId(account.id()).ifPresent(organizationId -> auditService.record(
                organizationId,
                account.username(),
                "POST /api/v1/auth/login -> " + reason,
                "/api/v1/auth/login"
        ));
    }

    private void recordFailedLogin(String username, String sourceIp) {
        failedLogins.compute(lockKey(username, sourceIp), (ignored, current) -> {
            int attempts = current == null ? 1 : current.attempts() + 1;
            Instant lockedUntil = attempts >= maxFailedLoginAttempts
                    ? Instant.now().plus(failedLoginLockDuration)
                    : null;
            return new FailedLoginState(attempts, lockedUntil);
        });
    }

    private void clearFailedLogin(String username, String sourceIp) {
        failedLogins.remove(lockKey(username, sourceIp));
    }

    private String lockKey(String username, String sourceIp) {
        String user = username.trim().toLowerCase(Locale.ROOT);
        String ip = sourceIp == null || sourceIp.isBlank() ? "-" : sourceIp.trim();
        return "user:" + user + "|ip:" + ip;
    }

    private String hashSessionToken(String token) {
        return Sm3.hex(token);
    }

    private record SessionState(SessionPrincipal principal, Instant expiresAt) {
    }

    private record FailedLoginState(int attempts, Instant lockedUntil) {
    }
}
