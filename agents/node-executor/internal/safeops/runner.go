package safeops

import (
	"context"
	"encoding/json"
	"fmt"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/privops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/workspace"
	"strings"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/mcp"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/otelsetup"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

type Runtime interface {
	ExecuteTool(nodeID string, toolName string, argumentsJSON string) (string, error)
}

type nativeGuardPostureProvider interface {
	NativeGuardPosturePayload(nodeID string) any
}

// WorkspaceCaller 是 workspace runner 客户端的最小接口。
// 抽成接口只为可测——生产实现是 workspace.Client。
type WorkspaceCaller interface {
	Call(req workspace.Request) (workspace.Response, error)
}

type Runner struct {
	runtime         Runtime
	catalog         mcp.Catalog
	catalogProvider func() mcp.Catalog
}

func NewRunner(runtime Runtime, catalogs ...mcp.Catalog) Runner {
	catalog := mcp.NewCatalog()
	if len(catalogs) > 0 {
		catalog = catalogs[0]
	}
	return Runner{runtime: runtime, catalog: catalog}
}

func (r Runner) Execute(ctx context.Context, call SafeToolCall) SafeToolResult {
	return r.ExecuteReport(ctx, call).Result
}

func (r Runner) ExecuteReport(ctx context.Context, call SafeToolCall) ExecutionReport {
	events := []RuntimeEvent{eventWithTrace(call, "TASK_STARTED", call.RiskCode, marshal(map[string]any{
		"commandId": call.CommandID,
		"toolName":  call.ToolName,
	}))}
	select {
	case <-ctx.Done():
		result := SafeToolResult{CommandID: call.CommandID, TaskID: call.TaskID, Status: "FAILED", Message: ctx.Err().Error(), LocalGuardrailDecision: "DENY"}
		events = append(events, eventWithTrace(call, "TASK_FAILED", call.RiskCode, marshal(map[string]any{"reason": ctx.Err().Error()})))
		return ExecutionReport{Result: result, Events: events}
	default:
	}
	catalog := r.currentCatalog()
	_, guardSpan := otelsetup.Tracer().Start(ctx, "host.local_guardrail.check", trace.WithAttributes(traceAttributes(call)...))
	decision, err := checkLocalGuardrail(call, catalog, r.runtime)
	guardSpan.SetAttributes(attribute.String("safeops.local_guardrail_decision", decision))
	if err != nil {
		guardSpan.RecordError(err)
		guardSpan.SetStatus(codes.Error, err.Error())
	}
	guardSpan.End()
	events = append(events, eventWithTrace(call, "LOCAL_GUARDRAIL_CHECKED", call.RiskCode, marshal(map[string]any{
		"decision": decision,
		"error":    errorString(err),
		"toolName": call.ToolName,
	})))
	if err != nil {
		result := SafeToolResult{CommandID: call.CommandID, TaskID: call.TaskID, Status: "DENIED", ResultJSON: marshal(map[string]any{"error": err.Error()}), Message: err.Error(), LocalGuardrailDecision: "DENY"}
		events = append(events, eventWithTrace(call, "TASK_FAILED", call.RiskCode, marshal(map[string]any{"reason": err.Error()})))
		return ExecutionReport{Result: result, Events: events}
	}
	events = append(events, eventWithTrace(call, "MCP_TOOL_CALLED", call.RiskCode, marshal(map[string]any{
		"toolName": call.ToolName,
	})))
	events = append(events, eventWithTrace(call, "EXECUTION_STARTED", call.RiskCode, marshal(map[string]any{
		"commandId": call.CommandID,
	})))
	resultJSON, err := r.dispatch(ctx, call)
	if err != nil {
		// callMCP 在工具自身返回错误时会把 MCP 的 CallResult 一并带回，其中含真实的失败原因
		// （如命令 stderr）。此前这里只保留了笼统的 err.Error()，把详情丢掉了，排障时
		// 只能看到 "MCP tool returned error"，无法定位根因。
		detail := toolErrorDetail(resultJSON)
		message := err.Error()
		if detail != "" {
			message = detail
		}
		payload := marshal(map[string]any{"error": message})
		if strings.TrimSpace(resultJSON) != "" {
			payload = resultJSON
		}
		result := SafeToolResult{CommandID: call.CommandID, TaskID: call.TaskID, Status: "FAILED", ResultJSON: payload, Message: message, LocalGuardrailDecision: "ALLOW"}
		events = append(events, eventWithTrace(call, "EXECUTION_FINISHED", call.RiskCode, payload))
		events = append(events, eventWithTrace(call, "TASK_FAILED", call.RiskCode, marshal(map[string]any{"reason": message})))
		return ExecutionReport{Result: result, Events: events}
	}
	result := SafeToolResult{CommandID: call.CommandID, TaskID: call.TaskID, Status: "COMPLETED", ResultJSON: resultJSON, Message: "completed", LocalGuardrailDecision: "ALLOW"}
	events = append(events, eventWithTrace(call, "EXECUTION_FINISHED", call.RiskCode, resultJSON))
	events = append(events, eventWithTrace(call, "TASK_COMPLETED", call.RiskCode, marshal(map[string]any{"commandId": call.CommandID, "status": result.Status})))
	return ExecutionReport{Result: result, Events: events}
}

// toolErrorDetail 从 MCP CallResult 中取出工具自身报告的失败原因。
// 取不到时返回空串，由调用方回退到通用错误文本。
func toolErrorDetail(resultJSON string) string {
	if strings.TrimSpace(resultJSON) == "" {
		return ""
	}
	var payload struct {
		StructuredContent struct {
			Error string `json:"error"`
		} `json:"structuredContent"`
		Content []struct {
			Text string `json:"text"`
		} `json:"content"`
	}
	if err := json.Unmarshal([]byte(resultJSON), &payload); err != nil {
		return ""
	}
	if detail := strings.TrimSpace(payload.StructuredContent.Error); detail != "" {
		return detail
	}
	for _, block := range payload.Content {
		if detail := strings.TrimSpace(block.Text); detail != "" {
			return detail
		}
	}
	return ""
}

// dispatch 决定一次调用走本地执行还是穿越特权边界。
//
// 路由点放在这一层，是因为只有这里同时握有工具名与审批令牌：执行器的
// ExecuteTool(nodeID, toolName, argumentsJSON) 签名里没有令牌，把令牌一路透传下去
// 会让执行器、hosttools、mcp 三层都沾上特权语义。放在这里，执行器可以完全不知道
// 「特权」这回事。
func (r Runner) dispatch(ctx context.Context, call SafeToolCall) (string, error) {
	if workspace.IsWorkspaceTool(call.ToolName) {
		return r.callWorkspace(ctx, call)
	}
	if _, privileged := privops.OperationFor(call.ToolName); privileged {
		return r.callPrivHelper(ctx, call)
	}
	return r.callMCP(ctx, call)
}

// callPrivHelper 把写操作交给 root 特权助手执行（ADR-002）。
//
// 只透传被签名覆盖的身份字段与令牌本身——不发送命令行、不发送独立的结构化参数。
// 助手用自己持有的密钥重新校验，再自行解析参数、自行拼装命令。
func (r Runner) callPrivHelper(ctx context.Context, call SafeToolCall) (string, error) {
	_, span := otelsetup.Tracer().Start(ctx, "host.privhelper.call", trace.WithAttributes(traceAttributes(call)...))
	defer span.End()

	output, err := privilegedExecutor.Call(privops.Request{
		TaskID:             call.TaskID,
		NodeID:             call.NodeID,
		ToolName:           call.ToolName,
		RiskCode:           call.RiskCode,
		ArgumentsJSON:      call.ArgumentsJSON,
		ExecutionMode:      call.ExecutionMode,
		ApprovalToken:      call.ApprovalToken,
		TenantID:           call.TenantID,
		ToolInvocationID:   call.ToolInvocationID,
		ExecutionAttemptID: call.ExecutionAttemptID,
		AgentInstanceID:    call.AgentInstanceID,
		AgentInvocationID:  call.AgentInvocationID,
		ToolVersion:        call.ToolVersion,
		LockVersion:        call.LockVersion,
	})
	// 结果包成与本地 MCP 路径一致的形状，让下游（审计事件）无需区分来源。
	result := mcp.CallResult{
		StructuredContent: map[string]any{"tool": call.ToolName, "output": output, "via": "privhelper"},
		Content:           []mcp.ContentBlock{{Type: "text", Text: output}},
	}
	if err != nil {
		span.SetStatus(codes.Error, err.Error())
		result.IsError = true
		result.StructuredContent = map[string]any{"tool": call.ToolName, "error": err.Error(), "via": "privhelper"}
		result.Content = []mcp.ContentBlock{{Type: "text", Text: err.Error()}}
		return marshal(result), err
	}
	return marshal(result), nil
}

func (r Runner) callMCP(ctx context.Context, call SafeToolCall) (string, error) {
	ctx, span := otelsetup.Tracer().Start(ctx, "host.mcp.tools_call", trace.WithAttributes(traceAttributes(call)...))
	defer span.End()
	var arguments map[string]any
	if strings.TrimSpace(call.ArgumentsJSON) != "" {
		if err := json.Unmarshal([]byte(call.ArgumentsJSON), &arguments); err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, err.Error())
			return "", fmt.Errorf("parse SafeToolCall arguments: %w", err)
		}
	}
	if arguments == nil {
		arguments = map[string]any{}
	}
	server := mcp.NewServer(call.NodeID, r.runtime, r.currentCatalog())
	response := server.Handle(ctx, mcp.Request{
		JSONRPC: "2.0",
		ID:      call.CommandID,
		Method:  "tools/call",
		Params:  json.RawMessage(marshal(map[string]any{"name": call.ToolName, "arguments": arguments})),
	})
	if response.Error != nil {
		span.SetStatus(codes.Error, response.Error.Message)
		return "", fmt.Errorf("MCP tools/call failed: %s", response.Error.Message)
	}
	if result, ok := response.Result.(mcp.CallResult); ok && result.IsError {
		if encoded, err := json.Marshal(result); err == nil {
			span.SetStatus(codes.Error, "MCP tool returned error")
			return string(encoded), fmt.Errorf("MCP tool returned error")
		}
		span.SetStatus(codes.Error, "MCP tool returned error")
		return "", fmt.Errorf("MCP tool returned error")
	}
	encoded, err := json.Marshal(response.Result)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return "", fmt.Errorf("serialize MCP result: %w", err)
	}
	return string(encoded), nil
}

