package privops

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/cleanupcore"
	nodeexecutorv2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"github.com/emmansun/gmsm/sm3"
)

var unitNamePattern = regexp.MustCompile(`^[a-zA-Z0-9@._-]+$`)

// Policy 是助手侧的允许清单，来自 root 拥有的配置文件。
// daemon 因 ProtectSystem=full 无法修改它——这是「即使 Agent 被完全攻陷」
// 这一前提能够成立的基础。
type Policy struct {
	AllowedUnits    []string
	AllowedScopes   []string
	MaxBlockMinutes int
	// MaxOpsPerMinute 限制特权操作频次。计在助手侧而非 daemon 侧：
	// 若计在 daemon，攻陷后即可绕过。
	MaxOpsPerMinute int
	// RollbackStore 是清理回滚包的根目录，root 拥有。备份文件 0600（daemon 读不到），
	// 元数据 0644（供运维侧直接检视回滚包内容）。
	RollbackStore string
}

// Runner 执行最终命令。抽成接口只为可测——生产实现直接 exec，助手本身就是 root，
// 不经过 sudo。
type Runner interface {
	Run(ctx context.Context, name string, args ...string) (string, error)
}

type execRunner struct{ timeout time.Duration }

func (r execRunner) Run(ctx context.Context, name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	output, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
	text := strings.TrimSpace(string(output))
	if err != nil {
		if text == "" {
			text = err.Error()
		}
		return text, fmt.Errorf("%s", text)
	}
	return text, nil
}

// 令牌 TTL 与消费记录的保留窗口。
//
// 签发侧把 expiresAt 限制在 5 分钟内，验签阶段（approvalv2.Verify）已经会拒掉过期令牌，
// 因此消费记录只需要活得比令牌久一点点即可：保留窗口 = 令牌到期 + 10 分钟宽限，
// 宽限量覆盖验签的 30 秒时钟偏移容忍以及节点与控制面之间的时钟漂移。
// 这个上界是台账「不会无界增长」的根据——过了窗口的记录被压缩回收，而那时
// 对应的令牌无论如何都过不了验签。
const (
	defaultTokenTTL        = 5 * time.Minute
	consumedRetentionGrace = 10 * time.Minute
)

// Server 是特权助手的核心。它不做网络、不加载插件、不接触 LLM。
type Server struct {
	policy      Policy
	peerUID     uint32
	runner      Runner
	audit       func(format string, args ...any)
	mu          sync.Mutex
	recent      []time.Time
	replay      ReplayGuard
	nowFunc     func() time.Time
	keyResolver approvalv2.PublicKeyResolver
}

type ServerOptions struct {
	Policy         Policy
	AllowedPeerUID uint32
	CommandTimeout time.Duration
	Runner         Runner
	Audit          func(format string, args ...any)
	Now            func() time.Time
	KeyResolver    approvalv2.PublicKeyResolver
	// ReplayGuard 是已消费令牌的台账。部署形态必须传入持久化实现
	// （cmd/privhelper 传 FileReplayGuard，构造失败即启动失败）——只有持久化台账
	// 才能让「一次性消费」跨助手重启成立。留空时退回**进程内**台账并审计告警，
	// 该路径仅供进程内测试与对抗基准使用。
	ReplayGuard ReplayGuard
}

func NewServer(options ServerOptions) *Server {
	timeout := options.CommandTimeout
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	runner := options.Runner
	if runner == nil {
		runner = execRunner{timeout: timeout}
	}
	audit := options.Audit
	if audit == nil {
		audit = func(string, ...any) {}
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}
	replay := options.ReplayGuard
	if replay == nil {
		// 没有持久化台账就等于「重启即重开重放窗口」。这不能悄悄发生，
		// 所以留一条审计——部署形态的 privhelper 永远不会走到这里。
		audit("警告：未配置持久化消费台账，防重放仅在本进程生命周期内有效")
		replay = newMemoryReplayGuard()
	}
	return &Server{
		policy:      options.Policy,
		peerUID:     options.AllowedPeerUID,
		runner:      runner,
		audit:       audit,
		replay:      replay,
		nowFunc:     now,
		keyResolver: options.KeyResolver,
	}
}

