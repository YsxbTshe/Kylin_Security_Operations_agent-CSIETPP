package safeops

import (
	"context"
	"encoding/json"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/privops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/workspace"
	"strings"
	"sync"
	"testing"
	"time"
)

type runnerFakeRuntime struct{}

func (runnerFakeRuntime) ExecuteTool(string, string, string) (string, error) {
	return "ok", nil
}

// fakePrivilegedExecutor 代替真实的特权助手，记录 daemon 究竟送出去了什么。
type fakePrivilegedExecutor struct {
	lastRequest privops.Request
}

func (f *fakePrivilegedExecutor) Call(request privops.Request) (string, error) {
	f.lastRequest = request
	return "restarted", nil
}

func TestRunnerDeniesR5(t *testing.T) {
	runner := NewRunner(runnerFakeRuntime{})

	report := runner.ExecuteReport(context.Background(), SafeToolCall{
		CommandID:     "cmd-1",
		TaskID:        "task-1",
		NodeID:        "node-1",
		ToolName:      "kylin.disk.usage",
		ArgumentsJSON: "{}",
		RiskCode:      "R5",
		TraceID:       "trace-1",
	})

	if report.Result.Status != "DENIED" || report.Result.LocalGuardrailDecision != "DENY" {
		t.Fatalf("expected R5 to be denied, got %+v", report.Result)
	}
}

func TestRunnerRequiresApprovalForR3(t *testing.T) {
	runner := NewRunner(runnerFakeRuntime{})

	report := runner.ExecuteReport(context.Background(), SafeToolCall{
		CommandID:     "cmd-1",
		TaskID:        "task-1",
		NodeID:        "node-1",
		ToolName:      "kylin.service.restart",
		ArgumentsJSON: `{"service":"nginx"}`,
		RiskCode:      "R3",
		TraceID:       "trace-1",
		ResourceLocks: []ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}},
	})

	if report.Result.Status != "DENIED" {
		t.Fatalf("expected missing approval token to be denied, got %+v", report.Result)
	}
}

// 已审批的 R3 写操作要一路执行到底。落点是特权助手而不是本地 MCP——
// kylin.service.restart 需要 OS 特权，daemon 自身不再有能力执行它（ADR-002）。
func TestRunnerExecutesApprovedR3ThroughPrivHelper(t *testing.T) {
	helper := &fakePrivilegedExecutor{}
	SetPrivilegedExecutor(helper)
	defer SetPrivilegedExecutor(unavailablePrivilegedExecutor{})

	runner := NewRunner(runnerFakeRuntime{})

	call := SafeToolCall{
		CommandID:     "cmd-1",
		TaskID:        "task-1",
		NodeID:        "node-1",
		ToolName:      "kylin.service.restart",
		ArgumentsJSON: `{"service":"nginx"}`,
		RiskCode:      "R3",
		TraceID:       "trace-1",
		ApprovalToken: approvalTokenForTest(SafeToolCall{TaskID: "task-1", NodeID: "node-1", ToolName: "kylin.service.restart", ArgumentsJSON: `{"service":"nginx"}`, RiskCode: "R3"}),
		ResourceLocks: []ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}},
	}
	report := runner.ExecuteReport(context.Background(), call)

	if report.Result.Status != "COMPLETED" || report.Result.LocalGuardrailDecision != "ALLOW" {
		t.Fatalf("expected approved R3 to complete, got %+v", report.Result)
	}
	if len(report.Events) == 0 || report.Events[len(report.Events)-1].EventType != "TASK_COMPLETED" {
		t.Fatalf("expected final TASK_COMPLETED event, got %+v", report.Events)
	}
	// 令牌必须原样送到助手：助手要用它做权威校验，daemon 侧的判定不作数。
	if helper.lastRequest.ApprovalToken != call.ApprovalToken {
		t.Fatalf("审批令牌未透传给助手，实际 %q", helper.lastRequest.ApprovalToken)
	}
	if helper.lastRequest.ToolName != "kylin.service.restart" {
		t.Fatalf("工具名未透传给助手，实际 %q", helper.lastRequest.ToolName)
	}
}

