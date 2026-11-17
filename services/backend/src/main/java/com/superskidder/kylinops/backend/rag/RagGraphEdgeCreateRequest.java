package com.superskidder.kylinops.backend.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagGraphEdgeCreateRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 255, message = "长度不能超过255")
        String sourceNode,
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String relation,
        @NotBlank(message = "不能为空")
        @Size(max = 255, message = "长度不能超过255")
        String targetNode,
        @Size(max = 12000, message = "长度不能超过12000")
        String evidence,
        @Size(max = 64, message = "长度不能超过64")
        String sourceDocumentId
) {
}
