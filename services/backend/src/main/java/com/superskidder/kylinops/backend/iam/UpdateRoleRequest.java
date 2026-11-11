package com.superskidder.kylinops.backend.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateRoleRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String name,
        @NotBlank(message = "不能为空")
        @Size(max = 255, message = "长度不能超过255")
        String description,
        @NotEmpty(message = "至少需要一个权限")
        @Size(max = 100, message = "数量不能超过100")
        List<@Size(max = 64, message = "长度不能超过64") String> permissions
) {
}