// 助手不可达时必须失败，绝不能退回本地执行——那等于绕过特权边界。
func TestPrivilegedToolFailsWhenHelperUnavailable(t *testing.T) {
	SetPrivilegedExecutor(unavailablePrivilegedExecutor{})
	runner := NewRunner(runnerFakeRuntime{})

	call := SafeToolCall{
		CommandID: "cmd-nohelper", TaskID: "task-nohelper", NodeID: "node-1",
		ToolName: "kylin.service.restart", ArgumentsJSON: `{"service":"nginx"}`,
		RiskCode: "R3", TraceID: "trace-nohelper",
		ResourceLocks: []ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}},
	}
	call.ApprovalToken = approvalTokenForTest(call)

	report := runner.ExecuteReport(context.Background(), call)
	// 关心的是「通过了门禁、但在执行阶段因边界不可用而失败」，
	// 而不是被门禁挡下——后者证明不了任何关于特权边界的事。
	if report.Result.LocalGuardrailDecision != "ALLOW" {
		t.Fatalf("本用例应通过本地门禁，实际 %s（%s）", report.Result.LocalGuardrailDecision, report.Result.Message)
	}
	if report.Result.Status != "FAILED" {
		t.Fatalf("助手不可达时必须失败，实际 %s", report.Result.Status)
	}
	if !strings.Contains(report.Result.Message, "特权助手未配置") {
		t.Fatalf("失败原因应指明特权助手不可用，实际 %q", report.Result.Message)
	}
}

// daemon 侧门禁只校验令牌骨架；骨架非法（这里不是合法的 base64url protobuf）即在本地拒绝，
// 不必联系助手。旧 HMAC 六元组形态同样在本地直接被拒。
// 令牌与任务/参数是否绑定一致，由持有公钥的助手在执行前判定（见 privops 的 helper 测试）。
func TestRunnerDeniesR3WhenApprovalTokenMalformed(t *testing.T) {
	runner := NewRunner(runnerFakeRuntime{})

	for _, malformed := range []string{
		"kylin-approval-v1.!!!not-base64",
		"approval-token-v2-0000000000000000-" + strings.Repeat("0", 64), // 已退役的 HMAC 形态
	} {
		report := runner.ExecuteReport(context.Background(), SafeToolCall{
			CommandID:     "cmd-1",
			TaskID:        "task-1",
			NodeID:        "node-1",
			ToolName:      "kylin.service.restart",
			ArgumentsJSON: `{"service":"nginx"}`,
			RiskCode:      "R3",
			TraceID:       "trace-1",
			ApprovalToken: malformed,
			ResourceLocks: []ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}},
		})

		if report.Result.Status != "DENIED" || report.Result.LocalGuardrailDecision != "DENY" {
			t.Fatalf("expected malformed approval token %q to be denied, got %+v", malformed, report.Result)
		}
	}
}

func TestRunnerEventsIncludeTraceContext(t *testing.T) {
	runner := NewRunner(runnerFakeRuntime{})

	report := runner.ExecuteReport(context.Background(), SafeToolCall{
		CommandID:     "cmd-1",
		TaskID:        "task-1",
		NodeID:        "node-1",
		ToolName:      "kylin.disk.usage",
		ArgumentsJSON: "{}",
		RiskCode:      "R0",
		TraceID:       "0123456789abcdef0123456789abcdef",
		SubtaskID:     "cmd-1",
		AgentRole:     "ExecutionAgent",
		ParentSpanID:  "task:task-1",
	})

	if len(report.Events) == 0 {
		t.Fatal("expected runtime events")
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(report.Events[0].PayloadJSON), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["traceparent"] == "" || payload["parentSpanId"] != "cmd-1" || payload["subtaskId"] != "cmd-1" || payload["agentRole"] != "ExecutionAgent" {
		t.Fatalf("expected trace context in payload, got %+v", payload)
	}
	if payload["w3cTraceId"] != "0123456789abcdef0123456789abcdef" {
		t.Fatalf("expected W3C trace id, got %+v", payload)
	}
}

func TestRunnerDeniesR3WhenNativeGuardIsCritical(t *testing.T) {
	runner := NewRunner(criticalNativeGuardRuntime{})

	report := runner.ExecuteReport(context.Background(), SafeToolCall{
		CommandID:     "cmd-1",
		TaskID:        "task-1",
		NodeID:        "node-1",
		ToolName:      "kylin.service.restart",
		ArgumentsJSON: `{"service":"nginx"}`,
		RiskCode:      "R3",
		TraceID:       "trace-1",
		ApprovalToken: approvalTokenForTest(SafeToolCall{TaskID: "task-1", NodeID: "node-1", ToolName: "kylin.service.restart", ArgumentsJSON: `{"service":"nginx"}`, RiskCode: "R3"}),
		ResourceLocks: []ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}},
	})

	if report.Result.Status != "DENIED" || report.Result.LocalGuardrailDecision != "DENY" {
		t.Fatalf("expected Native Guard critical posture to deny R3 action, got %+v", report.Result)
	}
}

