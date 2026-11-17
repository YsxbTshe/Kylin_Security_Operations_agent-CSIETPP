package com.superskidder.kylinops.backend.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagDocumentCreateRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 255, message = "长度不能超过255")
        String title,
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String sourceType,
        @NotBlank(message = "不能为空")
        @Size(max = 12000, message = "长度不能超过12000")
        String content
) {
}
