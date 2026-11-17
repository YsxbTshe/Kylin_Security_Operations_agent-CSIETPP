package com.superskidder.kylinops.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "不能为空")
        String currentPassword,
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$", message = "口令至少8位，且需同时包含字母与数字")
        String newPassword
) {
}
