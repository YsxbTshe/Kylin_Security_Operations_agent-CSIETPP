package controlstreamv2

import (
	"encoding/hex"
	"encoding/json"
	"strings"
	"testing"
	"time"

	v2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/gmcrypto"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/mcp"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/safeops"
	"google.golang.org/protobuf/types/known/timestamppb"
)

func TestSafeToolCallAcceptsReadOnlyAndRejectsWritesWithoutLocks(t *testing.T) {
	client := Client{TenantID: "tenant-1", NodeID: "node-1"}
	catalog := mcp.NewCatalog()
	arguments := `{"service":"nginx"}`
	digest, err := hex.DecodeString(gmcrypto.SM3HexString(arguments))
	if err != nil {
		t.Fatal(err)
	}
	submit := &v2.SubmitInvocation{
		TenantId: "tenant-1", NodeId: "node-1", RunId: "run-1",
		ToolInvocationId: "tool-1", ExecutionAttemptId: "attempt-1",
		ToolId: "kylin.service.status", ToolVersion: "1.0.0",
		CanonicalArgumentsJson: arguments, ArgumentsSm3: digest,
		RiskCode: v2.RiskCode_RISK_CODE_R0, ReadOnly: true,
		Deadline: timestamppb.New(time.Now().Add(time.Minute)),
	}

	call, err := client.safeToolCall(submit, catalog)
	if err != nil {
		t.Fatalf("safeToolCall() error = %v", err)
	}
	if call.CommandID != "attempt-1" || call.IdempotencyKey != "tool-1" || call.ToolName != "kylin.service.status" {
		t.Fatalf("unexpected safe call: %+v", call)
	}

	submit.ReadOnly = false
	if _, err := client.safeToolCall(submit, catalog); err == nil || !strings.Contains(err.Error(), "WRITE_EXCLUSIVE") {
		t.Fatalf("expected write lock rejection, got %v", err)
	}
	submit.ReadOnly = true
	submit.ArgumentsSm3 = []byte("wrong")
	if _, err := client.safeToolCall(submit, catalog); err == nil || !strings.Contains(err.Error(), "SM3 mismatch") {
		t.Fatalf("expected digest rejection, got %v", err)
	}
}

// V2 协议没有 session 概念：SessionID 必须取 AgentInstanceID，因为它下游被映射为
// workspace 隔离键（WorkspaceID）。留空会让同一 agent 的所有实例共用同一个沙箱。
func TestSafeToolCallMapsSessionIDFromAgentInstance(t *testing.T) {
	client := Client{TenantID: "tenant-1", NodeID: "node-1"}
	catalog := mcp.NewCatalog()
	arguments := `{"service":"nginx"}`
	digest, err := hex.DecodeString(gmcrypto.SM3HexString(arguments))
	if err != nil {
		t.Fatal(err)
	}
	submit := &v2.SubmitInvocation{
		TenantId: "tenant-1", NodeId: "node-1", RunId: "run-1",
		ToolInvocationId: "tool-1", ExecutionAttemptId: "attempt-1",
		AgentInstanceId: "agent-instance-9",
		ToolId:          "kylin.service.status", ToolVersion: "1.0.0",
		CanonicalArgumentsJson: arguments, ArgumentsSm3: digest,
		RiskCode: v2.RiskCode_RISK_CODE_R0, ReadOnly: true,
		Deadline: timestamppb.New(time.Now().Add(time.Minute)),
	}

	call, err := client.safeToolCall(submit, catalog)
	if err != nil {
		t.Fatalf("safeToolCall() error = %v", err)
	}
	if call.SessionID != "agent-instance-9" || call.SessionID != call.AgentInstanceID {
		t.Fatalf("SessionID 必须以 AgentInstanceID 为 workspace 隔离键，实际 %+v", call)
	}
}

func TestSafeToolCallRejectsUnknownTool(t *testing.T) {
	client := Client{TenantID: "tenant-1", NodeID: "node-1"}
	catalog := mcp.NewCatalog()
	submit := &v2.SubmitInvocation{
		TenantId: "tenant-1", NodeId: "node-1", RunId: "run-1",
		ToolInvocationId: "tool-1", ExecutionAttemptId: "attempt-1",
		ToolId: "kylin.nonexistent.tool", ToolVersion: "1.0.0",
		CanonicalArgumentsJson: `{}`, ArgumentsSm3: make([]byte, 32),
		RiskCode: v2.RiskCode_RISK_CODE_R0, ReadOnly: true,
		Deadline: timestamppb.New(time.Now().Add(time.Minute)),
	}
	if _, err := client.safeToolCall(submit, catalog); err == nil || !strings.Contains(err.Error(), "not in the node catalog") {
		t.Fatalf("expected catalog rejection, got %v", err)
	}
}

func TestSafeToolCallRejectsR5(t *testing.T) {
	client := Client{TenantID: "tenant-1", NodeID: "node-1"}
	catalog := mcp.NewCatalog()
	arguments := `{"service":"nginx"}`
	submit := &v2.SubmitInvocation{
		TenantId: "tenant-1", NodeId: "node-1", RunId: "run-1",
		ToolInvocationId: "tool-1", ExecutionAttemptId: "attempt-1",
		ToolId: "kylin.service.status", ToolVersion: "1.0.0",
		CanonicalArgumentsJson: arguments,
		ArgumentsSm3:           func() []byte { d, _ := hex.DecodeString(gmcrypto.SM3HexString(arguments)); return d }(),
		RiskCode:               v2.RiskCode_RISK_CODE_R5, ReadOnly: true,
		Deadline: timestamppb.New(time.Now().Add(time.Minute)),
	}
	if _, err := client.safeToolCall(submit, catalog); err == nil || !strings.Contains(err.Error(), "R5") {
		t.Fatalf("expected R5 rejection, got %v", err)
	}
}

