package controlstreamv2

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	v2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/gmcrypto"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/mcp"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/safeops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/pkg/model"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const (
	protocolVersion uint32 = 2
	executorVersion        = "0.2.0-v2"
)

type Client struct {
	EnrollAddress   string
	ControlAddress  string
	EnrollmentToken string
	TenantID        string
	NodeID          string
	CertStore       CertStore
	SessionDir      string
	Runtime         safeops.Runtime
	Catalog         mcp.Catalog
	MaxParallel     int
	RunnerPoolSize  int
	MetricsSnapshot func(MetricsSnapshot)
}

type MetricsSnapshot struct {
	Scheduler   safeops.SchedulerSnapshot
	UpdatedUnix int64
	Connected   bool
}

func (c Client) Run(ctx context.Context, snapshotProvider func() model.SystemSnapshot) error {
	for {
		if err := c.runOnce(ctx, snapshotProvider); err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			log.Printf("ControlStream V2 disconnected: %v; reconnecting", err)
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(5 * time.Second):
			}
			continue
		}
		return nil
	}
}

func (c Client) EnsureEnrolled(ctx context.Context, snapshot model.SystemSnapshot) error {
	if c.CertStore.Ready() {
		return nil
	}
	csr, err := c.CertStore.GenerateCSR(c.NodeID, c.TenantID, snapshot.Hostname)
	if err != nil {
		return fmt.Errorf("generate V2 CSR: %w", err)
	}
	tlsConfig, err := enrollTLSConfig()
	if err != nil {
		return err
	}
	creds := credentials.NewTLS(tlsConfig)
	conn, err := grpc.NewClient(c.EnrollAddress, grpc.WithTransportCredentials(creds))
	if err != nil {
		return fmt.Errorf("connect V2 enrollment: %w", err)
	}
	defer conn.Close()
	response, err := v2.NewNodeExecutorControlServiceClient(conn).EnrollNode(ctx, &v2.EnrollNodeRequest{
		EnrollmentToken: c.EnrollmentToken,
		TenantId:        c.TenantID,
		NodeId:          c.NodeID,
		Hostname:        snapshot.Hostname,
		MachineId:       machineID(),
		ExecutorVersion: executorVersion,
		CsrPem:          csr,
	})
	if err != nil {
		return fmt.Errorf("enroll Node Executor V2: %w", err)
	}
	if !response.GetAccepted() {
		return fmt.Errorf("V2 enrollment rejected: %s %s", response.GetRejectionCode(), response.GetMessage())
	}
	if err := c.CertStore.Save(response.GetNodeCertificatePem(), response.GetNodeCaCertificatePem()); err != nil {
		return fmt.Errorf("save V2 certificate bundle: %w", err)
	}
	log.Printf("Node Executor enrolled through protocol V2: node_id=%s", c.NodeID)
	return nil
}

