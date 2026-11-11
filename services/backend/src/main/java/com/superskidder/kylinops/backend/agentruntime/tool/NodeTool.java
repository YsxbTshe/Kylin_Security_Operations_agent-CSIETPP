package com.superskidder.kylinops.backend.agentruntime.tool;

import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.llm.LlmTool;
import java.util.List;
import java.util.Map;

public interface NodeTool {

    String toolId();

    String version();

    String packageVersionId();

    boolean readOnly();

    String riskFloor();

    boolean alwaysRequireApproval();

    ToolIdempotency idempotency();

    int maxAttempts();

    Map<String, Object> inputSchema();

    Map<String, Object> outputSchema();

    Map<String, Object> validateArguments(Map<String, Object> arguments);

    Map<String, Object> validateOutput(String resultJson);

    Map<String, Object> render(Map<String, Object> output);

    LlmTool llmTool();

    String inputSchemaDigest();

    String outputSchemaDigest();

    String catalogDigest();

    List<ResourceLockTemplate> resourceLockTemplates();

    record ResourceLockTemplate(String resourcePattern, String lockMode) {}
}
