package com.superskidder.kylinops.backend.agentruntime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceStatusToolTest {

    private final ServiceStatusTool tool = new ServiceStatusTool(new ObjectMapper());

    @Test
    void acceptsOnlyTheStrictInputAndOutputContract() {
        assertThat(tool.validateArguments(Map.of("service", "nginx"))).isEqualTo(Map.of("service", "nginx"));
        assertThatThrownBy(() -> tool.validateArguments(Map.of("service", "nginx", "shell", "id")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of("service", "nginx;reboot")))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> output = tool.validateOutput("""
                {"service":"nginx","activeState":"active","subState":"running","loadState":"loaded","unitFileState":"enabled"}
                """);
        assertThat(tool.render(output)).containsEntry("directDisplay", true).containsEntry("activeState", "active");
        assertThatThrownBy(() -> tool.validateOutput("""
                {"service":"nginx","activeState":"active","subState":"running","loadState":"loaded","unitFileState":"enabled","stdout":"unsafe"}
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}
