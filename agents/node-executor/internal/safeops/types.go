package safeops

type SafeAction struct {
	ActionType string        `json:"actionType"`
	TaskID     string        `json:"taskId"`
	CommandID  string        `json:"commandId"`
	TraceID    string        `json:"traceId"`
	ToolCall   *SafeToolCall `json:"toolCall"`
	Message    string        `json:"message"`
}

type SafeToolCall struct {
	CommandID       string          `json:"commandId"`
	TaskID          string          `json:"taskId"`
	SessionID       string          `json:"sessionId"`
	NodeID          string          `json:"nodeId"`
	ToolName        string          `json:"toolName"`
	ArgumentsJSON   string          `json:"argumentsJson"`
	RiskCode        string          `json:"riskCode"`
	TraceID         string          `json:"traceId"`
	SubtaskID       string          `json:"subtaskId"`
	AgentRole       string          `json:"agentRole"`
	ParentSpanID    string          `json:"parentSpanId"`
	TraceParent     string          `json:"traceparent"`
	PolicyIDs       []string        `json:"policyIds"`
	ApprovalToken   string          `json:"approvalToken"`
	IdempotencyKey  string          `json:"idempotencyKey"`
	ResourceLocks   []ResourceLock  `json:"resourceLocks"`
	RollbackContext RollbackContext `json:"rollbackContext"`
	ExecutionMode   string          `json:"executionMode"`

	TenantID           string `json:"tenantId"`
	ToolInvocationID   string `json:"toolInvocationId"`
	ExecutionAttemptID string `json:"executionAttemptId"`
	AgentInstanceID    string `json:"agentInstanceId"`
	AgentInvocationID  string `json:"agentInvocationId"`
	ToolVersion        string `json:"toolVersion"`
	LockVersion        uint64 `json:"lockVersion"`
}

type SafeToolResult struct {
	CommandID              string `json:"commandId"`
	TaskID                 string `json:"taskId"`
	Status                 string `json:"status"`
	ResultJSON             string `json:"resultJson"`
	Message                string `json:"message"`
	LocalGuardrailDecision string `json:"localGuardrailDecision"`
	EventHash              string `json:"eventHash"`
}

type ResourceLock struct {
	ResourceKey string `json:"resourceKey"`
	LockType    string `json:"lockType"`
}

type RollbackContext struct {
	RollbackRequired bool   `json:"rollbackRequired"`
	RollbackID       string `json:"rollbackId"`
	Note             string `json:"note"`
}

type RuntimeEvent struct {
	EventType   string
	RiskCode    string
	PayloadJSON string
}

type ExecutionReport struct {
	Result SafeToolResult
	Events []RuntimeEvent
}