// Serve 接受连接直到 ctx 取消。
func (s *Server) Serve(ctx context.Context, listener net.Listener) error {
	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()
	for {
		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go s.serveConn(ctx, conn)
	}
}

func (s *Server) serveConn(ctx context.Context, conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(s.nowFunc().Add(60 * time.Second))

	uid, err := PeerUID(conn)
	if err != nil {
		s.reply(conn, Response{Error: "无法确认对端身份：" + err.Error()})
		return
	}
	if uid != s.peerUID {
		// 不回显期望值，避免把配置泄露给非预期调用方。
		s.audit("拒绝：对端 uid=%d 不是授权调用方", uid)
		s.reply(conn, Response{Error: "调用方未授权"})
		return
	}

	var request Request
	decoder := json.NewDecoder(&limitedReader{conn: conn, remaining: maxMessageBytes})
	if err := decoder.Decode(&request); err != nil {
		s.reply(conn, Response{Error: "请求解析失败：" + err.Error()})
		return
	}
	s.reply(conn, s.Handle(ctx, request))
}

func (s *Server) reply(conn net.Conn, response Response) {
	payload, err := json.Marshal(response)
	if err != nil {
		payload = []byte(`{"error":"应答编码失败"}`)
	}
	_, _ = conn.Write(payload)
}

// Handle 处理一条请求。与传输解耦，便于直接测试。
func (s *Server) Handle(ctx context.Context, request Request) Response {
	// 先看这个工具是不是免令牌的特权只读操作。判定只依据签名过的 ToolName，
	// 且 readOnlyOps 是常量表，daemon 无从影响。
	op, isPrivileged := OperationFor(request.ToolName)
	_, readOnly := readOnlyOps[op]

	// ① 审批令牌的权威校验：只认 SM2 签名令牌（approvalv2），旧 HMAC 形态一律拒绝。
	// 写操作一律要令牌；免令牌只给无参、命令行为常量的特权只读操作。
	if !(readOnly && isPrivileged) {
		if !s.verifyApprovalToken(request) {
			s.audit("拒绝：审批令牌校验失败 tool=%s task=%s", request.ToolName, request.TaskID)
			return Response{Error: "审批令牌校验失败"}
		}
	}

	// ② 操作类型从签名过的 ToolName 推导，不采信 daemon 的任何声明。
	if !isPrivileged {
		s.audit("拒绝：工具 %s 不在特权操作清单内", request.ToolName)
		return Response{Error: "工具不在特权操作清单内：" + request.ToolName}
	}

	// ③ 执行模式与风险等级同样在签名内，助手独立复判一次。
	// 只读操作不受计划模式限制——计划模式禁止的是写。
	if !readOnly && normalizeExecutionMode(request.ExecutionMode) == "PLAN" {
		s.audit("拒绝：计划模式不允许写操作 tool=%s", request.ToolName)
		return Response{Error: "计划模式不允许写操作"}
	}
	if normalizeRisk(request.RiskCode) == "R5" {
		s.audit("拒绝：R5 工具不允许执行 tool=%s", request.ToolName)
		return Response{Error: "R5 工具不允许执行"}
	}

	if err := s.admitRate(); err != nil {
		s.audit("拒绝：%v tool=%s", err, request.ToolName)
		return Response{Error: err.Error()}
	}

	// 清理是 in-process 文件操作，不走外部命令。参数只从签名覆盖的 ArgumentsJSON 解析。
	if op == opCleanupExecute || op == opCleanupRollback {
		output, err := s.handleCleanup(op, request.ArgumentsJSON)
		if err != nil {
			s.audit("清理失败 tool=%s：%v", request.ToolName, err)
			return Response{Output: output, Error: err.Error()}
		}
		s.audit("执行 tool=%s op=%s task=%s", request.ToolName, op, request.TaskID)
		return Response{Output: output}
	}

	// ④ 参数只从签名覆盖的 ArgumentsJSON 里解析，并做结构化校验。
	name, args, err := s.buildCommand(op, request.ArgumentsJSON)
	if err != nil {
		s.audit("拒绝：%v tool=%s args=%s", err, request.ToolName, request.ArgumentsJSON)
		return Response{Error: err.Error()}
	}

	s.audit("执行 tool=%s op=%s task=%s cmd=%s %s", request.ToolName, op, request.TaskID, name, strings.Join(args, " "))
	output, err := s.runner.Run(ctx, name, args...)
	if err != nil {
		return Response{Output: output, Error: err.Error()}
	}
	if snapshot := s.serviceActionSnapshot(ctx, op, args); snapshot != "" {
		return Response{Output: snapshot}
	}
	return Response{Output: output}
}