func (c Client) runOnce(ctx context.Context, snapshotProvider func() model.SystemSnapshot) error {
	if c.Runtime == nil {
		return fmt.Errorf("ControlStream V2 runtime is nil")
	}
	if err := c.EnsureEnrolled(ctx, snapshotProvider()); err != nil {
		return err
	}
	conn, err := c.mtlsConnection()
	if err != nil {
		return err
	}
	defer conn.Close()
	stream, err := v2.NewNodeExecutorControlServiceClient(conn).ControlStream(ctx)
	if err != nil {
		return fmt.Errorf("open ControlStream V2: %w", err)
	}
	catalog := c.Catalog
	if len(catalog.SupportedToolNames()) == 0 {
		catalog = mcp.NewCatalog()
	}
	runner := safeops.NewRunner(c.Runtime, catalog)
	scheduler := safeops.NewSchedulerWithRunnerPool(runner, c.MaxParallel, c.RunnerPoolSize,
		safeops.NewFileStateStore(c.SessionDir))
	var nodeSequence atomic.Uint64
	var lastControlSequence atomic.Uint64
	var sendMu sync.Mutex
	send := func(request *v2.ControlStreamRequest) error {
		sendMu.Lock()
		defer sendMu.Unlock()
		request.ProtocolVersion = protocolVersion
		request.Sequence = nodeSequence.Add(1)
		request.SentAt = timestamppb.Now()
		return stream.Send(request)
	}
	updateMetrics := func(connected bool) {
		if c.MetricsSnapshot != nil {
			c.MetricsSnapshot(MetricsSnapshot{Scheduler: scheduler.Snapshot(), UpdatedUnix: time.Now().Unix(), Connected: connected})
		}
	}
	updateMetrics(true)
	defer updateMetrics(false)

	guard := newNativeGuardReporter(c.Runtime, c.NodeID)
	if err := send(&v2.ControlStreamRequest{Payload: &v2.ControlStreamRequest_Capabilities{Capabilities: c.capabilities(catalog)}}); err != nil {
		return fmt.Errorf("send V2 capabilities: %w", err)
	}
	if err := send(&v2.ControlStreamRequest{Payload: &v2.ControlStreamRequest_Heartbeat{Heartbeat: c.heartbeat(
		scheduler, catalog, nodeSequence.Load(), lastControlSequence.Load(), guard)}}); err != nil {
		return fmt.Errorf("send initial V2 heartbeat: %w", err)
	}

	streamCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	errCh := make(chan error, 1)
	reportError := func(err error) {
		select {
		case errCh <- err:
		default:
		}
		cancel()
	}
	var executionWG sync.WaitGroup
	var inFlight sync.Map
	startExecution := func(call safeops.SafeToolCall) {
		if _, loaded := inFlight.LoadOrStore(call.CommandID, struct{}{}); loaded {
			return
		}
		executionWG.Add(1)
		go func() {
			defer executionWG.Done()
			defer inFlight.Delete(call.CommandID)
			report := scheduler.ExecuteReport(streamCtx, call)
			if err := send(c.invocationResult(call, report.Result)); err != nil {
				reportError(fmt.Errorf("send V2 invocation result: %w", err))
			}
			updateMetrics(true)
		}()
	}
	for _, pending := range scheduler.PendingCalls() {
		startExecution(pending)
	}

	go func() {
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-streamCtx.Done():
				return
			case <-ticker.C:
				if err := send(&v2.ControlStreamRequest{Payload: &v2.ControlStreamRequest_Heartbeat{Heartbeat: c.heartbeat(
					scheduler, catalog, nodeSequence.Load(), lastControlSequence.Load(), guard)}}); err != nil {
					reportError(fmt.Errorf("send V2 heartbeat: %w", err))
					return
				}
				updateMetrics(true)
			}
		}
	}()

	go func() {
		for {
			response, err := stream.Recv()
			if err != nil {
				reportError(fmt.Errorf("receive V2 control message: %w", err))
				return
			}
			if response.GetProtocolVersion() != protocolVersion {
				reportError(fmt.Errorf("unsupported control protocol version %d", response.GetProtocolVersion()))
				return
			}
			if sequence := response.GetSequence(); sequence == 0 || sequence <= lastControlSequence.Load() {
				continue
			} else {
				lastControlSequence.Store(sequence)
			}
			switch payload := response.Payload.(type) {
			case *v2.ControlStreamResponse_Submit:
				call, err := c.safeToolCall(payload.Submit, catalog)
				if err != nil {
					_ = send(c.rejectedResult(payload.Submit, "NODE_POLICY_REJECTED", err.Error()))
					continue
				}
				state, _, cached, found := scheduler.Lookup(call.CommandID, call.IdempotencyKey)
				if found {
					_ = send(c.ack(payload.Submit, v2.AckKind_ACK_KIND_DUPLICATE))
					if state == "COMPLETED" {
						_ = send(c.invocationResult(call, cached))
					}
					continue
				}
				if !scheduler.StorePending(call) {
					_ = send(c.ack(payload.Submit, v2.AckKind_ACK_KIND_DUPLICATE))
					continue
				}
				if err := send(c.ack(payload.Submit, v2.AckKind_ACK_KIND_RECEIVED)); err != nil {
					reportError(fmt.Errorf("send V2 persisted ACK: %w", err))
					return
				}
				startExecution(call)
			case *v2.ControlStreamResponse_Reconcile:
				if err := send(c.reconcileResult(scheduler, payload.Reconcile, nodeSequence.Load(),
					lastControlSequence.Load())); err != nil {
					reportError(fmt.Errorf("send V2 reconcile result: %w", err))
					return
				}
			case *v2.ControlStreamResponse_Cancel:
				state, call, _, found := scheduler.Lookup(payload.Cancel.GetExecutionAttemptId(), payload.Cancel.GetToolInvocationId())
				if found && state != "COMPLETED" {
					scheduler.Cancel(call.TaskID)
				}
			case *v2.ControlStreamResponse_KeysetUpdate:
				log.Printf("received verification keyset update: version=%s keys=%d",
					payload.KeysetUpdate.GetKeysetVersion(), len(payload.KeysetUpdate.GetKeys()))
			}
		}
	}()

	select {
	case <-ctx.Done():
		cancel()
		executionWG.Wait()
		return ctx.Err()
	case err := <-errCh:
		cancel()
		executionWG.Wait()
		return err
	}
}

