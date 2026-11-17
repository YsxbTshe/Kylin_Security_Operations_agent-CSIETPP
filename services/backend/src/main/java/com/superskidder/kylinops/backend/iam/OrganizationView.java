package com.superskidder.kylinops.backend.iam;

public record OrganizationView(
        String id,
        String code,
        String name,
        long hostCount,
        long memberCount,
        boolean current
) {
}
