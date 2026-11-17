package com.superskidder.kylinops.backend.abac;

import java.util.List;
import java.util.Map;

/**
 * 一次资源访问判定的输入：主体（用户 + 其角色）、资源、动作、以及上下文属性
 * （如本次动作的风险等级、目标节点等），供条件匹配使用。
 */
public record AbacRequest(
        String userId,
        List<String> roles,
        String resourceType,
        String resourceId,
        String action,
        Map<String, String> context
) {
}