func TestInvocationResultEnforcesStrictOutputSchema(t *testing.T) {
	client := Client{}
	call := safeops.SafeToolCall{CommandID: "attempt-1", TaskID: "run-1", IdempotencyKey: "tool-1", ToolName: "kylin.service.status"}
	valid := `{"service":"nginx","activeState":"active","subState":"running","loadState":"loaded","unitFileState":"enabled"}`
	result := client.invocationResult(call, safeops.SafeToolResult{
		CommandID: "attempt-1", TaskID: "run-1", Status: "COMPLETED",
		ResultJSON: `{"structuredContent":{"output":` + quoted(valid) + `}}`,
	}).GetResult()
	if result.GetStatus() != v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_SUCCEEDED {
		t.Fatalf("expected success, got %s: %s", result.GetStatus(), result.GetMessage())
	}
	if hex.EncodeToString(result.GetResultSm3()) != gmcrypto.SM3HexString(valid) {
		t.Fatal("result SM3 does not cover the exact structured result")
	}

	invalid := client.invocationResult(call, safeops.SafeToolResult{
		CommandID: "attempt-1", TaskID: "run-1", Status: "COMPLETED",
		ResultJSON: `{"structuredContent":{"output":"{\\"service\\":\\"nginx\\"}"}}`,
	}).GetResult()
	if invalid.GetStatus() != v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_FAILED ||
		invalid.GetErrorCode() != "OUTPUT_SCHEMA_VALIDATION_FAILED" {
		t.Fatalf("expected schema failure, got %+v", invalid)
	}
}

func TestInvocationResultAcceptsGenericJsonForNonServiceTools(t *testing.T) {
	client := Client{}
	call := safeops.SafeToolCall{CommandID: "attempt-1", TaskID: "run-1", IdempotencyKey: "tool-1", ToolName: "kylin.service.start"}
	output := `{"status":"started","service":"nginx"}`
	result := client.invocationResult(call, safeops.SafeToolResult{
		CommandID: "attempt-1", TaskID: "run-1", Status: "COMPLETED",
		ResultJSON: `{"structuredContent":{"output":` + quoted(output) + `}}`,
	}).GetResult()
	if result.GetStatus() != v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_SUCCEEDED {
		t.Fatalf("expected success for generic tool output, got %s: %s", result.GetStatus(), result.GetMessage())
	}
	if result.GetStructuredResultJson() != output {
		t.Fatalf("expected unwrapped JSON object, got %s", result.GetStructuredResultJson())
	}
}

func TestInvocationResultWrapsPlainTextPerceptionOutput(t *testing.T) {
	client := Client{}
	call := safeops.SafeToolCall{CommandID: "attempt-1", TaskID: "run-1", IdempotencyKey: "tool-1", ToolName: "kylin.disk.usage"}
	result := client.invocationResult(call, safeops.SafeToolResult{
		CommandID: "attempt-1", TaskID: "run-1", Status: "COMPLETED",
		ResultJSON: `{"structuredContent":{"toolName":"kylin.disk.usage","output":"Filesystem Size Used\n/dev/sda1 40G 12G"}}`,
	}).GetResult()
	if result.GetStatus() != v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_SUCCEEDED {
		t.Fatalf("expected wrapped plaintext to succeed, got %s: %s", result.GetStatus(), result.GetMessage())
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(result.GetStructuredResultJson()), &payload); err != nil {
		t.Fatalf("wrapped payload is not JSON: %v", err)
	}
	if payload["output"] != "Filesystem Size Used\n/dev/sda1 40G 12G" {
		t.Fatalf("unexpected wrapped output: %#v", payload)
	}
}

func TestInvocationResultKeepsWorkspaceExitCode(t *testing.T) {
	client := Client{}
	call := safeops.SafeToolCall{CommandID: "attempt-1", TaskID: "run-1", IdempotencyKey: "tool-1", ToolName: "kylin.workspace.bash"}
	result := client.invocationResult(call, safeops.SafeToolResult{
		CommandID: "attempt-1", TaskID: "run-1", Status: "COMPLETED",
		ResultJSON: `{"structuredContent":{"tool":"kylin.workspace.bash","output":"hello","exitCode":0,"via":"workspace"}}`,
	}).GetResult()
	if result.GetStatus() != v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_SUCCEEDED {
		t.Fatalf("expected workspace envelope to succeed, got %s: %s", result.GetStatus(), result.GetMessage())
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(result.GetStructuredResultJson()), &payload); err != nil {
		t.Fatalf("workspace payload is not JSON: %v", err)
	}
	if payload["output"] != "hello" {
		t.Fatalf("unexpected output: %#v", payload)
	}
	if int(payload["exitCode"].(float64)) != 0 {
		t.Fatalf("expected exitCode 0, got %#v", payload["exitCode"])
	}
	if _, ok := payload["via"]; ok {
		t.Fatalf("transport metadata via should be stripped: %#v", payload)
	}
}

func quoted(value string) string {
	encoded, _ := json.Marshal(value)
	return string(encoded)
}
