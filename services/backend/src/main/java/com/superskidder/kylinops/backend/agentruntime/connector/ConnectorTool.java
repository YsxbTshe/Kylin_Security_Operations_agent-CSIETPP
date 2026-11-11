package com.superskidder.kylinops.backend.agentruntime.connector;

import com.superskidder.kylinops.backend.agentruntime.domain.ToolExecutor;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;

/**
 * CONNECTOR 执行器标记接口。实现该接口的工具不下发节点，
 * 由 ConnectorToolDispatcher 经 ConnectorToolGateway 在控制面内执行。
 */
public interface ConnectorTool extends NodeTool {

    default ToolExecutor executor() {
        return ToolExecutor.CONNECTOR;
    }
}