func (c Client) safeToolCall(submit *v2.SubmitInvocation, catalog mcp.Catalog) (safeops.SafeToolCall, error) {
	if submit.GetTenantId() != c.TenantID || submit.GetNodeId() != c.NodeID {
		return safeops.SafeToolCall{}, fmt.Errorf("submit identity does not match this node")
	}
	if !catalog.IsSupportedTool(submit.GetToolId()) {
		return safeops.SafeToolCall{}, fmt.Errorf("tool %s is not in the node catalog", submit.GetToolId())
	}
	risk := riskCodeString(submit.GetRiskCode())
	if risk == "" {
		return safeops.SafeToolCall{}, fmt.Errorf("unknown risk code")
	}
	if risk == "R5" {
		return safeops.SafeToolCall{}, fmt.Errorf("R5 action is forbidden")
	}
	if gmcrypto.SM3HexString(submit.GetCanonicalArgumentsJson()) != hex.EncodeToString(submit.GetArgumentsSm3()) {
		return safeops.SafeToolCall{}, fmt.Errorf("arguments SM3 mismatch")
	}
	if submit.GetDeadline() == nil || submit.GetDeadline().AsTime().Before(time.Now()) {
		return safeops.SafeToolCall{}, fmt.Errorf("invocation deadline is expired")
	}
	if (risk == "R3" || risk == "R4") && submit.GetApprovalToken() == nil {
		return safeops.SafeToolCall{}, fmt.Errorf("%s action requires approval token", risk)
	}
	if !submit.GetReadOnly() {
		hasWriteLock := false
		for _, lock := range submit.GetResourceLocks() {
			if lock.GetMode() == v2.LockMode_LOCK_MODE_WRITE_EXCLUSIVE {
				hasWriteLock = true
				break
			}
		}
		if !hasWriteLock {
			return safeops.SafeToolCall{}, fmt.Errorf("write action requires WRITE_EXCLUSIVE resource lock")
		}
	}
	mode := "PLAN"
	if submit.GetExecutionMode() == v2.ExecutionMode_EXECUTION_MODE_AUTO {
		mode = "AUTO"
	} else if submit.GetExecutionMode() == v2.ExecutionMode_EXECUTION_MODE_CAUTIOUS {
		mode = "CAUTIOUS"
	}
	locks := make([]safeops.ResourceLock, 0, len(submit.GetResourceLocks()))
	for _, lock := range submit.GetResourceLocks() {
		lockType := "READ_SHARED"
		if lock.GetMode() == v2.LockMode_LOCK_MODE_WRITE_EXCLUSIVE {
			lockType = "WRITE_EXCLUSIVE"
		}
		locks = append(locks, safeops.ResourceLock{ResourceKey: lock.GetCanonicalResource(), LockType: lockType})
	}
	approvalToken := ""
	if submit.GetApprovalToken() != nil {
		approvalToken = encodeApprovalToken(submit.GetApprovalToken())
	}
	return safeops.SafeToolCall{
		CommandID:     submit.GetExecutionAttemptId(),
		TaskID:        submit.GetRunId(),
		NodeID:        submit.GetNodeId(),
		ToolName:      submit.GetToolId(),
		ArgumentsJSON: submit.GetCanonicalArgumentsJson(),
		RiskCode:      risk,
		TraceID:       gmcrypto.SM3HexString(submit.GetRunId())[:32],
		// V2 协议没有 session 概念，用 AgentInstance 作为 workspace 隔离键：
		// SessionID 下游映射为 WorkspaceID（见 runner.callWorkspace），若留空则
		// 同一 agent 的所有实例会共用同一个沙箱 workspace。
		SessionID:          submit.GetAgentInstanceId(),
		IdempotencyKey:     submit.GetToolInvocationId(),
		ResourceLocks:      locks,
		ExecutionMode:      mode,
		ApprovalToken:      approvalToken,
		TenantID:           submit.GetTenantId(),
		ToolInvocationID:   submit.GetToolInvocationId(),
		ExecutionAttemptID: submit.GetExecutionAttemptId(),
		AgentInstanceID:    submit.GetAgentInstanceId(),
		AgentInvocationID:  submit.GetHandoffId(),
		ToolVersion:        submit.GetToolVersion(),
		LockVersion:        submit.GetLockVersion(),
	}, nil
}

