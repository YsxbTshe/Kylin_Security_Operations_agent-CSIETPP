package com.superskidder.kylinops.backend.agentruntime.mcp;

import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;

public record RuntimeToolContext(
        LeasedAgentRun run,
        String agentInvocationId,
        String nodeId
) {
}
