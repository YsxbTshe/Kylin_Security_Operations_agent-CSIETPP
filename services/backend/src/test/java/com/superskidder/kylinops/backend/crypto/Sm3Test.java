package com.superskidder.kylinops.backend.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sm3Test {

    @Test
    void shouldMatchKnownSm3Vector() {
        assertThat(Sm3.hex("abc")).isEqualTo("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0");
    }
}
