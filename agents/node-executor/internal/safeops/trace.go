package safeops

import (
	"encoding/json"
	"strings"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/gmcrypto"
)

func eventWithTrace(call SafeToolCall, eventType string, riskCode string, payloadJSON string) RuntimeEvent {
	payload := payloadMap(payloadJSON)
	spanID := spanID(call.CommandID + ":" + eventType)
	parentSpanID := strings.TrimSpace(call.CommandID)
	if parentSpanID == "" {
		parentSpanID = strings.TrimSpace(call.ParentSpanID)
	}
	subtaskID := strings.TrimSpace(call.SubtaskID)
	if subtaskID == "" {
		subtaskID = strings.TrimSpace(call.CommandID)
	}
	agentRole := strings.TrimSpace(call.AgentRole)
	if agentRole == "" {
		agentRole = "ExecutionAgent"
	}
	traceparent := traceParent(call.TraceID, spanID)
	payload["traceId"] = call.TraceID
	payload["w3cTraceId"] = w3cTraceID(call.TraceID)
	payload["spanId"] = spanID
	payload["parentSpanId"] = parentSpanID
	payload["traceparent"] = traceparent
	payload["subtaskId"] = subtaskID
	payload["agentRole"] = agentRole
	payload["commandId"] = call.CommandID
	payload["toolName"] = call.ToolName
	return RuntimeEvent{EventType: eventType, RiskCode: normalizeRisk(riskCode), PayloadJSON: marshal(payload)}
}

func payloadMap(payloadJSON string) map[string]any {
	payload := map[string]any{}
	if strings.TrimSpace(payloadJSON) == "" {
		return payload
	}
	var decoded any
	if err := json.Unmarshal([]byte(payloadJSON), &decoded); err != nil {
		payload["payload"] = payloadJSON
		return payload
	}
	if object, ok := decoded.(map[string]any); ok {
		return object
	}
	payload["payload"] = decoded
	return payload
}

func traceParent(traceID string, spanID string) string {
	return "00-" + w3cTraceID(traceID) + "-" + spanID + "-01"
}

func w3cTraceID(traceID string) string {
	normalized := strings.ToLower(strings.TrimSpace(traceID))
	normalized = strings.TrimPrefix(normalized, "trace-")
	if isHex(normalized, 32) && !allZero(normalized) {
		return normalized
	}
	return gmcrypto.SM3HexString(traceID)[:32]
}

func spanID(value string) string {
	normalized := strings.ToLower(strings.TrimSpace(value))
	if isHex(normalized, 16) && !allZero(normalized) {
		return normalized
	}
	return gmcrypto.SM3HexString(value)[:16]
}

func isHex(value string, length int) bool {
	if len(value) != length {
		return false
	}
	for _, ch := range value {
		if (ch < '0' || ch > '9') && (ch < 'a' || ch > 'f') && (ch < 'A' || ch > 'F') {
			return false
		}
	}
	return true
}

func allZero(value string) bool {
	for _, ch := range value {
		if ch != '0' {
			return false
		}
	}
	return true
}
