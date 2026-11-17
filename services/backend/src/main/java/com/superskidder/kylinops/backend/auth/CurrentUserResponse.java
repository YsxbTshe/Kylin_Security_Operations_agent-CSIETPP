package com.superskidder.kylinops.backend.auth;

import com.superskidder.kylinops.backend.iam.OrganizationView;
import com.superskidder.kylinops.backend.iam.RoleView;
import java.util.List;
import java.util.Set;

public record CurrentUserResponse(
        String id,
        String username,
        String displayName,
        String email,
        String avatarUrl,
        boolean enabled,
        boolean mustChangePassword,
        String currentOrganizationId,
        List<OrganizationView> organizations,
        List<RoleView> roles,
        Set<String> permissions
) {
}