func (c Client) invocationResult(call safeops.SafeToolCall, result safeops.SafeToolResult) *v2.ControlStreamRequest {
	status := v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_FAILED
	structured := ""
	errorCode := "NODE_TOOL_FAILED"
	message := result.Message
	if strings.EqualFold(result.Status, "COMPLETED") {
		structured = structuredPayload(result.ResultJSON)
		if err := validateToolOutput(call.ToolName, structured); err == nil {
			status = v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_SUCCEEDED
			errorCode = ""
			message = "completed"
		} else {
			message = err.Error()
			errorCode = "OUTPUT_SCHEMA_VALIDATION_FAILED"
			structured = ""
		}
	} else if strings.EqualFold(result.Status, "DENIED") {
		status = v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_REJECTED
		errorCode = "NODE_POLICY_REJECTED"
	}
	digest, _ := hex.DecodeString(gmcrypto.SM3HexString(structured))
	return &v2.ControlStreamRequest{Payload: &v2.ControlStreamRequest_Result{Result: &v2.InvocationResult{
		ToolInvocationId:     call.IdempotencyKey,
		ExecutionAttemptId:   call.CommandID,
		Status:               status,
		StructuredResultJson: structured,
		ResultSm3:            digest,
		ErrorCode:            errorCode,
		Message:              message,
		Retryable:            false,
		CompletedAt:          timestamppb.Now(),
	}}}
}

// structuredPayload 把一次执行的 CallResult 投影成一个 JSON 对象再上行。
//
// 三条执行路径的信封字段互不相同：MCP 本地路径是 {toolName, output}，特权助手是
// {tool, output, via}，workspace 沙箱是 {tool, output, exitCode, via}；而 output 本身
// 有时是工具序列化过的结构化对象（service.status、cleanup、助手侧写工具），有时是命令
// 的纯文本 stdout（绝大多数只读感知工具）。控制面的 NodeTool.validateOutput 按工具声明的
// outputSchema 校验对象，因此必须在这里收敛成统一的对象形态——只取 output 字段的旧实现
// 会让纯文本输出连 JSON 都不是，只读工具一律被判 OUTPUT_SCHEMA_VALIDATION_FAILED。
//
// 规则：剥掉传输元信息（tool/toolName/via）后，若剩下的 output 自身就是 JSON 对象，
// 以该对象为载荷；否则把 output 当文本，与其余业务字段（如 exitCode）合成对象。
func structuredPayload(resultJSON string) string {
	var envelope struct {
		StructuredContent map[string]json.RawMessage `json:"structuredContent"`
	}
	if err := json.Unmarshal([]byte(resultJSON), &envelope); err != nil {
		return ""
	}
	payload := map[string]json.RawMessage{}
	for key, value := range envelope.StructuredContent {
		switch key {
		case "tool", "toolName", "via":
		default:
			payload[key] = value
		}
	}
	raw, ok := payload["output"]
	if !ok {
		return ""
	}
	var text string
	if err := json.Unmarshal(raw, &text); err != nil {
		// output 不是字符串：形态未知，不猜测，交由上层判为校验失败。
		return ""
	}
	text = strings.TrimSpace(text)
	if len(payload) == 1 && strings.HasPrefix(text, "{") && json.Valid([]byte(text)) {
		return text
	}
	payload["output"] = raw
	// map 序列化按键排序，载荷形态确定——result_sm3 才能在重放时复现。
	encoded, err := json.Marshal(payload)
	if err != nil {
		return ""
	}
	return string(encoded)
}

