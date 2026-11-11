package privops

import (
	"context"
	"crypto/ecdsa"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
	"github.com/emmansun/gmsm/sm2"
	"github.com/emmansun/gmsm/sm3"

	nodeexecutorv2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type recordingRunner struct {
	name string
	args []string
	runs int
}

func (r *recordingRunner) Run(_ context.Context, name string, args ...string) (string, error) {
	r.name, r.args, r.runs = name, args, r.runs+1
	if name == "systemctl" && len(args) > 0 && args[0] == "show" {
		return "LoadState=loaded\nActiveState=active\nSubState=running\nUnitFileState=enabled\nMainPID=4242\nActiveEnterTimestamp=Tue 2026-08-17 16:45:23 UTC\n", nil
	}
	return "ok", nil
}

// approvalFixture 持有测试 SM2 密钥对与固定时钟，为请求签发真实 SM2 审批令牌。
type approvalFixture struct {
	privateKey *sm2.PrivateKey
	publicKey  *ecdsa.PublicKey
	now        time.Time
}

func newApprovalFixture(t *testing.T) *approvalFixture {
	t.Helper()
	privateKey, publicKey := v2TestKeys(t)
	return &approvalFixture{privateKey: privateKey, publicKey: publicKey, now: time.Unix(1_800_000_000, 0).UTC()}
}

func (f *approvalFixture) resolver() approvalv2.PublicKeyResolver {
	return approvalv2.StaticPublicKeys{"approval-test-1": f.publicKey}
}

// ledger 在临时目录上开一本**持久化**消费台账，与部署形态一致。
// 测试一律走这条路径，而不是进程内内存表——否则测的就不是生产实际跑的那套判定。
func (f *approvalFixture) ledger(t *testing.T, dir string) *FileReplayGuard {
	t.Helper()
	guard, err := NewFileReplayGuard(ReplayGuardOptions{Dir: dir, Now: func() time.Time { return f.now }})
	if err != nil {
		t.Fatalf("打开消费台账失败：%v", err)
	}
	t.Cleanup(func() { _ = guard.Close() })
	return guard
}

// serverWithLedger 用指定台账构造助手，供「重启」类用例复用同一个台账目录。
func (f *approvalFixture) serverWithLedger(runner Runner, guard ReplayGuard) *Server {
	return NewServer(ServerOptions{
		Policy: Policy{
			AllowedUnits:    []string{"nginx", "business-service"},
			AllowedScopes:   []string{"ingress"},
			MaxBlockMinutes: 60,
			MaxOpsPerMinute: 10,
		},
		Runner:      runner,
		KeyResolver: f.resolver(),
		Now:         func() time.Time { return f.now },
		ReplayGuard: guard,
	})
}

func (f *approvalFixture) server(t *testing.T, runner Runner) *Server {
	t.Helper()
	return f.serverWithLedger(runner, f.ledger(t, t.TempDir()))
}

// signedRequest 构造一条带**合法** SM2 审批令牌的请求（含完整 V2 身份字段），
// 模拟攻击者已经拿到了一张真令牌（例如复用一次合法审批），
// 据此检验助手的其余各道校验是否仍然拦得住。
func (f *approvalFixture) signedRequest(toolName, argumentsJSON string) Request {
	request := Request{
		TaskID:        "task-1",
		NodeID:        "node-1",
		ToolName:      toolName,
		RiskCode:      "R3",
		ArgumentsJSON: argumentsJSON,
		ExecutionMode: "CAUTIOUS",

		TenantID:           "tenant-1",
		ToolInvocationID:   "tool-1",
		ExecutionAttemptID: "attempt-1",
		AgentInstanceID:    "agent-1",
		AgentInvocationID:  "model-call-1",
		ToolVersion:        "1.0.0",
		LockVersion:        7,
	}
	claim := v2ClaimForRequest(f.now, request)
	token, err := approvalv2.Issue(claim, "approval-test-1", f.privateKey)
	if err != nil {
		panic(err)
	}
	request.ApprovalToken = token
	return request
}

// v2ClaimForRequest 按请求字段构造与之匹配的 ApprovalClaim（签发视角）。
func v2ClaimForRequest(now time.Time, request Request) *nodeexecutorv2.ApprovalClaim {
	digest := sm3.Sum([]byte(request.ArgumentsJSON))
	ceiling := nodeexecutorv2.RiskCode_RISK_CODE_R0
	switch strings.ToUpper(strings.TrimSpace(request.RiskCode)) {
	case "R1":
		ceiling = nodeexecutorv2.RiskCode_RISK_CODE_R1
	case "R2":
		ceiling = nodeexecutorv2.RiskCode_RISK_CODE_R2
	case "R3":
		ceiling = nodeexecutorv2.RiskCode_RISK_CODE_R3
	case "R4":
		ceiling = nodeexecutorv2.RiskCode_RISK_CODE_R4
	}
	mode := nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS
	if strings.ToUpper(strings.TrimSpace(request.ExecutionMode)) == "AUTO" {
		mode = nodeexecutorv2.ExecutionMode_EXECUTION_MODE_AUTO
	}
	return &nodeexecutorv2.ApprovalClaim{
		Version:            1,
		TenantId:           request.TenantID,
		RunId:              request.TaskID,
		AgentInstanceId:    request.AgentInstanceID,
		AgentInvocationId:  request.AgentInvocationID,
		ToolInvocationId:   request.ToolInvocationID,
		ExecutionAttemptId: request.ExecutionAttemptID,
		NodeId:             request.NodeID,
		ToolId:             request.ToolName,
		ToolVersion:        request.ToolVersion,
		ArgumentsSm3:       digest[:],
		RiskCeiling:        ceiling,
		ExecutionMode:      mode,
		LockVersion:        request.LockVersion,
		KeyPurpose:         string(signing.ApprovalSigning),
		IssuedAt:           timestamppb.New(now),
		ExpiresAt:          timestamppb.New(now.Add(5 * time.Minute)),
	}
}

// 助手存在的首要理由：签名私钥只在签发侧，攻陷的 daemon 签不出能过这一关的令牌。
// 已退役的 HMAC 六元组形态与任意伪造的 V2 骨架都必须被拒。
func TestForgedApprovalTokenIsRejected(t *testing.T) {
	fixture := newApprovalFixture(t)
	for _, forged := range []string{
		"approval-token-v2-0000000000000000-" + strings.Repeat("a", 64), // 已退役的 HMAC 形态
		"kylin-approval-v1." + strings.Repeat("a", 64),                  // 伪造的 V2 骨架
	} {
		runner := &recordingRunner{}
		server := fixture.server(t, runner)

		request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
		request.ApprovalToken = forged

		if got := server.Handle(t.Context(), request); got.Error == "" {
			t.Fatalf("伪造令牌 %q 必须被拒绝", forged)
		}
		if runner.runs != 0 {
			t.Fatal("校验失败时不得执行任何命令")
		}
	}
}

// 令牌绑定参数：拿着为「重启 nginx」签发的令牌去重启别的服务，SM3 摘要必然对不上。
// 这条是「参数只从签名覆盖的 JSON 里解析」这个设计的直接体现。
func TestApprovalTokenIsBoundToArguments(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	request.ArgumentsJSON = `{"service":"business-service"}` // 换参数，不换令牌

	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("参数被替换后必须校验失败")
	}
	if runner.runs != 0 {
		t.Fatal("校验失败时不得执行任何命令")
	}
}

