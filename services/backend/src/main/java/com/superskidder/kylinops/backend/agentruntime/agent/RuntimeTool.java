package com.superskidder.kylinops.backend.agentruntime.agent;

import com.superskidder.kylinops.backend.agentruntime.domain.ToolExecutor;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;

/**
 * RUNTIME 执行器标记接口。NodeTool 本身不带 executor 字段，
 * 实现该接口的工具由 RuntimeToolDispatcher 在 Runtime 内执行，不下发到节点 MCP。
 * 这是对现有工具体系的最小侵入方式。
 */
public interface RuntimeTool extends NodeTool {

    default ToolExecutor executor() {
        return ToolExecutor.RUNTIME;
    }
}
