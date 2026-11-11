package com.superskidder.kylinops.backend.iam;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final IdentityService identityService;
    private final AccessControlService accessControlService;
    private final AvatarStorageService avatarStorageService;

    public UserController(
            IdentityService identityService,
            AccessControlService accessControlService,
            AvatarStorageService avatarStorageService
    ) {
        this.identityService = identityService;
        this.accessControlService = accessControlService;
        this.avatarStorageService = avatarStorageService;
    }

    @GetMapping
    public ApiResponse<List<UserView>> listUsers() {
        accessControlService.requirePermission("user:read");
        return ApiResponse.success(identityService.listUsers(accessControlService.currentUser()));
    }

    @PostMapping
    public ApiResponse<UserView> createUser(@Valid @RequestBody CreateUserRequest request) {
        accessControlService.requirePermission("user:write");
        return ApiResponse.success(identityService.createUser(accessControlService.currentUser(), request));
    }

    @PostMapping("/avatar")
    public ApiResponse<AvatarUploadResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
        accessControlService.requirePermission("user:write");
        return ApiResponse.success(new AvatarUploadResponse(avatarStorageService.store(file)));
    }

    @PutMapping("/{userId}")
    public ApiResponse<UserView> updateUser(@PathVariable String userId, @Valid @RequestBody UpdateUserRequest request) {
        accessControlService.requirePermission("user:write");
        validateUserId(userId);
        return ApiResponse.success(identityService.updateUser(accessControlService.currentUser(), userId, request));
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId 不能为空");
        }
        if (userId.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId 长度不能超过64");
        }
    }
}
