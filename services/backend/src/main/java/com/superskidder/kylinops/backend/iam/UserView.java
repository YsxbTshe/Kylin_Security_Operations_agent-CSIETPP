package com.superskidder.kylinops.backend.iam;

import java.util.List;

public record UserView(
        String id,
        String username,
        String displayName,
        String email,
        String avatarUrl,
        boolean enabled,
        String defaultOrganizationId,
        List<RoleView> roles,
        List<OrganizationView> organizations
) {
}