// callWorkspace 把 workspace 工具调用中转到 Workspace Runner（Podman 沙箱）。
//
// workspace 工具的安全边界由容器保证（无网络、无宿主访问、seccomp 限制），
// 因此这里不做审批令牌校验——控制面的 DispatchGate 在 gRPC 入口已经完成了授权。
func (r Runner) callWorkspace(ctx context.Context, call SafeToolCall) (string, error) {
	_, span := otelsetup.Tracer().Start(ctx, "host.workspace.call", trace.WithAttributes(traceAttributes(call)...))
	defer span.End()

	// WorkspaceID（来自 SessionID）是沙箱容器隔离键：容器名由 tenant/agent/workspace
	// 派生，空键会让同一 agent 的所有实例共用同一个容器，造成串实例共享，必须 fail-closed。
	if strings.TrimSpace(call.SessionID) == "" {
		err := fmt.Errorf("拒绝执行 workspace 调用：WorkspaceID 为空（SessionID 未赋值），会导致多实例共享沙箱")
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return "", err
	}

	action, ok := workspace.ActionFor(call.ToolName)
	if !ok {
		span.SetStatus(codes.Error, "未知 workspace 工具")
		return "", fmt.Errorf("未知 workspace 工具：%s", call.ToolName)
	}

	arguments, err := workspace.BuildArguments(call.ToolName, call.ArgumentsJSON)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		return "", fmt.Errorf("构建 workspace 参数失败：%w", err)
	}

	resp, err := workspaceCaller.Call(workspace.Request{
		RequestID:   call.CommandID,
		TenantID:    call.TenantID,
		AgentID:     call.AgentInstanceID,
		WorkspaceID: call.SessionID,
		Action:      action,
		Arguments:   arguments,
	})
	// 验收基线 18.3：失败就是失败。传输成功（err==nil）不代表执行成功——
	// workspace runner 对 exec 用 ExitCode/Error 表达失败。若这里不转成错误，
	// 一段恰好能解析的失败输出会被下游误判为 COMPLETED/SUCCEEDED。
	if err == nil && strings.TrimSpace(resp.Error) != "" {
		err = fmt.Errorf("workspace runner 报告失败：%s", resp.Error)
	}
	if err == nil && resp.ExitCode != 0 {
		err = fmt.Errorf("workspace 命令退出码 %d：%s", resp.ExitCode, strings.TrimSpace(resp.Output))
	}
	result := mcp.CallResult{
		StructuredContent: map[string]any{"tool": call.ToolName, "output": resp.Output, "exitCode": resp.ExitCode, "via": "workspace"},
		Content:           []mcp.ContentBlock{{Type: "text", Text: resp.Output}},
	}
	if err != nil {
		span.SetStatus(codes.Error, err.Error())
		result.IsError = true
		result.StructuredContent = map[string]any{"tool": call.ToolName, "error": err.Error(), "via": "workspace"}
		result.Content = []mcp.ContentBlock{{Type: "text", Text: err.Error()}}
		return marshal(result), err
	}
	return marshal(result), nil
}

