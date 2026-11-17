package com.superskidder.kylinops.backend.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    @Test
    void shouldHashAndVerifyWithPbkdf2Format() {
        PasswordHasher passwordHasher = new PasswordHasher();

        String hash = passwordHasher.hash("admin123");

        assertThat(hash).startsWith("pbkdf2_sm3$120000$");
        assertThat(passwordHasher.matches("admin123", hash)).isTrue();
        assertThat(passwordHasher.matches("wrong", hash)).isFalse();
    }

    @Test
    void shouldRejectLegacySha256Hash() {
        PasswordHasher passwordHasher = new PasswordHasher();

        assertThat(passwordHasher.matches(
                "admin123",
                "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"
        )).isFalse();
    }
}
