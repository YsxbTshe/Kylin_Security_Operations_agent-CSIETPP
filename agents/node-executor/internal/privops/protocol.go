// Package privops 定义非特权 daemon 与 root 特权助手之间的协议（ADR-002）。
//
// 设计要点有二：
//
//  1. 助手执行的一切都来自**被签名覆盖的数据**。请求里没有独立的结构化参数字段——
//     若 unit/ip 与 ArgumentsJSON 是两份数据，攻陷的 daemon 就能拿一张
//     `{"ip":"1.2.3.4"}` 的合法令牌去封 `9.9.9.9`：令牌校验照过，动作却是另一个。
//     助手只解析 ArgumentsJSON，操作类型也从签名过的 ToolName 推导，不采信任何
//     daemon 单独声明的意图。
//
//  2. 命令行由助手自己拼装。daemon 从不提供命令字符串或 argv 片段，因此没有注入面；
//     攻击面收敛为「工具枚举 × 各参数取值域」，可以穷举论证。这正是 sudoers 做不到的——
//     它只能表达「任意字符串」，无法表达「这里是一个 IPv4 地址」。
package privops

// Request 是 daemon → 助手的唯一请求形态。
//
// 全部字段都绑定到 SM2 签名的 ApprovalClaim（approvalv2）：TaskID 对应 claim 的
// run_id，NodeID/ToolName/TenantID/ToolVersion/LockVersion 等 13 个身份字段
// 逐字段复判（签名本身覆盖 ApprovalClaim 全部 17 字段），ArgumentsJSON 以 SM3 摘要绑定。助手可以独立验证完整的调用上下文；
// 请求里缺任何一项都会与已签发的 claim 失配而被拒。
type Request struct {
	TaskID        string `json:"taskId"`
	NodeID        string `json:"nodeId"`
	ToolName      string `json:"toolName"`
	RiskCode      string `json:"riskCode"`
	ArgumentsJSON string `json:"argumentsJson"`
	ExecutionMode string `json:"executionMode"`
	ApprovalToken string `json:"approvalToken"`

	TenantID           string `json:"tenantId,omitempty"`
	ToolInvocationID   string `json:"toolInvocationId,omitempty"`
	ExecutionAttemptID string `json:"executionAttemptId,omitempty"`
	AgentInstanceID    string `json:"agentInstanceId,omitempty"`
	AgentInvocationID  string `json:"agentInvocationId,omitempty"`
	ToolVersion        string `json:"toolVersion,omitempty"`
	LockVersion        uint64 `json:"lockVersion,omitempty"`
}

// Response 是助手 → daemon 的应答。Error 非空即表示操作未执行。
type Response struct {
	Output string `json:"output,omitempty"`
	Error  string `json:"error,omitempty"`
}

// DefaultSocketPath 是助手监听的 unix socket。放在 /run 下，随重启清空。
const DefaultSocketPath = "/run/kylin-privhelper/helper.sock"

// maxMessageBytes 限制单条消息体积，避免一条超大请求拖垮特权进程。
const maxMessageBytes = 1 << 20

// 助手认识的全部特权操作。清单封闭：推导不出其中之一的工具一律拒绝。
// 只读工具不在此列——它们以 kylin-agent 身份直接执行，本就不需要特权。
const (
	opServiceStart    = "service.start"
	opServiceStop     = "service.stop"
	opServiceRestart  = "service.restart"
	opServiceReload   = "service.reload"
	opServiceEnable   = "service.enable"
	opServiceDisable  = "service.disable"
	opFirewallBlock   = "firewall.block_ip"
	opFirewallUnblock = "firewall.unblock_ip"

	// 特权只读操作。它们需要特权（firewalld 的 D-Bus 调用按 uid 判定），
	// 但作为 R0 工具根本不会被签发审批令牌，因此不能要求它们出示令牌。
	opFirewallState = "firewall.state"
	opFirewallRules = "firewall.rules"

	// 清理是写操作（归档/截断/回滚），需审批令牌，不在 readOnlyOps 内。
	// 执行下沉到助手是为了让审批成为强制点：演示目录改 root 属主后，只有助手能写。
	opCleanupExecute  = "cleanup.execute"
	opCleanupRollback = "cleanup.rollback"
)

// readOnlyOps 是免审批令牌的特权只读操作。
//
// 这是对「经助手的一切都过审批校验」的一处有意让步，边界画得很紧：
// 它们完全无参数（因此攻击面只有「能否读到防火墙配置」这一件事本身），
// 命令行是常量，且同样受限速与审计约束。写操作不在此列，一律要求令牌。
var readOnlyOps = map[string]struct{}{
	opFirewallState: {},
	opFirewallRules: {},
}

// toolToOp 是工具名到特权操作的封闭映射。
// 这张表是助手的权限边界本身：不在表内的工具，助手根本不知道该做什么，直接拒绝。
var toolToOp = map[string]string{
	"kylin.service.start":       opServiceStart,
	"kylin.service.stop":        opServiceStop,
	"kylin.service.restart":     opServiceRestart,
	"kylin.service.reload":      opServiceReload,
	"kylin.service.enable":      opServiceEnable,
	"kylin.service.disable":     opServiceDisable,
	"kylin.firewall.block_ip":   opFirewallBlock,
	"kylin.firewall.unblock_ip": opFirewallUnblock,
	"kylin.firewall.state":      opFirewallState,
	"kylin.firewall.rules":      opFirewallRules,
	"kylin.cleanup.execute":     opCleanupExecute,
	"kylin.cleanup.rollback":    opCleanupRollback,
}

// OperationFor 返回工具对应的特权操作，第二个返回值表示该工具是否需要经助手执行。
// daemon 用它决定某个工具走本地执行还是走特权边界。
func OperationFor(toolName string) (string, bool) {
	op, ok := toolToOp[toolName]
	return op, ok
}
