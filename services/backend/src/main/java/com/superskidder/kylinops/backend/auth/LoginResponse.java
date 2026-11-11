package com.superskidder.kylinops.backend.auth;

public record LoginResponse(String token, CurrentUserResponse user, boolean mustChangePassword) {
}