func validateToolOutput(toolName, value string) error {
	if strings.TrimSpace(value) == "" {
		return fmt.Errorf("tool output is empty")
	}
	var output map[string]any
	if err := json.Unmarshal([]byte(value), &output); err != nil {
		return fmt.Errorf("tool output is not a JSON object: %w", err)
	}
	if toolName == "kylin.service.status" {
		return validateServiceStatusOutput(output)
	}
	return nil
}

func validateServiceStatusOutput(output map[string]any) error {
	required := []string{"service", "activeState", "subState", "loadState", "unitFileState"}
	if len(output) != len(required) {
		return fmt.Errorf("service.status output must contain exactly five fields")
	}
	for _, key := range required {
		text, ok := output[key].(string)
		if !ok || strings.TrimSpace(text) == "" {
			return fmt.Errorf("service.status output field %s is missing or blank", key)
		}
	}
	return nil
}

func (c Client) ack(submit *v2.SubmitInvocation, kind v2.AckKind) *v2.ControlStreamRequest {
	return &v2.ControlStreamRequest{Payload: &v2.ControlStreamRequest_Ack{Ack: &v2.InvocationAck{
		ToolInvocationId: submit.GetToolInvocationId(), ExecutionAttemptId: submit.GetExecutionAttemptId(),
		Kind: kind, PersistedAt: timestamppb.Now(),
	}}}
}

func (c Client) rejectedResult(submit *v2.SubmitInvocation, code string, message string) *v2.ControlStreamRequest {
	return &v2.ControlStreamRequest{Payload: &v2.ControlStreamRequest_Result{Result: &v2.InvocationResult{
		ToolInvocationId: submit.GetToolInvocationId(), ExecutionAttemptId: submit.GetExecutionAttemptId(),
		Status:    v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_REJECTED,
		ErrorCode: code, Message: message, CompletedAt: timestamppb.Now(),
	}}}
}

func (c Client) reconcileResult(scheduler *safeops.Scheduler, request *v2.ReconcileInvocations,
	nodeSequence uint64, lastControlSequence uint64) *v2.ControlStreamRequest {
	items := make([]*v2.ReconciledInvocation, 0, len(request.GetToolInvocationIds()))
	for _, invocationID := range request.GetToolInvocationIds() {
		state, call, result, found := scheduler.Lookup("", invocationID)
		item := &v2.ReconciledInvocation{ToolInvocationId: invocationID,
			Status: v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_UNKNOWN}
		if found {
			item.ExecutionAttemptId = call.CommandID
			switch state {
			case "QUEUED":
				item.Status = v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_QUEUED
			case "RUNNING":
				item.Status = v2.InvocationProtocolStatus_INVOCATION_PROTOCOL_STATUS_RUNNING
			case "COMPLETED":
				cachedCall := safeops.SafeToolCall{CommandID: result.CommandID, TaskID: result.TaskID,
					IdempotencyKey: invocationID, ToolName: call.ToolName}
				terminal := c.invocationResult(cachedCall, result).GetResult()
				item.ExecutionAttemptId = result.CommandID
				item.Status = terminal.GetStatus()
				item.TerminalResult = terminal
			}
		}
		items = append(items, item)
	}
	return &v2.ControlStreamRequest{Payload: &v2.ControlStreamRequest_ReconcileResult{ReconcileResult: &v2.ReconcileResult{
		Invocations: items, LastNodeSequence: nodeSequence, LastControlPlaneSequence: lastControlSequence,
	}}}
}

