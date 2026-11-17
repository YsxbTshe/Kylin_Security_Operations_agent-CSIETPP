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
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final IdentityService identityService;
    private final AccessControlService accessControlService;

    public RoleController(IdentityService identityService, AccessControlService accessControlService) {
        this.identityService = identityService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ApiResponse<List<RoleView>> listRoles() {
        accessControlService.requirePermission("role:read");
        return ApiResponse.success(identityService.listAllRoles());
    }

    @PostMapping
    public ApiResponse<RoleView> createRole(@Valid @RequestBody CreateRoleRequest request) {
        accessControlService.requirePermission("role:write");
        return ApiResponse.success(identityService.createRole(accessControlService.currentUser(), request));
    }

    @PutMapping("/{roleCode}")
    public ApiResponse<RoleView> updateRole(@PathVariable String roleCode, @Valid @RequestBody UpdateRoleRequest request) {
        accessControlService.requirePermission("role:write");
        validateRoleCode(roleCode);
        return ApiResponse.success(identityService.updateRole(accessControlService.currentUser(), roleCode, request));
    }

    @DeleteMapping("/{roleCode}")
    public ApiResponse<Boolean> deleteRole(@PathVariable String roleCode) {
        accessControlService.requirePermission("role:write");
        validateRoleCode(roleCode);
        identityService.deleteRole(accessControlService.currentUser(), roleCode);
        return ApiResponse.success(true);
    }

    private void validateRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleCode 不能为空");
        }
        if (roleCode.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roleCode 长度不能超过64");
        }
    }
}
