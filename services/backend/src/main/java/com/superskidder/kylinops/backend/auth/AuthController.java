package com.superskidder.kylinops.backend.auth;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import com.superskidder.kylinops.backend.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AccessControlService accessControlService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, AccessControlService accessControlService,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.accessControlService = accessControlService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        LoginResponse response = authService.login(request, clientIpResolver.resolve(httpRequest));
        authService.writeSessionCookie(httpResponse, response.token(), httpRequest.isSecure());
        return ApiResponse.success(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse httpResponse) {
        authService.logout(accessControlService.currentToken());
        authService.clearSessionCookie(httpResponse);
        return ApiResponse.success(null);
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(accessControlService.currentToken(), request);
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> currentUser() {
        return ApiResponse.success(authService.currentUser(accessControlService.currentToken()));
    }

    @PostMapping("/switch-organization")
    public ApiResponse<CurrentUserResponse> switchOrganization(@Valid @RequestBody SwitchOrganizationRequest request) {
        accessControlService.requirePermission("organization:switch");
        return ApiResponse.success(authService.switchOrganization(accessControlService.currentToken(), request.organizationId()));
    }
}
