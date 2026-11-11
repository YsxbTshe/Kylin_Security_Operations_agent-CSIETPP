package com.superskidder.kylinops.backend.agentruntime.connector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway;
import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * CONNECTOR 执行器分流：CONNECTOR 类工具不下发节点，经 ConnectorToolGateway 在控制面内执行。
 * 与 RuntimeToolDispatcher 同构——DispatchGate 已在 AgentRunWorker 前置评估，此处只做执行。
 */
@Component
public class ConnectorToolDispatcher {

    private final ConnectorToolGateway gateway;
    private final ObjectMapper objectMapper;

    public ConnectorToolDispatcher(ConnectorToolGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public record DispatchOutcome(Map<String, Object> structuredOutput, Map<String, Object> renderedOutput) {}

    public DispatchOutcome dispatch(LeasedAgentRun run, NodeTool tool, Map<String, Object> arguments) {
        try {
            if (!(tool instanceof ConnectorInvokeTool connectorTool)) {
                throw new ConnectorException("CONNECTOR_TOOL_UNSUPPORTED",
                        "unsupported connector tool: " + tool.toolId(), false);
            }
            Map<String, Object> validated = connectorTool.validateArguments(arguments);
            ConnectorToolGateway.Invocation invocation = toInvocation(run, validated);
            Map<String, Object> structured = gateway.execute(invocation);
            String resultJson = objectMapper.writeValueAsString(structured);
            Map<String, Object> output = connectorTool.validateOutput(resultJson);
            return new DispatchOutcome(output, connectorTool.render(output));
        } catch (ConnectorException connectorException) {
            throw new NodeMcpGateway.ToolCallException(
                    connectorException.errorCode(), connectorException.getMessage(), connectorException.retryable());
        } catch (IllegalArgumentException argumentException) {
            throw new NodeMcpGateway.ToolCallException(
                    "CONTRACT_VALIDATION_FAILED", argumentException.getMessage(), false);
        } catch (JsonProcessingException serializationException) {
            throw new NodeMcpGateway.ToolCallException(
                    "CONNECTOR_RESULT_INVALID", "connector result cannot be serialized", false);
        }
    }

    @SuppressWarnings("unchecked")
    private ConnectorToolGateway.Invocation toInvocation(LeasedAgentRun run, Map<String, Object> validated) {
        return new ConnectorToolGateway.Invocation(
                run.tenantId(),
                run.id(),
                (String) validated.get("connector_id"),
                (String) validated.get("path"),
                (Map<String, String>) validated.get("query"),
                (Map<String, Object>) validated.get("body"));
    }
}