func (c Client) capabilities(catalog mcp.Catalog) *v2.NodeCapabilities {
	version := catalog.Manifest().Version
	if version == "" {
		version = "0.1.0"
	}
	installed := make([]*v2.InstalledTool, 0, len(catalog.Tools()))
	for _, tool := range catalog.Tools() {
		installed = append(installed, &v2.InstalledTool{ToolId: tool.Name, ToolVersion: version,
			ImplementationDigest: catalog.VersionHash(), Healthy: true})
	}
	return &v2.NodeCapabilities{TenantId: c.TenantID, NodeId: c.NodeID, ExecutorVersion: executorVersion,
		SupportedProtocolVersions: []string{"2"}, InstalledTools: installed}
}

func (c Client) heartbeat(scheduler *safeops.Scheduler, catalog mcp.Catalog,
	nodeSequence uint64, lastControlSequence uint64, guard *nativeGuardReporter) *v2.NodeHeartbeat {
	status, observedAt, payload := guard.report()
	heartbeat := &v2.NodeHeartbeat{TenantId: c.TenantID, NodeId: c.NodeID, ExecutorVersion: executorVersion,
		ProtocolVersion: "2", ToolCatalogDigest: catalog.VersionHash(), NativeGuardStatus: status,
		NativeGuardJson:    payload,
		RunningInvocations: uint32(len(scheduler.Snapshot().Running)),
		LastNodeSequence:   nodeSequence, LastControlPlaneSequence: lastControlSequence}
	if observedAt != nil {
		heartbeat.NativeGuardObservedAt = observedAt
	}
	return heartbeat
}

// nativeGuardReporter 把本机 Native Guard 姿态汇总进心跳，供控制面做新鲜度门禁。
// 采集要执行四个状态工具，按 TTL 缓存以免每次心跳都跑子进程。
//
// 状态词汇 = strings.ToUpper(hosttools 的 overall)：HEALTHY / WARNING / CRITICAL /
// UNSUPPORTED；取不到姿态时上报空串——控制面按 UNKNOWN fail-closed 拒绝 R3+，
// 绝不用「假装 AVAILABLE」掩盖采集失败（此前这里是硬编码 AVAILABLE）。
type nativeGuardReporter struct {
	runtime safeops.Runtime
	nodeID  string
	ttl     time.Duration
	mu      sync.Mutex
	fetched time.Time
	status  string
	observd *timestamppb.Timestamp
	payload string
}

func newNativeGuardReporter(runtime safeops.Runtime, nodeID string) *nativeGuardReporter {
	return &nativeGuardReporter{runtime: runtime, nodeID: nodeID, ttl: time.Minute}
}

func (r *nativeGuardReporter) report() (string, *timestamppb.Timestamp, string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.status != "" && time.Since(r.fetched) < r.ttl {
		return r.status, r.observd, r.payload
	}
	status, observedAt, payload := summarizeNativeGuard(r.runtime, r.nodeID)
	if status != "" {
		r.fetched = time.Now()
		r.status = status
		r.observd = observedAt
		r.payload = payload
	}
	return status, observedAt, payload
}

func summarizeNativeGuard(runtime safeops.Runtime, nodeID string) (string, *timestamppb.Timestamp, string) {
	provider, ok := runtime.(interface{ NativeGuardPosturePayload(nodeID string) any })
	if !ok {
		return "", nil, ""
	}
	encoded, err := json.Marshal(provider.NativeGuardPosturePayload(nodeID))
	if err != nil {
		return "", nil, ""
	}
	var posture struct {
		Overall         string `json:"overall"`
		CollectedAtUnix int64  `json:"collectedAtUnix"`
	}
	if err := json.Unmarshal(encoded, &posture); err != nil || strings.TrimSpace(posture.Overall) == "" {
		return "", nil, ""
	}
	observedAt := timestamppb.Now()
	if posture.CollectedAtUnix > 0 {
		observedAt = timestamppb.New(time.Unix(posture.CollectedAtUnix, 0))
	}
	return strings.ToUpper(strings.TrimSpace(posture.Overall)), observedAt, string(encoded)
}