func (s *Server) serviceActionSnapshot(ctx context.Context, op string, args []string) string {
	switch op {
	case opServiceStart, opServiceStop, opServiceRestart, opServiceReload, opServiceEnable, opServiceDisable:
	default:
		return ""
	}
	if len(args) == 0 {
		return ""
	}
	unit := args[len(args)-1]
	show, err := s.runner.Run(ctx, "systemctl", "show", unit, "--no-pager",
		"--property=ActiveState,SubState,LoadState,UnitFileState,MainPID,ActiveEnterTimestamp")
	if err != nil {
		encoded, encodeErr := json.Marshal(map[string]any{
			"service":       unit,
			"action":        strings.TrimPrefix(op, "service."),
			"snapshotError": err.Error(),
			"commandOutput": show,
		})
		if encodeErr != nil {
			return ""
		}
		return string(encoded)
	}
	return encodeServiceSnapshot(unit, strings.TrimPrefix(op, "service."), show)
}

func encodeServiceSnapshot(unit string, action string, showOutput string) string {
	values := map[string]string{}
	for _, line := range strings.Split(showOutput, "\n") {
		key, value, found := strings.Cut(strings.TrimSpace(line), "=")
		if !found {
			continue
		}
		values[strings.TrimSpace(key)] = strings.TrimSpace(value)
	}
	payload := map[string]any{
		"service":              unit,
		"action":               action,
		"loadState":            values["LoadState"],
		"activeState":          values["ActiveState"],
		"subState":             values["SubState"],
		"unitFileState":        values["UnitFileState"],
		"mainPID":              values["MainPID"],
		"activeEnterTimestamp": values["ActiveEnterTimestamp"],
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return ""
	}
	return string(encoded)
}

func (s *Server) admitRate() error {
	if s.policy.MaxOpsPerMinute <= 0 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	cutoff := s.nowFunc().Add(-time.Minute)
	kept := s.recent[:0]
	for _, at := range s.recent {
		if at.After(cutoff) {
			kept = append(kept, at)
		}
	}
	s.recent = kept
	if len(s.recent) >= s.policy.MaxOpsPerMinute {
		return fmt.Errorf("特权操作超过每分钟 %d 次的上限", s.policy.MaxOpsPerMinute)
	}
	s.recent = append(s.recent, s.nowFunc())
	return nil
}

// verifyApprovalToken 只认 SM2 签名令牌。旧 HMAC 六元组令牌已随 V1 协议退役：
// 无论形态多像合法令牌，前缀不是 kylin-approval-v1. 一律拒绝（fail-closed）。
func (s *Server) verifyApprovalToken(request Request) bool {
	if !strings.HasPrefix(request.ApprovalToken, approvalv2.TokenPrefix) {
		return false
	}
	return s.verifyV2Token(request)
}

// verifyV2Token 用 SM2 公钥验证令牌，并把签名覆盖的 claim 与请求**逐字段**复判：
// 九元身份、参数 SM3、风险上限、执行模式、锁版本，任何一项失配即拒绝。
// 助手不采信 daemon 的任何单独声明——请求里缺任何一个身份字段都会导致失配。
func (s *Server) verifyV2Token(request Request) bool {
	if s.keyResolver == nil {
		return false
	}
	resolved, err := approvalv2.Verify(request.ApprovalToken, s.keyResolver, s.nowFunc())
	if err != nil {
		return false
	}
	if !approvalv2.CoversInvocation(resolved, requestClaim(request)) {
		return false
	}
	var expiresAt time.Time
	if ts := resolved.GetExpiresAt(); ts != nil {
		expiresAt = ts.AsTime()
	}
	return s.consumeOnce(request.ApprovalToken, expiresAt)
}

