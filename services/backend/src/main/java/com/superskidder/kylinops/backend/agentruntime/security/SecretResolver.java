package com.superskidder.kylinops.backend.agentruntime.security;

public interface SecretResolver {
    SecretValue resolve(String reference);
}