// 令牌绑定完整身份：请求里缺任何一个 V2 身份字段，都会与已签发的 claim 失配。
func TestApprovalTokenIsBoundToFullIdentity(t *testing.T) {
	fixture := newApprovalFixture(t)

	mutations := map[string]func(*Request){
		"tenant":            func(r *Request) { r.TenantID = "" },
		"agent_instance":    func(r *Request) { r.AgentInstanceID = "" },
		"agent_invocation":  func(r *Request) { r.AgentInvocationID = "" },
		"tool_invocation":   func(r *Request) { r.ToolInvocationID = "" },
		"execution_attempt": func(r *Request) { r.ExecutionAttemptID = "" },
		"tool_version":      func(r *Request) { r.ToolVersion = "" },
		"lock_version":      func(r *Request) { r.LockVersion = 0 },
		"cross_mode":        func(r *Request) { r.ExecutionMode = "AUTO" },
		"risk_escalation":   func(r *Request) { r.RiskCode = "R4" },
		"unknown_risk":      func(r *Request) { r.RiskCode = "NONSENSE" },
		"unknown_exec_mode": func(r *Request) { r.ExecutionMode = "react" },
	}
	for name, mutate := range mutations {
		runner := &recordingRunner{}
		server := fixture.server(t, runner)
		request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
		mutate(&request)

		if got := server.Handle(t.Context(), request); got.Error == "" {
			t.Fatalf("身份失配用例 %q 必须被拒绝", name)
		}
		if runner.runs != 0 {
			t.Fatalf("身份失配用例 %q 不得执行任何命令", name)
		}
	}
}

// 即使令牌完全合法，不在映射表里的工具助手也不知道该做什么——直接拒绝，
// 而不是尝试猜测或回退到某个默认动作。
func TestToolOutsidePrivilegedTableIsRejected(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	if got := server.Handle(t.Context(), fixture.signedRequest("kylin.disk.usage", `{}`)); got.Error == "" {
		t.Fatal("非特权工具不应被助手执行")
	}
	if runner.runs != 0 {
		t.Fatal("不得执行任何命令")
	}
}

