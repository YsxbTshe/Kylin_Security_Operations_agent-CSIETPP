// Package protocol 定义 Node Executor 与 Workspace Runner 之间的 Unix Socket 协议。
//
// 设计复用 privops 模式：每条请求一条连接、JSON 编码、半关闭写方向。
// Workspace Runner 不持有任何密钥，不接触审批令牌——它只负责在沙箱内
// 执行文件操作和命令，安全边界由 Node Executor 的 DispatchGate 保证。
package protocol

// Request 是 Node Executor → Workspace Runner 的请求。
type Request struct {
	// RequestID 用于审计关联，不参与安全校验。
	RequestID string `json:"requestId"`
	// TenantID 用于容器命名隔离。
	TenantID string `json:"tenantId"`
	// AgentID 用于容器命名隔离和路径前缀。
	AgentID string `json:"agentId"`
	// WorkspaceID 标识工作空间实例，同一 Agent 可有多个。
	WorkspaceID string `json:"workspaceId"`
	// Action 是操作类型，封闭枚举。
	Action string `json:"action"`
	// Arguments 是操作参数，结构取决于 Action。
	Arguments map[string]any `json:"arguments,omitempty"`
	// TimeoutSec 覆盖默认超时，0 表示使用默认值。
	TimeoutSec int `json:"timeoutSec,omitempty"`
}

// Response 是 Workspace Runner → Node Executor 的应答。
type Response struct {
	// Output 是操作输出内容。
	Output string `json:"output,omitempty"`
	// Error 非空表示操作失败。
	Error string `json:"error,omitempty"`
	// ExitCode 仅对 exec 操作有效。
	ExitCode int `json:"exitCode,omitempty"`
}

// DefaultSocketPath 是 Workspace Runner 监听的 Unix Socket 路径。
const DefaultSocketPath = "/run/kylin-workspace/workspace.sock"

// maxMessageBytes 限制单条消息体积。
const maxMessageBytes = 2 << 20

// 封闭操作枚举。不在表内的操作一律拒绝。
const (
	ActionExec       = "exec"
	ActionReadFile   = "read_file"
	ActionWriteFile  = "write_file"
	ActionEditFile   = "edit_file"
	ActionGlob       = "glob"
	ActionListDir    = "list_dir"
	ActionCreateDir  = "create_dir"
	ActionDeleteFile = "delete_file"
)

// validActions 是合法操作的封闭集合。
var validActions = map[string]struct{}{
	ActionExec:       {},
	ActionReadFile:   {},
	ActionWriteFile:  {},
	ActionEditFile:   {},
	ActionGlob:       {},
	ActionListDir:    {},
	ActionCreateDir:  {},
	ActionDeleteFile: {},
}

// IsValidAction 判断操作是否在封闭集合内。
func IsValidAction(action string) bool {
	_, ok := validActions[action]
	return ok
}
