package com.superskidder.kylinops.backend.registry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 创建/更新专家 Agent（按 agentKey upsert）。 */
public record AgentUpsertRequest(
        @NotBlank(message = "agentKey 不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String agentKey,
        @NotBlank(message = "名称不能为空")
        @Size(max = 128, message = "长度不能超过128")
        String name,
        @NotBlank(message = "角色不能为空")
        @Size(max = 64, message = "长度不能超过64")
        String role,
        @Size(max = 512, message = "长度不能超过512")
        String description,
        List<String> knowledgeDomains,
        List<String> allowedTools,
        @Size(max = 8, message = "长度不能超过8")
        String maxRisk,
        Long budgetTokens,
        Boolean enabled,
        @Size(max = 32, message = "长度不能超过32")
        String version
) {
}
