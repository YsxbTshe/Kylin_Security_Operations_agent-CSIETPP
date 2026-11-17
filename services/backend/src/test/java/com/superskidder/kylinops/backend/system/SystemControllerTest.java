package com.superskidder.kylinops.backend.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemControllerTest {

    @Test
    void shouldRejectBlankScreenSharedToken() {
        SystemController controller = new SystemController(null, null, " ", "test", false);

        assertThatThrownBy(controller::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.screen.shared-token must be configured");
    }
}
