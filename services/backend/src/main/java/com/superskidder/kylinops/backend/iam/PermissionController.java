package com.superskidder.kylinops.backend.iam;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private final IdentityService identityService;
    private final AccessControlService accessControlService;

    public PermissionController(IdentityService identityService, AccessControlService accessControlService) {
        this.identityService = identityService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ApiResponse<List<PermissionView>> listPermissions() {
        accessControlService.requirePermission("permission:read");
        return ApiResponse.success(identityService.listAllPermissions());
    }

    @PostMapping
    public ApiResponse<PermissionView> createPermission(@Valid @RequestBody CreatePermissionRequest request) {
        accessControlService.requirePermission("permission:write");
        return ApiResponse.success(identityService.createPermission(accessControlService.currentUser(), request));
    }

    @PutMapping("/{permissionCode}")
    public ApiResponse<PermissionView> updatePermission(
            @PathVariable String permissionCode,
            @Valid @RequestBody UpdatePermissionRequest request
    ) {
        accessControlService.requirePermission("permission:write");
        validatePermissionCode(permissionCode);
        return ApiResponse.success(identityService.updatePermission(accessControlService.currentUser(), permissionCode, request));
    }

    @DeleteMapping("/{permissionCode}")
    public ApiResponse<Boolean> deletePermission(@PathVariable String permissionCode) {
        accessControlService.requirePermission("permission:write");
        validatePermissionCode(permissionCode);
        identityService.deletePermission(accessControlService.currentUser(), permissionCode);
        return ApiResponse.success(true);
    }

    private void validatePermissionCode(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissionCode 不能为空");
        }
        if (permissionCode.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "permissionCode 长度不能超过64");
        }
    }
}