func (r Runner) currentCatalog() mcp.Catalog {
	if r.catalogProvider != nil {
		return r.catalogProvider()
	}
	return r.catalog
}

func checkLocalGuardrail(call SafeToolCall, catalog mcp.Catalog, runtime Runtime) (string, error) {
	if strings.TrimSpace(call.CommandID) == "" || strings.TrimSpace(call.TaskID) == "" || strings.TrimSpace(call.NodeID) == "" || strings.TrimSpace(call.TraceID) == "" {
		return "DENY", fmt.Errorf("SafeToolCall missing required identity or trace fields")
	}
	if !catalog.IsSupportedTool(call.ToolName) {
		return "DENY", fmt.Errorf("unsupported MCP tool: %s", call.ToolName)
	}
	// Workspace 工具在 Podman 沙箱内执行，安全边界由容器保证（无网络、无宿主、seccomp）。
	// 跳过：approval token、resource lock、NativeGuard、PLAN mode 限制。
	if workspace.IsWorkspaceTool(call.ToolName) {
		return "ALLOW", nil
	}
	risk := normalizeRisk(call.RiskCode)
	if risk == "" {
		return "DENY", fmt.Errorf("invalid risk code: %s", call.RiskCode)
	}
	if risk == "R5" {
		return "DENY", fmt.Errorf("R5 action is forbidden")
	}
	// 计划模式只读（ADR-001 §2.10.2/§2.10.3）：端侧独立判定，不依赖控制面自我约束。
	// 控制面出现缺陷或被绕过时，这条是最后一道能拦住写操作的检查。
	if normalizeExecutionMode(call.ExecutionMode) == "PLAN" && riskRank(risk) >= riskRank("R2") {
		return "DENY", fmt.Errorf("plan mode forbids write action %s (risk %s)", call.ToolName, risk)
	}
	minRisk := catalog.MinimumToolRisk(call.ToolName)
	if minRisk == "" {
		minRisk = "R0"
	}
	if riskRank(risk) < riskRank(minRisk) {
		return "DENY", fmt.Errorf("tool %s requires at least %s risk classification", call.ToolName, minRisk)
	}
	if (risk == "R3" || risk == "R4") && strings.TrimSpace(call.ApprovalToken) == "" {
		return "DENY", fmt.Errorf("%s action requires approval token", risk)
	}
	if (risk == "R3" || risk == "R4") && !approvalTokenHasValidShape(call) {
		// 只校验形态；令牌真伪由持有密钥的特权助手在执行前权威判定（ADR-002 §3.4）。
		return "DENY", fmt.Errorf("%s action approval token is malformed", risk)
	}
	if (risk == "R3" || risk == "R4") && len(call.ResourceLocks) == 0 {
		return "DENY", fmt.Errorf("%s action requires resource locks", risk)
	}
	if (risk == "R3" || risk == "R4") && !isSecurityStatusTool(call.ToolName) {
		if err := checkNativeGuardPosture(call, runtime); err != nil {
			return "DENY", err
		}
	}
	for _, lock := range call.ResourceLocks {
		if strings.TrimSpace(lock.ResourceKey) == "" || strings.TrimSpace(lock.LockType) == "" {
			return "DENY", fmt.Errorf("resource lock is incomplete")
		}
		if (risk == "R3" || risk == "R4") && strings.TrimSpace(lock.LockType) != "WRITE_EXCLUSIVE" {
			return "DENY", fmt.Errorf("%s action requires WRITE_EXCLUSIVE resource lock", risk)
		}
	}
	return "ALLOW", nil
}

