package com.superskidder.kylinops.backend.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagMemoryCreateRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String memoryType,
        @NotBlank(message = "不能为空")
        @Size(max = 255, message = "长度不能超过255")
        String title,
        @NotBlank(message = "不能为空")
        @Size(max = 12000, message = "长度不能超过12000")
        String content,
        @Min(value = 1, message = "不能小于1")
        @Max(value = 5, message = "不能大于5")
        int importance,
        @Size(max = 64, message = "长度不能超过64")
        String sourceDocumentId
) {
}
