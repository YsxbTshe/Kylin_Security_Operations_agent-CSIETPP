package safeops

import (
	"fmt"
	"strings"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/privops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/workspace"
)

// 审批令牌的权威校验在特权助手侧完成，不在 daemon（ADR-002 §3.4）。
//
// daemon 不持有任何签名材料：若持有，攻陷 daemon 就等于同时拿到签发能力，可为任意
// 工具自签合法令牌，人工审批便退化成应用层约定。因此 daemon 只能做**骨架校验**
// （前缀、base64url、protobuf 骨架、版本号）用于快速失败与体验，真正的判定
// （签名、有效期、逐字段绑定）由助手在执行前完成。
//
// 这套之所以安全，前提是「每个 R3/R4 写操作都经助手执行」——助手执行前必验令牌。
// 该前提由 privops 的路由表 + executor 的路由不变量测试共同保证；一旦有 R3/R4 工具
// 不经助手而在本地执行，这里的骨架校验就会漏放伪造令牌。
func approvalTokenHasValidShape(call SafeToolCall) bool {
	return approvalv2.HasValidShape(call.ApprovalToken)
}

// PrivilegedExecutor 执行需要 OS 特权的写操作。生产实现是特权助手客户端。
//
// daemon 里没有任何一处直接持有特权，全部经由这个出口离开进程。
type PrivilegedExecutor interface {
	Call(request privops.Request) (string, error)
}

// unavailablePrivilegedExecutor 是默认实现：拒绝一切特权操作。
//
// 默认必须是「不可用」而不是「本地执行」。若默认回退到本地执行，任何一次
// 装配遗漏都会让写操作悄悄绕过特权边界——而且是在功能看起来完全正常的情况下。
type unavailablePrivilegedExecutor struct{}

func (unavailablePrivilegedExecutor) Call(privops.Request) (string, error) {
	return "", fmt.Errorf("特权助手未配置，拒绝执行特权操作")
}

var privilegedExecutor PrivilegedExecutor = unavailablePrivilegedExecutor{}

// SetPrivilegedExecutor 注入特权助手客户端。仅在启动装配阶段调用一次。
func SetPrivilegedExecutor(executor PrivilegedExecutor) {
	if executor != nil {
		privilegedExecutor = executor
	}
}

// ResetPrivilegedExecutorForTest 把特权执行器复位为默认的 fail-closed 实现。
// 仅供测试/基准在结束后清理进程级全局状态，避免污染后续用例。
func ResetPrivilegedExecutorForTest() {
	privilegedExecutor = unavailablePrivilegedExecutor{}
}

// unavailableWorkspaceCaller 是默认实现：拒绝一切 workspace 操作。
// 与 unavailablePrivilegedExecutor 同理——默认 fail-closed，装配遗漏不会悄悄绕过沙箱。
type unavailableWorkspaceCaller struct{}

func (unavailableWorkspaceCaller) Call(workspace.Request) (workspace.Response, error) {
	return workspace.Response{}, fmt.Errorf("workspace runner 未配置，拒绝执行 workspace 操作")
}

var workspaceCaller WorkspaceCaller = unavailableWorkspaceCaller{}

// SetWorkspaceCaller 注入 workspace runner 客户端。仅在启动装配阶段调用一次。
func SetWorkspaceCaller(caller WorkspaceCaller) {
	if caller != nil {
		workspaceCaller = caller
	}
}

// ResetWorkspaceCallerForTest 把 workspace 客户端复位为默认的 fail-closed 实现。
func ResetWorkspaceCallerForTest() {
	workspaceCaller = unavailableWorkspaceCaller{}
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