func TestUnitOutsideHelperAllowlistIsRejected(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	got := server.Handle(t.Context(), fixture.signedRequest("kylin.service.restart", `{"service":"sshd"}`))
	if got.Error == "" || !strings.Contains(got.Error, "不在助手允许清单内") {
		t.Fatalf("清单外的服务必须被拒绝，实际 %+v", got)
	}
	if runner.runs != 0 {
		t.Fatal("不得执行任何命令")
	}
}

// 助手独立复判执行模式。PLAN 模式根本签不出令牌（claim 只允许 CAUTIOUS/AUTO），
// 因此带 PLAN 模式的写请求要么在令牌绑定处失配，要么被步骤③的模式规则拦下——
// 两道都必须 fail-closed。
func TestPlanModeIsRejectedAtHelper(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	request.ExecutionMode = "PLAN" // 令牌为 CAUTIOUS 签发，模式被篡改

	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("计划模式下的写操作必须被助手拒绝")
	}
	if runner.runs != 0 {
		t.Fatal("不得执行任何命令")
	}
}

// 这条是本设计相对 sudoers 的核心优势：富规则由助手拼装，daemon 提供的只是一个
// 待校验的 IP 字符串。sudoers 的通配符会跨词边界匹配，拦不住这类替换。
func TestRichRuleIsBuiltByHelperNotCaller(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	// 攻击者试图把 drop 换成 masquerade——注意令牌是为这份参数**合法签发**的。
	injected := `{"ip":"203.0.113.7\" masquerade x=\"","scope":"ingress","duration":"10m"}`
	if got := server.Handle(t.Context(), fixture.signedRequest("kylin.firewall.block_ip", injected)); got.Error == "" {
		t.Fatal("非法 IP 必须被拒绝")
	}
	if runner.runs != 0 {
		t.Fatal("不得执行任何命令")
	}

	// 正常路径：助手自己拼出 drop 规则。
	got := server.Handle(t.Context(), fixture.signedRequest("kylin.firewall.block_ip",
		`{"ip":"203.0.113.7","scope":"ingress","duration":"10m"}`))
	if got.Error != "" {
		t.Fatalf("合法封禁不应失败：%s", got.Error)
	}
	joined := strings.Join(runner.args, " ")
	if !strings.Contains(joined, `source address="203.0.113.7" drop`) {
		t.Fatalf("助手应拼出 drop 富规则，实际 %q", joined)
	}
	if strings.Contains(joined, "masquerade") || strings.Contains(joined, "forward-port") {
		t.Fatalf("富规则被污染：%q", joined)
	}
}

// 「这里必须是一个可路由的单播地址」是 sudoers 无法表达、助手能表达的那类约束。
func TestUnroutableAddressesAreRejected(t *testing.T) {
	fixture := newApprovalFixture(t)
	server := fixture.server(t, &recordingRunner{})
	for _, ip := range []string{"127.0.0.1", "0.0.0.0", "224.0.0.1", "169.254.1.1", "::1", "not-an-ip"} {
		got := server.Handle(t.Context(), fixture.signedRequest("kylin.firewall.block_ip",
			`{"ip":"`+ip+`","scope":"ingress","duration":"10m"}`))
		if got.Error == "" {
			t.Fatalf("%s 不应被允许封禁", ip)
		}
	}
}

func TestBlockDurationCeilingIsEnforced(t *testing.T) {
	fixture := newApprovalFixture(t)
	server := fixture.server(t, &recordingRunner{})
	got := server.Handle(t.Context(), fixture.signedRequest("kylin.firewall.block_ip",
		`{"ip":"203.0.113.7","scope":"ingress","duration":"24h"}`))
	if got.Error == "" || !strings.Contains(got.Error, "超过上限") {
		t.Fatalf("超长封禁必须被拒绝，实际 %+v", got)
	}
}

