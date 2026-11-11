package com.superskidder.kylinops.backend.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String code,
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String name
) {
}