// consumeOnce 在**执行之前**把令牌指纹记入持久化台账。
//
// 顺序是刻意的：先记消费并落盘，再执行。反过来（先执行后记账）会留下一个窗口——
// 助手在执行完、记账前崩溃，重启后同一张令牌还能在 TTL 内再执行一次，
// 这正是本次修复要关掉的缺口。
//
// 指纹取 SM3(令牌原文)，与修复前的语义完全一致，不动 SM2 验签与 covers() 复判。
// 需要说清的边界：同一份 claim 若被控制面重新签名会得到另一枚令牌（SM2 签名带随机数），
// 指纹也就不同；但 claim 里的 execution_attempt_id 对每次尝试唯一，
// 拿不到第二次审批就拿不到第二枚令牌，所以这不构成绕过。
func (s *Server) consumeOnce(token string, expiresAt time.Time) bool {
	digest := sm3.Sum([]byte(token))
	key := hex.EncodeToString(digest[:])
	now := s.nowFunc()
	until := expiresAt
	if until.IsZero() || until.Before(now) {
		until = now.Add(defaultTokenTTL)
	}
	accepted, err := s.replay.Consume(key, until.Add(consumedRetentionGrace), now)
	if err != nil {
		// fail-closed：台账写不进去就不执行。退回内存模式等于攻击者只要把台账
		// 写坏（塞满磁盘、改权限）就能重新打开重放窗口。
		s.audit("拒绝：消费台账不可用，特权操作 fail-closed：%v", err)
		return false
	}
	if !accepted {
		s.audit("拒绝：审批令牌已被消费（防重放）")
		return false
	}
	return true
}

// requestClaim 把请求映射成它应当匹配的 ApprovalClaim 形态。
// 未识别的风险码按最严格的 R5 映射——没有任何合法令牌的覆盖范围能达到 R5，
// 因此必然失配拒绝；缺失的身份字段映射为空串，同样必然与已签发的 claim 失配。
func requestClaim(request Request) *nodeexecutorv2.ApprovalClaim {
	digest := sm3.Sum([]byte(request.ArgumentsJSON))
	return &nodeexecutorv2.ApprovalClaim{
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
		RiskCeiling:        riskCeilingFor(request.RiskCode),
		ExecutionMode:      executionModeFor(request.ExecutionMode),
		LockVersion:        request.LockVersion,
	}
}

// riskCeilingFor 把请求的风险码映射到 proto 枚举。
func riskCeilingFor(riskCode string) nodeexecutorv2.RiskCode {
	switch normalizeRisk(riskCode) {
	case "R0":
		return nodeexecutorv2.RiskCode_RISK_CODE_R0
	case "R1":
		return nodeexecutorv2.RiskCode_RISK_CODE_R1
	case "R2":
		return nodeexecutorv2.RiskCode_RISK_CODE_R2
	case "R3":
		return nodeexecutorv2.RiskCode_RISK_CODE_R3
	case "R4":
		return nodeexecutorv2.RiskCode_RISK_CODE_R4
	default:
		return nodeexecutorv2.RiskCode_RISK_CODE_R5
	}
}

// executionModeFor 把请求的执行模式映射到 proto 枚举。
// 未知取值映射为 UNSPECIFIED：合法 claim 只会是 CAUTIOUS/AUTO，因此必然失配。
func executionModeFor(mode string) nodeexecutorv2.ExecutionMode {
	switch strings.ToUpper(strings.TrimSpace(mode)) {
	case "PLAN":
		return nodeexecutorv2.ExecutionMode_EXECUTION_MODE_PLAN
	case "AUTO":
		return nodeexecutorv2.ExecutionMode_EXECUTION_MODE_AUTO
	case "CAUTIOUS":
		return nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS
	default:
		return nodeexecutorv2.ExecutionMode_EXECUTION_MODE_UNSPECIFIED
	}
}

