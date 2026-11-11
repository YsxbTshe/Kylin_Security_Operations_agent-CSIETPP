package com.superskidder.kylinops.backend.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagQueryRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 4000, message = "长度不能超过4000")
        String question,
        /** 是否返回混合检索轨迹（各通道候选与 RRF 贡献）；默认关闭 */
        Boolean withTrace
) {

    public boolean traceRequested() {
        return Boolean.TRUE.equals(withTrace);
    }
}
