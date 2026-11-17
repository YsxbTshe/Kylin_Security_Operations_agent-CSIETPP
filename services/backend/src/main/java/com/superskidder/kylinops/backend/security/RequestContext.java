package com.superskidder.kylinops.backend.security;

import com.superskidder.kylinops.backend.auth.AuthenticatedUser;

public record RequestContext(String token, AuthenticatedUser user) {
}