// normalizeExecutionMode 把执行模式归一。未知取值一律按最严格的 CAUTIOUS 处理：
// 缺省绝不能倒向放宽，否则控制面漏发该字段就等于降级了门禁。
func normalizeExecutionMode(mode string) string {
	switch strings.ToUpper(strings.TrimSpace(mode)) {
	case "PLAN":
		return "PLAN"
	case "AUTO":
		return "AUTO"
	default:
		return "CAUTIOUS"
	}
}

// normalizeRisk 把风险码归一到与后端 RiskCode 枚举一致的取值；未知取值返回空串。
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

func (s *Server) handleCleanup(op string, argumentsJSON string) (string, error) {
	if strings.TrimSpace(s.policy.RollbackStore) == "" {
		return "", fmt.Errorf("助手未配置回滚存储目录")
	}
	var arguments map[string]any
	if strings.TrimSpace(argumentsJSON) != "" {
		if err := json.Unmarshal([]byte(argumentsJSON), &arguments); err != nil {
			return "", fmt.Errorf("参数不是合法 JSON")
		}
	}
	now := s.nowFunc().UTC()
	switch op {
	case opCleanupExecute:
		targetDir := stringArg(arguments, "targetDir", "target_dir")
		mode := stringArg(arguments, "mode")
		maxFiles := intArg(arguments, 20, "maxFiles", "max_files")
		return cleanupcore.Execute(targetDir, mode, maxFiles, s.policy.RollbackStore, now)
	case opCleanupRollback:
		rollbackID := stringArg(arguments, "rollbackId", "rollback_id")
		return cleanupcore.Rollback(rollbackID, s.policy.RollbackStore, now)
	}
	return "", fmt.Errorf("未知清理操作：%s", op)
}

func (s *Server) buildCommand(op string, argumentsJSON string) (string, []string, error) {
	var arguments map[string]any
	if strings.TrimSpace(argumentsJSON) != "" {
		if err := json.Unmarshal([]byte(argumentsJSON), &arguments); err != nil {
			return "", nil, fmt.Errorf("参数不是合法 JSON")
		}
	}

	switch op {
	case opServiceStart, opServiceStop, opServiceRestart, opServiceReload, opServiceEnable, opServiceDisable:
		unit, err := s.requireAllowedUnit(stringArg(arguments, "service", "unit"))
		if err != nil {
			return "", nil, err
		}
		return "systemctl", []string{strings.TrimPrefix(op, "service."), unit}, nil

	case opFirewallBlock:
		ip, err := requireBlockableIP(stringArg(arguments, "ip"))
		if err != nil {
			return "", nil, err
		}
		if err := s.requireAllowedScope(stringArg(arguments, "scope")); err != nil {
			return "", nil, err
		}
		duration, err := s.requireDuration(stringArg(arguments, "duration"))
		if err != nil {
			return "", nil, err
		}
		// 富规则字符串由助手拼装。daemon 从不提供它——否则一个 drop 规则的位置
		// 就能被换成 masquerade 或 forward-port。
		return "firewall-cmd", []string{
			"--quiet",
			"--add-rich-rule=" + dropRule(ip),
			"--timeout=" + duration,
		}, nil

	case opFirewallState:
		return "firewall-cmd", []string{"--state"}, nil

	case opFirewallRules:
		return "firewall-cmd", []string{"--zone=public", "--list-all"}, nil

	case opFirewallUnblock:
		ip, err := requireBlockableIP(stringArg(arguments, "ip"))
		if err != nil {
			return "", nil, err
		}
		if err := s.requireAllowedScope(stringArg(arguments, "scope")); err != nil {
			return "", nil, err
		}
		return "firewall-cmd", []string{"--quiet", "--remove-rich-rule=" + dropRule(ip)}, nil
	}
	return "", nil, fmt.Errorf("未知特权操作：%s", op)
}