func checkNativeGuardPosture(call SafeToolCall, runtime Runtime) error {
	provider, ok := runtime.(nativeGuardPostureProvider)
	if !ok {
		return nil
	}
	payload := provider.NativeGuardPosturePayload(call.NodeID)
	encoded, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("Native Guard posture is unavailable")
	}
	var posture struct {
		Items []struct {
			Tool      string `json:"tool"`
			Supported bool   `json:"supported"`
			Status    string `json:"status"`
		} `json:"items"`
	}
	if err := json.Unmarshal(encoded, &posture); err != nil {
		return fmt.Errorf("Native Guard posture is invalid")
	}
	for _, item := range posture.Items {
		if !item.Supported {
			continue
		}
		tool := strings.TrimSpace(item.Tool)
		status := strings.ToLower(strings.TrimSpace(item.Status))
		if tool == "kylin.security.security_switch_status" && isCriticalNativeGuardStatus(status) {
			return fmt.Errorf("security-switch posture is %s; R3/R4 action is denied locally", status)
		}
		if tool == "kylin.security.kysec_status" && isCriticalNativeGuardStatus(status) {
			return fmt.Errorf("KYSEC posture is %s; R3/R4 action is denied locally", status)
		}
		if tool == "kylin.security.auditd_status" && isCriticalNativeGuardStatus(status) {
			return fmt.Errorf("auditd posture is %s; R3/R4 action is denied locally", status)
		}
	}
	return nil
}