// 限速计在助手侧：若计在 daemon，攻陷后即可绕过。
func TestRateLimitIsEnforcedByHelper(t *testing.T) {
	privateKey, publicKey := v2TestKeys(t)
	runner := &recordingRunner{}
	frozen := time.Unix(1765000000, 0)
	ledger, err := NewFileReplayGuard(ReplayGuardOptions{Dir: t.TempDir(), Now: func() time.Time { return frozen }})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ledger.Close() })
	server := NewServer(ServerOptions{
		Policy:      Policy{AllowedUnits: []string{"nginx"}, MaxOpsPerMinute: 2},
		Runner:      runner,
		KeyResolver: approvalv2.StaticPublicKeys{"approval-test-1": publicKey},
		Now:         func() time.Time { return frozen },
		ReplayGuard: ledger,
	})

	request := Request{
		TaskID: "task-1", NodeID: "node-1", ToolName: "kylin.service.restart",
		RiskCode: "R3", ArgumentsJSON: `{"service":"nginx"}`, ExecutionMode: "CAUTIOUS",
		TenantID: "tenant-1", ToolInvocationID: "tool-1", ExecutionAttemptID: "attempt-1",
		AgentInstanceID: "agent-1", AgentInvocationID: "model-call-1",
		ToolVersion: "1.0.0", LockVersion: 7,
	}
	token, err := approvalv2.Issue(v2ClaimForRequest(frozen, request), "approval-test-1", privateKey)
	if err != nil {
		t.Fatal(err)
	}
	request.ApprovalToken = token

	for i := range 2 {
		attempt := request
		attempt.ExecutionAttemptID = "attempt-" + string(rune('1'+i))
		token, err := approvalv2.Issue(v2ClaimForRequest(frozen, attempt), "approval-test-1", privateKey)
		if err != nil {
			t.Fatal(err)
		}
		attempt.ApprovalToken = token
		if got := server.Handle(t.Context(), attempt); got.Error != "" {
			t.Fatalf("第 %d 次不应被限速：%s", i+1, got.Error)
		}
	}
	third := request
	third.ExecutionAttemptID = "attempt-3"
	token, err = approvalv2.Issue(v2ClaimForRequest(frozen, third), "approval-test-1", privateKey)
	if err != nil {
		t.Fatal(err)
	}
	third.ApprovalToken = token
	if got := server.Handle(t.Context(), third); got.Error == "" {
		t.Fatal("超过上限后必须被拒绝")
	}
	if runner.runs != 4 {
		t.Fatalf("两次成功操作各含一条动作 + 一条状态快照，应为 4 次，实际 %d", runner.runs)
	}
}

func v2TestKeys(t *testing.T) (*sm2.PrivateKey, *ecdsa.PublicKey) {
	t.Helper()
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, err := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	if err != nil {
		t.Fatal(err)
	}
	publicPEM, err := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	if err != nil {
		t.Fatal(err)
	}
	privateKey, err := signing.ParsePrivateKeyPEM(privatePEM)
	if err != nil {
		t.Fatal(err)
	}
	publicKey, err := signing.ParsePublicKeyPEM(publicPEM)
	if err != nil {
		t.Fatal(err)
	}
	return privateKey, publicKey
}

func TestV2Sm2TokenIsAcceptedByKeyResolver(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	got := server.Handle(t.Context(), request)
	if got.Error != "" {
		t.Fatalf("V2 SM2 token should be accepted: %s", got.Error)
	}
	if runner.runs != 2 {
		t.Fatalf("expected action + status snapshot, got %d", runner.runs)
	}
	if !strings.Contains(got.Output, `"service":"nginx"`) || !strings.Contains(got.Output, `"activeState":"active"`) {
		t.Fatalf("写工具成功应回传状态快照，实际 %q", got.Output)
	}
}

func TestV2Sm2TokenCannotBeReplayed(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	if got := server.Handle(t.Context(), request); got.Error != "" {
		t.Fatalf("first use must succeed: %s", got.Error)
	}
	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("replay of the same approval token must be rejected")
	}
	if runner.runs != 2 {
		t.Fatalf("replay must not execute again, got %d runs", runner.runs)
	}
}

func TestV2Sm2CrossNodeIsRejected(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	request.NodeID = "node-2" // 令牌为 node-1 签发

	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("V2 SM2 cross-node must be rejected")
	}
	if runner.runs != 0 {
		t.Fatal("cross-node rejection must not execute")
	}
}

func TestV2Sm2CrossRunIsRejected(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := fixture.server(t, runner)

	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	request.TaskID = "task-999" // 令牌为 task-1 签发

	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("V2 SM2 cross-run must be rejected")
	}
	if runner.runs != 0 {
		t.Fatal("cross-run rejection must not execute")
	}
}

func TestV2WithoutKeyResolverIsRejected(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &recordingRunner{}
	server := NewServer(ServerOptions{
		Policy:      Policy{AllowedUnits: []string{"nginx"}, MaxOpsPerMinute: 10},
		Runner:      runner,
		Now:         func() time.Time { return fixture.now },
		ReplayGuard: fixture.ledger(t, t.TempDir()),
	})

	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("V2 token without key resolver must be rejected")
	}
	if runner.runs != 0 {
		t.Fatal("must not execute without key resolver")
	}
}