func (s *Server) requireAllowedUnit(value string) (string, error) {
	unit := strings.TrimSpace(value)
	if unit == "" {
		return "", fmt.Errorf("缺少服务名")
	}
	if !unitNamePattern.MatchString(unit) {
		return "", fmt.Errorf("服务名含非法字符")
	}
	for _, allowed := range s.policy.AllowedUnits {
		if strings.EqualFold(strings.TrimSpace(allowed), unit) {
			return unit, nil
		}
	}
	return "", fmt.Errorf("服务 %s 不在助手允许清单内", unit)
}

func (s *Server) requireAllowedScope(value string) error {
	scope := strings.TrimSpace(value)
	if scope == "" {
		// 作用域缺省时按第一个允许项处理，与 daemon 侧既有行为一致。
		if len(s.policy.AllowedScopes) == 0 {
			return fmt.Errorf("助手未配置任何允许的作用域")
		}
		return nil
	}
	for _, allowed := range s.policy.AllowedScopes {
		if strings.EqualFold(strings.TrimSpace(allowed), scope) {
			return nil
		}
	}
	return fmt.Errorf("作用域 %s 不在助手允许清单内", scope)
}

func (s *Server) requireDuration(value string) (string, error) {
	text := strings.TrimSpace(value)
	if text == "" {
		text = "10m"
	}
	parsed, err := time.ParseDuration(text)
	if err != nil {
		return "", fmt.Errorf("封禁时长格式非法")
	}
	if parsed <= 0 {
		return "", fmt.Errorf("封禁时长必须为正")
	}
	limit := time.Duration(s.policy.MaxBlockMinutes) * time.Minute
	if limit > 0 && parsed > limit {
		return "", fmt.Errorf("封禁时长超过上限 %d 分钟", s.policy.MaxBlockMinutes)
	}
	return text, nil
}

func dropRule(ip net.IP) string {
	family := "ipv4"
	if ip.To4() == nil {
		family = "ipv6"
	}
	return fmt.Sprintf("rule family=%q source address=%q drop", family, ip.String())
}

// requireBlockableIP 把字符串解析成 IP 并拒掉不该封的地址。
//
// 这是 sudoers 无法表达、而助手能表达的那一类约束：不是「匹配某个字符串模式」，
// 而是「它必须是一个可路由的单播地址」。封掉回环或未指定地址会切断本机自身通信，
// 是最容易被用来制造拒绝服务的一手。
func requireBlockableIP(value string) (net.IP, error) {
	ip := net.ParseIP(strings.TrimSpace(value))
	if ip == nil {
		return nil, fmt.Errorf("不是合法 IP 地址")
	}
	switch {
	case ip.IsLoopback():
		return nil, fmt.Errorf("拒绝封禁回环地址")
	case ip.IsUnspecified():
		return nil, fmt.Errorf("拒绝封禁未指定地址")
	case ip.IsMulticast(), ip.IsLinkLocalUnicast(), ip.IsLinkLocalMulticast():
		return nil, fmt.Errorf("拒绝封禁组播或链路本地地址")
	}
	return ip, nil
}

func intArg(arguments map[string]any, fallback int, keys ...string) int {
	for _, key := range keys {
		if value, ok := arguments[key]; ok {
			switch v := value.(type) {
			case float64:
				return int(v)
			case int:
				return v
			}
		}
	}
	return fallback
}

func stringArg(arguments map[string]any, keys ...string) string {
	for _, key := range keys {
		if value, ok := arguments[key]; ok {
			if text, ok := value.(string); ok {
				return text
			}
		}
	}
	return ""
}

// limitedReader 限制单条请求的体积，同时保留底层连接以便后续写应答。
type limitedReader struct {
	conn      net.Conn
	remaining int64
}

func (r *limitedReader) Read(buffer []byte) (int, error) {
	if r.remaining <= 0 {
		return 0, fmt.Errorf("请求超过大小上限")
	}
	if int64(len(buffer)) > r.remaining {
		buffer = buffer[:r.remaining]
	}
	n, err := r.conn.Read(buffer)
	r.remaining -= int64(n)
	return n, err
}