func isSecurityStatusTool(toolName string) bool {
	return strings.HasPrefix(strings.TrimSpace(toolName), "kylin.security.") && strings.HasSuffix(strings.TrimSpace(toolName), "_status")
}

func isCriticalNativeGuardStatus(status string) bool {
	switch status {
	case "disabled", "inactive", "failed", "off", "error":
		return true
	default:
		return false
	}
}

// normalizeRisk 把风险码归一到与后端 RiskCode 枚举一致的取值。
//
// 未知取值返回空串，是刻意的 fail-closed：门禁遇到无法识别的风险码一律拒绝，
// 而不是"顺手"归一到某个可预测的等级。
func normalizeRisk(value string) string {
	switch strings.ToUpper(strings.TrimSpace(value)) {
	case "", "LOW", "R0":
		return "R0"
	case "R1":
		return "R1"
	case "MEDIUM", "R2":
		return "R2"
	case "R3":
		return "R3"
	case "HIGH", "R4":
		return "R4"
	case "R5":
		return "R5"
	default:
		return ""
	}
}

func riskRank(risk string) int {
	switch risk {
	case "R0":
		return 0
	case "R1":
		return 1
	case "R2":
		return 2
	case "R3":
		return 3
	case "R4":
		return 4
	case "R5":
		return 5
	default:
		return -1
	}
}

func marshal(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "{}"
	}
	return string(encoded)
}

func event(eventType string, riskCode string, payloadJSON string) RuntimeEvent {
	return RuntimeEvent{EventType: eventType, RiskCode: normalizeRisk(riskCode), PayloadJSON: payloadJSON}
}

func errorString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func traceAttributes(call SafeToolCall) []attribute.KeyValue {
	return []attribute.KeyValue{
		attribute.String("host.task_id", call.TaskID),
		attribute.String("host.node_id", call.NodeID),
		attribute.String("safeops.command_id", call.CommandID),
		attribute.String("safeops.tool_name", call.ToolName),
		attribute.String("safeops.risk_code", call.RiskCode),
		attribute.Int("safeops.arguments_bytes", len(call.ArgumentsJSON)),
	}
}