func (c Client) mtlsConnection() (*grpc.ClientConn, error) {
	cert, err := tls.LoadX509KeyPair(c.CertStore.NodeCertPath(), c.CertStore.NodeKeyPath())
	if err != nil {
		return nil, fmt.Errorf("load V2 node certificate: %w", err)
	}
	caPEM, err := os.ReadFile(c.CertStore.CACertPath())
	if err != nil {
		return nil, fmt.Errorf("read V2 CA certificate: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, fmt.Errorf("invalid V2 CA certificate")
	}
	creds := credentials.NewTLS(&tls.Config{ServerName: controlTLSServerName(), Certificates: []tls.Certificate{cert},
		RootCAs: pool, MinVersion: tls.VersionTLS12})
	return grpc.NewClient(c.ControlAddress, grpc.WithTransportCredentials(creds))
}

const envControlServerName = "KYLIN_CONTROL_SERVER_NAME"

// controlTLSServerName 是 mTLS 控制流校验平台证书用的 ServerName。
// 优先 KYLIN_CONTROL_SERVER_NAME，未设时回退 KYLIN_ENROLL_SERVER_NAME，再回退 localhost。
func controlTLSServerName() string {
	if value := strings.TrimSpace(os.Getenv(envControlServerName)); value != "" {
		return value
	}
	if value := strings.TrimSpace(os.Getenv("KYLIN_ENROLL_SERVER_NAME")); value != "" {
		return value
	}
	return "localhost"
}

const (
	// enroll 阶段钉住平台身份的两种方式，配其一即可。
	envEnrollCAFile = "KYLIN_ENROLL_CA_FILE"
	envEnrollCAPin  = "KYLIN_ENROLL_CA_SHA256"
	// 开发/演示逃生舱：必须显式打开，且每次连接都告警。
	envEnrollInsecureTOFU = "KYLIN_ENROLL_INSECURE_TOFU"
)

// enrollTLSConfig 构造 enroll（:9091）阶段的 TLS 配置。
//
// enroll 是整条信任链的起点：此刻节点手里还没有任何证书，它要把 bootstrap token 交出去，
// 并把对端回给它的 CA 存成后续 mTLS 的信任锚。这一步不校验对端，中间人就能一次拿到 token、
// 再把自己的 CA 塞进节点——之后所有 mTLS 都会在攻击者的信任域里"正常工作"，且没有任何
// 后续环节能发现。所以这里**默认 fail-closed**：两个 CA 变量都不配置时直接拒绝 enroll，
// 而不是静默降级成 TOFU 首次信任。
//
// 三条路径：
//   - KYLIN_ENROLL_CA_FILE：常规 PEM 信任锚，走标准链校验（推荐）。
//   - KYLIN_ENROLL_CA_SHA256：钉扎**服务端叶子证书 DER 的 SHA-256**（不是 CA 证书的指纹，
//     变量名是历史遗留；服务端证书轮换后需同步更新）。适合没法分发 CA 文件的场景。
//   - KYLIN_ENROLL_INSECURE_TOFU=true：显式选择的不安全模式，仅限开发/演示。
//
// 两个都配时不是二选一，而是**同时生效**：链校验 + 指纹钉扎。配置了却只生效一个，
// 是比不配更危险的错觉。
func enrollTLSConfig() (*tls.Config, error) {
	serverName := strings.TrimSpace(os.Getenv("KYLIN_ENROLL_SERVER_NAME"))
	if serverName == "" {
		serverName = "localhost"
	}
	cfg := &tls.Config{ServerName: serverName, MinVersion: tls.VersionTLS12}

	caFile := strings.TrimSpace(os.Getenv(envEnrollCAFile))
	pin := strings.TrimSpace(os.Getenv(envEnrollCAPin))

	if caFile != "" {
		pemBytes, err := os.ReadFile(caFile)
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", envEnrollCAFile, err)
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pemBytes) {
			return nil, fmt.Errorf("%s is not a valid PEM certificate", envEnrollCAFile)
		}
		cfg.RootCAs = pool
	}

	if pin != "" {
		want := strings.ToLower(strings.ReplaceAll(pin, ":", ""))
		if len(want) != sha256.Size*2 {
			return nil, fmt.Errorf("%s must be a hex SHA-256 digest (%d hex chars, optional colons); got %d chars",
				envEnrollCAPin, sha256.Size*2, len(want))
		}
		if _, err := hex.DecodeString(want); err != nil {
			return nil, fmt.Errorf("%s is not valid hex: %w", envEnrollCAPin, err)
		}
		// 只配指纹时没有信任锚可用于链校验，必须让出 Go 的默认校验并由回调全权负责；
		// 与 CA 文件同时配置时，链校验照常执行，回调只是在其之上再钉一层。
		if caFile == "" {
			cfg.InsecureSkipVerify = true
		}
		cfg.VerifyPeerCertificate = func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return fmt.Errorf("enroll server presented no certificate")
			}
			sum := sha256.Sum256(rawCerts[0])
			got := hex.EncodeToString(sum[:])
			if got != want {
				return fmt.Errorf("enroll server certificate fingerprint mismatch: got %s want %s", got, want)
			}
			return nil
		}
	}

	if caFile != "" || pin != "" {
		return cfg, nil
	}

	if insecureTOFUEnabled() {
		// 逃生舱：每次 enroll 连接都打，不做去重、不降级为 debug——不安全模式不该安静。
		log.Printf("SECURITY WARNING: %s=true — enroll TLS runs with InsecureSkipVerify and trusts whatever CA "+
			"the first responder returns (TOFU). The bootstrap token and the entire mTLS trust anchor are exposed "+
			"to anyone able to intercept the enroll connection. Development and demo only; configure %s or %s "+
			"for any real deployment.",
			envEnrollInsecureTOFU, envEnrollCAFile, envEnrollCAPin)
		cfg.InsecureSkipVerify = true
		return cfg, nil
	}

	return nil, fmt.Errorf("refusing to enroll without a pinned platform identity: set %s to the platform CA PEM path "+
		"(recommended), or %s to the hex SHA-256 of the enroll server certificate. "+
		"For development only, set %s=true to accept the first certificate presented (insecure, logs a warning on every connection)",
		envEnrollCAFile, envEnrollCAPin, envEnrollInsecureTOFU)
}

