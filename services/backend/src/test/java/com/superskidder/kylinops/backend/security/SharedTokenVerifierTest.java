package com.superskidder.kylinops.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedTokenVerifierTest {

    @Test
    void shouldRejectBlankConfiguredToken() {
        assertThatThrownBy(() -> SharedTokenVerifier.requireConfigured(" ", "app.test.shared-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.test.shared-token must be configured");
    }

    @Test
    void shouldCompareTokenWithoutAcceptingBlankInput() {
        assertThat(SharedTokenVerifier.matches("shared-secret", "shared-secret")).isTrue();
        assertThat(SharedTokenVerifier.matches("shared-secret", " shared-secret ")).isTrue();
        assertThat(SharedTokenVerifier.matches("shared-secret", "wrong-secret")).isFalse();
        assertThat(SharedTokenVerifier.matches("shared-secret", "")).isFalse();
        assertThat(SharedTokenVerifier.matches("shared-secret", null)).isFalse();
    }
}