type criticalNativeGuardRuntime struct {
}

func (criticalNativeGuardRuntime) ExecuteTool(string, string, string) (string, error) {
	return "ok", nil
}

func (criticalNativeGuardRuntime) NativeGuardPosturePayload(string) any {
	return map[string]any{
		"overall": "critical",
		"items": []map[string]any{
			{"tool": "kylin.security.security_switch_status", "supported": true, "status": "off"},
		},
	}
}

type countingRuntime struct {
	count int
}

func (r *countingRuntime) ExecuteTool(string, string, string) (string, error) {
	r.count++
	return "ok", nil
}

func TestSchedulerCachesIdempotentResult(t *testing.T) {
	runtime := &countingRuntime{}
	scheduler := NewScheduler(NewRunner(runtime))
	call := SafeToolCall{CommandID: "cmd-1", TaskID: "task-1", NodeID: "node-1", ToolName: "kylin.disk.usage", ArgumentsJSON: `{}`, RiskCode: "R0", TraceID: "trace-1", IdempotencyKey: "task-1:cmd-1"}

	first := scheduler.ExecuteReport(context.Background(), call)
	second := scheduler.ExecuteReport(context.Background(), call)

	if first.Result.Status != "COMPLETED" || second.Result.Status != "COMPLETED" {
		t.Fatalf("expected completed cached executions, got first=%+v second=%+v", first.Result, second.Result)
	}
	if runtime.count != 1 {
		t.Fatalf("expected runtime to execute once, got %d", runtime.count)
	}
}

func TestSchedulerDeniesPausedTask(t *testing.T) {
	scheduler := NewScheduler(NewRunner(runnerFakeRuntime{}))
	scheduler.Pause("task-1")

	report := scheduler.ExecuteReport(context.Background(), SafeToolCall{CommandID: "cmd-1", TaskID: "task-1", NodeID: "node-1", ToolName: "kylin.disk.usage", ArgumentsJSON: `{}`, RiskCode: "R0", TraceID: "trace-1"})

	if report.Result.Status != "DENIED" {
		t.Fatalf("expected paused task to be denied, got %+v", report.Result)
	}
}

func TestSchedulerPersistsState(t *testing.T) {
	store := NewFileStateStore(t.TempDir())
	firstRuntime := &countingRuntime{}
	first := NewScheduler(NewRunner(firstRuntime), store)
	call := SafeToolCall{CommandID: "cmd-1", TaskID: "task-1", NodeID: "node-1", ToolName: "kylin.disk.usage", ArgumentsJSON: `{}`, RiskCode: "R0", TraceID: "trace-1", IdempotencyKey: "task-1:cmd-1"}
	if report := first.ExecuteReport(context.Background(), call); report.Result.Status != "COMPLETED" {
		t.Fatalf("expected first execution completed, got %+v", report.Result)
	}
	first.Pause("task-2")

	secondRuntime := &countingRuntime{}
	second := NewScheduler(NewRunner(secondRuntime), store)
	if report := second.ExecuteReport(context.Background(), call); report.Result.Status != "COMPLETED" {
		t.Fatalf("expected cached execution completed, got %+v", report.Result)
	}
	if secondRuntime.count != 0 {
		t.Fatalf("expected cached result to avoid runtime execution, got count=%d", secondRuntime.count)
	}
	paused := second.ExecuteReport(context.Background(), SafeToolCall{CommandID: "cmd-2", TaskID: "task-2", NodeID: "node-1", ToolName: "kylin.disk.usage", ArgumentsJSON: `{}`, RiskCode: "R0", TraceID: "trace-2"})
	if paused.Result.Status != "DENIED" {
		t.Fatalf("expected persisted pause to deny task, got %+v", paused.Result)
	}
}

