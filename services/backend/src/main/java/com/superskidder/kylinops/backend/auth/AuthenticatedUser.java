package com.superskidder.kylinops.backend.auth;

import java.util.List;
import java.util.Set;

public record AuthenticatedUser(
        String userId,
        String username,
        String displayName,
        String email,
        String avatarUrl,
        boolean enabled,
        boolean mustChangePassword,
        String currentOrganizationId,
        List<String> organizationIds,
        List<String> roleCodes,
        Set<String> permissions
) {
}
