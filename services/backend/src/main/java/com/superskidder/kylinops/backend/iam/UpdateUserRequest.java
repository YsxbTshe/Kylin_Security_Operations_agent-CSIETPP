package com.superskidder.kylinops.backend.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateUserRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String displayName,
        @Size(max = 128, message = "长度不能超过128")
        String email,
        @Size(max = 512, message = "长度不能超过512")
        String avatarUrl,
        @Size(max = 128, message = "长度不能超过128")
        @Pattern(regexp = "^$|^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "口令至少8位，且需同时包含字母与数字（留空表示不修改）")
        String password,
        @NotNull(message = "不能为空") Boolean enabled,
        @NotEmpty(message = "至少需要一个角色")
        @Size(max = 50, message = "数量不能超过50")
        List<@Size(max = 64, message = "长度不能超过64") String> roleCodes,
        @NotEmpty(message = "至少需要一个组织")
        @Size(max = 50, message = "数量不能超过50")
        List<@Size(max = 64, message = "长度不能超过64") String> organizationIds,
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String defaultOrganizationId
) {
}