func insecureTOFUEnabled() bool {
	enabled, err := strconv.ParseBool(strings.TrimSpace(os.Getenv(envEnrollInsecureTOFU)))
	return err == nil && enabled
}

func machineID() string {
	for _, path := range []string{"/etc/machine-id", "/var/lib/dbus/machine-id"} {
		if data, err := os.ReadFile(path); err == nil && strings.TrimSpace(string(data)) != "" {
			return strings.TrimSpace(string(data))
		}
	}
	return "unknown-machine"
}

func riskCodeString(rc v2.RiskCode) string {
	switch rc {
	case v2.RiskCode_RISK_CODE_R0:
		return "R0"
	case v2.RiskCode_RISK_CODE_R1:
		return "R1"
	case v2.RiskCode_RISK_CODE_R2:
		return "R2"
	case v2.RiskCode_RISK_CODE_R3:
		return "R3"
	case v2.RiskCode_RISK_CODE_R4:
		return "R4"
	case v2.RiskCode_RISK_CODE_R5:
		return "R5"
	default:
		return ""
	}
}

func encodeApprovalToken(token *v2.ApprovalToken) string {
	raw, err := proto.Marshal(token)
	if err != nil {
		return ""
	}
	return "kylin-approval-v1." + base64.RawURLEncoding.EncodeToString(raw)
}
