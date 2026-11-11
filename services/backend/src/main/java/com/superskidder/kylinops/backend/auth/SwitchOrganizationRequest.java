package com.superskidder.kylinops.backend.auth;

import jakarta.validation.constraints.NotBlank;

public record SwitchOrganizationRequest(@NotBlank(message = "不能为空") String organizationId) {
}