func TestSchedulerRunnerPoolLimitsConcurrentExecutions(t *testing.T) {
	runtime := &blockingRuntime{
		started: make(chan string, 2),
		release: make(chan struct{}),
	}
	scheduler := NewSchedulerWithRunnerPool(NewRunner(runtime), 2, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	var wg sync.WaitGroup
	wg.Add(2)
	for _, commandID := range []string{"cmd-1", "cmd-2"} {
		go func(commandID string) {
			defer wg.Done()
			scheduler.ExecuteReport(ctx, SafeToolCall{CommandID: commandID, TaskID: commandID, NodeID: "node-1", ToolName: "kylin.disk.usage", ArgumentsJSON: `{}`, RiskCode: "R0", TraceID: "trace-" + commandID})
		}(commandID)
	}
	first := <-runtime.started
	if first == "" {
		t.Fatal("expected first runner execution")
	}
	select {
	case second := <-runtime.started:
		t.Fatalf("expected runner pool size 1 to block second execution, got %s", second)
	case <-time.After(100 * time.Millisecond):
	}
	snapshot := scheduler.Snapshot()
	if snapshot.RunnerPoolSize != 1 || snapshot.ActiveRunners != 1 || len(snapshot.Running) != 1 || snapshot.Running[0].RunnerID != "runner-1" {
		t.Fatalf("unexpected runner pool snapshot: %+v", snapshot)
	}
	close(runtime.release)
	wg.Wait()
	if runtime.maxActive != 1 {
		t.Fatalf("expected at most one active runner, got %d", runtime.maxActive)
	}
}

type blockingRuntime struct {
	mu        sync.Mutex
	active    int
	maxActive int
	started   chan string
	release   chan struct{}
}

func (r *blockingRuntime) ExecuteTool(_ string, toolName string, _ string) (string, error) {
	r.mu.Lock()
	r.active++
	if r.active > r.maxActive {
		r.maxActive = r.active
	}
	r.mu.Unlock()
	r.started <- toolName
	<-r.release
	r.mu.Lock()
	r.active--
	r.mu.Unlock()
	return "ok", nil
}

func TestSchedulerStoresPendingCallsForOfflineReplay(t *testing.T) {
	store := NewFileStateStore(t.TempDir())
	call := SafeToolCall{CommandID: "cmd-replay", TaskID: "task-replay", NodeID: "node-1", ToolName: "kylin.disk.usage", ArgumentsJSON: `{}`, RiskCode: "R0", TraceID: "trace-replay", IdempotencyKey: "cmd-replay"}
	first := NewScheduler(NewRunner(runnerFakeRuntime{}), store)

	if !first.StorePending(call) {
		t.Fatal("expected first StorePending to persist command")
	}
	if first.StorePending(call) {
		t.Fatal("expected duplicate StorePending to be ignored")
	}

	secondRuntime := &countingRuntime{}
	second := NewScheduler(NewRunner(secondRuntime), store)
	pending := second.PendingCalls()
	if len(pending) != 1 || pending[0].CommandID != "cmd-replay" {
		t.Fatalf("expected restored pending command, got %+v", pending)
	}
	if report := second.ExecuteReport(context.Background(), pending[0]); report.Result.Status != "COMPLETED" {
		t.Fatalf("expected replayed pending command to complete, got %+v", report.Result)
	}
	if secondRuntime.count != 1 {
		t.Fatalf("expected replayed pending command to execute once, got %d", secondRuntime.count)
	}

	thirdRuntime := &countingRuntime{}
	third := NewScheduler(NewRunner(thirdRuntime), store)
	if got := third.PendingCalls(); len(got) != 0 {
		t.Fatalf("expected no pending commands after replay, got %+v", got)
	}
	if report := third.ExecuteReport(context.Background(), call); report.Result.Status != "COMPLETED" {
		t.Fatalf("expected cached replay result, got %+v", report.Result)
	}
	if thirdRuntime.count != 0 {
		t.Fatalf("expected cached replay result to avoid duplicate execution, got %d", thirdRuntime.count)
	}
}

// fakeWorkspaceCaller 记录送往 workspace runner 的请求，代替真实 Unix Socket 客户端。
type fakeWorkspaceCaller struct {
	calls       int
	lastRequest workspace.Request
}

func (f *fakeWorkspaceCaller) Call(req workspace.Request) (workspace.Response, error) {
	f.calls++
	f.lastRequest = req
	return workspace.Response{Output: "ok"}, nil
}

// WorkspaceID（SessionID）为空必须直接拒绝，且不得触达 workspace runner：
// 沙箱容器名由 tenant/agent/workspace 派生，空键会让多实例共享同一容器。
// 验收基线 18.2（安全）：隔离键缺失必须 fail-closed。
func TestRunnerCallWorkspaceRejectsEmptyWorkspaceID(t *testing.T) {
	caller := &fakeWorkspaceCaller{}
	SetWorkspaceCaller(caller)
	defer ResetWorkspaceCallerForTest()

	runner := NewRunner(runnerFakeRuntime{})
	_, err := runner.callWorkspace(context.Background(), SafeToolCall{
		CommandID:       "cmd-ws",
		TaskID:          "task-ws",
		NodeID:          "node-1",
		ToolName:        "kylin.workspace.bash",
		ArgumentsJSON:   `{"script":"echo hi"}`,
		TenantID:        "tenant-1",
		AgentInstanceID: "agent-instance-1",
		// SessionID 故意留空：它是 WorkspaceID 的来源
	})
	if err == nil || !strings.Contains(err.Error(), "WorkspaceID 为空") {
		t.Fatalf("空 WorkspaceID 必须被拒绝，实际 %v", err)
	}
	if caller.calls != 0 {
		t.Fatalf("空 WorkspaceID 的调用不得到达 workspace runner，实际被调用 %d 次", caller.calls)
	}
}

// 隔离键齐备时必须原样转发：WorkspaceID = SessionID、AgentID = AgentInstanceID，
// 保证每个 AgentInstance 拥有独立沙箱容器。
func TestRunnerCallWorkspaceForwardsInstanceScopedKey(t *testing.T) {
	caller := &fakeWorkspaceCaller{}
	SetWorkspaceCaller(caller)
	defer ResetWorkspaceCallerForTest()

	runner := NewRunner(runnerFakeRuntime{})
	_, err := runner.callWorkspace(context.Background(), SafeToolCall{
		CommandID:       "cmd-ws",
		TaskID:          "task-ws",
		NodeID:          "node-1",
		ToolName:        "kylin.workspace.bash",
		ArgumentsJSON:   `{"script":"echo hi"}`,
		TenantID:        "tenant-1",
		SessionID:       "agent-instance-1",
		AgentInstanceID: "agent-instance-1",
	})
	if err != nil {
		t.Fatalf("callWorkspace() error = %v", err)
	}
	if caller.lastRequest.WorkspaceID != "agent-instance-1" || caller.lastRequest.AgentID != "agent-instance-1" {
		t.Fatalf("隔离键未按预期转发：%+v", caller.lastRequest)
	}
}

// failingWorkspaceCaller 模拟脚本退出码非零 / runner 报错的应答。
type failingWorkspaceCaller struct {
	response workspace.Response
}

func (f *failingWorkspaceCaller) Call(req workspace.Request) (workspace.Response, error) {
	return f.response, nil
}

// 验收基线 18.3：输出必须通过 outputSchema 校验，失败的输出不得因「恰好可解析」
// 被记为成功。workspace 命令退出码非零时，即使输出是合法 JSON 也必须判失败。
func TestRunnerCallWorkspaceFailsOnNonZeroExitCode(t *testing.T) {
	caller := &failingWorkspaceCaller{response: workspace.Response{
		Output:   `{"looks":"valid"}`, // 恰好能解析的输出——仍不得记为成功
		ExitCode: 1,
	}}
	SetWorkspaceCaller(caller)
	defer ResetWorkspaceCallerForTest()

	runner := NewRunner(runnerFakeRuntime{})
	report := runner.ExecuteReport(context.Background(), SafeToolCall{
		CommandID:       "cmd-ws-fail",
		TaskID:          "task-ws",
		NodeID:          "node-1",
		TraceID:         "tr-ws-fail",
		ToolName:        "kylin.workspace.bash",
		ArgumentsJSON:   `{"script":"exit 1"}`,
		TenantID:        "tenant-1",
		SessionID:       "agent-instance-1",
		AgentInstanceID: "agent-instance-1",
	})
	if report.Result.Status != "FAILED" {
		t.Fatalf("非零退出码必须映射为 FAILED，实际 %s（%s）", report.Result.Status, report.Result.Message)
	}
}

// 验收基线 18.3：workspace runner 应答携带 Error 时同样必须判失败，
// 不能只看传输是否成功（err==nil）。
func TestRunnerCallWorkspaceFailsOnRunnerReportedError(t *testing.T) {
	caller := &failingWorkspaceCaller{response: workspace.Response{
		Output: "partial output",
		Error:  "sandbox exec failed: permission denied",
	}}
	SetWorkspaceCaller(caller)
	defer ResetWorkspaceCallerForTest()

	runner := NewRunner(runnerFakeRuntime{})
	result := runner.Execute(context.Background(), SafeToolCall{
		CommandID:       "cmd-ws-err",
		TaskID:          "task-ws",
		NodeID:          "node-1",
		TraceID:         "tr-ws-err",
		ToolName:        "kylin.workspace.bash",
		ArgumentsJSON:   `{"script":"cat /etc/shadow"}`,
		TenantID:        "tenant-1",
		SessionID:       "agent-instance-1",
		AgentInstanceID: "agent-instance-1",
	})
	if result.Status != "FAILED" {
		t.Fatalf("workspace Error 应答必须映射为 FAILED，实际 %s（%s）", result.Status, result.Message)
	}
}
