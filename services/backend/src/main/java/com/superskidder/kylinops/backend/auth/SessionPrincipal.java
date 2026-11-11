package com.superskidder.kylinops.backend.auth;

public record SessionPrincipal(String token, String userId, String currentOrganizationId) {
}
