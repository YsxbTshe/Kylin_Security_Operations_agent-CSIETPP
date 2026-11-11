package com.superskidder.kylinops.backend.iam;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final IdentityService identityService;
    private final AccessControlService accessControlService;

    public OrganizationController(IdentityService identityService, AccessControlService accessControlService) {
        this.identityService = identityService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public ApiResponse<List<OrganizationView>> listOrganizations() {
        accessControlService.requirePermission("organization:read");
        return ApiResponse.success(identityService.listOrganizations(accessControlService.currentUser()));
    }

    @PostMapping
    public ApiResponse<OrganizationView> createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
        accessControlService.requirePermission("organization:write");
        return ApiResponse.success(identityService.createOrganization(accessControlService.currentUser(), request));
    }

    @PutMapping("/{organizationId}")
    public ApiResponse<OrganizationView> updateOrganization(
            @PathVariable String organizationId,
            @Valid @RequestBody UpdateOrganizationRequest request
    ) {
        accessControlService.requirePermission("organization:write");
        validateOrganizationId(organizationId);
        return ApiResponse.success(identityService.updateOrganization(accessControlService.currentUser(), organizationId, request));
    }

    private void validateOrganizationId(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId 不能为空");
        }
        if (organizationId.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId 长度不能超过64");
        }
    }
}
