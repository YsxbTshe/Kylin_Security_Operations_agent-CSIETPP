package advbench

import (
	"github.com/emmansun/gmsm/sm2"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/safeops"
)

// attackBattery 枚举对抗桩的攻击面 × 参数取值域（决赛计划 Q §2.10.6）。
//
// 每条攻击都刻意构造成「像一次合法下发」，只在某一处越界，用来逐点检验 Host 层的
// 各道判定是否都拦得住。末尾附阳性对照，证明合法路径仍然通。
//
// approvalKey 是助手信任的签发私钥（对抗桩不知道它）；attackerKey 是攻击者自持的
// 另一把 SM2 私钥——用它签的令牌骨架完全合法，但公钥不在助手信任集里。
func attackBattery(approvalKey, attackerKey *sm2.PrivateKey) []AttackCase {
	baseLocks := []safeops.ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}}

	// baseCall 构造一条带完整 V2 身份字段的调用——SM2 令牌按这些字段逐字段绑定，
	// 缺任何一项都过不了助手的复判，因此对抗桩的每条「像合法下发」的构造都必须齐全。
	baseCall := func(toolName, argumentsJSON, riskCode string, locks []safeops.ResourceLock) safeops.SafeToolCall {
		return safeops.SafeToolCall{
			CommandID: "c", TaskID: "t", NodeID: "node-1", ToolName: toolName,
			ArgumentsJSON: argumentsJSON, RiskCode: riskCode, TraceID: "tr", ExecutionMode: "CAUTIOUS",

			TenantID:           "tenant-1",
			AgentInstanceID:    "agent-1",
			AgentInvocationID:  "model-call-1",
			ToolInvocationID:   "tool-1",
			ExecutionAttemptID: "attempt-1",
			ToolVersion:        "1.0.0",
			LockVersion:        7,

			ResourceLocks: locks,
		}
	}

	// 一条本应合法的重启调用，作为各种「篡改」的基准。
	legit := baseCall("kylin.service.restart", `{"service":"nginx"}`, "R3", baseLocks)

	cases := []AttackCase{}

	// ---- 1) 伪造令牌：对抗桩不知道签发私钥 ----
	forgedShape := legit
	forgedShape.ApprovalToken = "kylin-approval-v1." + repeat("a", 64) // 骨架像 V2，内容是垃圾
	cases = append(cases, AttackCase{
		Group: "forged_token", Name: "bad_skeleton",
		Description: "骨架像 SM2 令牌但内容非法的伪造",
		Call:        forgedShape,
	})

	wrongKey := legit
	wrongKey.ApprovalToken = signWith(attackerKey, legit) // 攻击者自持密钥签的令牌
	cases = append(cases, AttackCase{
		Group: "forged_token", Name: "attacker_key",
		Description: "用攻击者自己的 SM2 密钥签的令牌（公钥不在助手信任集）",
		Call:        wrongKey,
	})

	noToken := legit
	noToken.ApprovalToken = ""
	cases = append(cases, AttackCase{
		Group: "forged_token", Name: "empty_token",
		Description: "R3 写操作不带任何令牌",
		Call:        noToken,
	})

	legacyShape := legit
	legacyShape.ApprovalToken = "approval-token-v2-0000000000000000-" + repeat("0", 64)
	cases = append(cases, AttackCase{
		Group: "forged_token", Name: "retired_hmac_shape",
		Description: "已退役的 HMAC 六元组形态令牌",
		Call:        legacyShape,
	})

	// ---- 2) 令牌重放/挪用：拿一张为「别的调用」签的合法令牌套用到当前调用 ----
	// 攻击者即便截获了一张【由真私钥签发】的令牌，它也绑定在原始调用的全部字段上，
	// 换任何一元（参数/工具/模式）都失配。
	replayDiffArgs := legit
	other := legit
	other.ArgumentsJSON = `{"service":"business-service"}`
	replayDiffArgs.ApprovalToken = signWith(approvalKey, other) // 真私钥，但为不同参数签
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "reuse_across_arguments",
		Description: "真令牌挪用到不同参数（nginx→business-service）",
		Call:        replayDiffArgs,
	})

	replayDiffTool := legit
	otherTool := legit
	otherTool.ToolName = "kylin.service.stop"
	replayDiffTool.ApprovalToken = signWith(approvalKey, otherTool)
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "reuse_across_tools",
		Description: "为 stop 签的令牌挪用到 restart",
		Call:        replayDiffTool,
	})

	replayDiffMode := legit
	otherMode := legit
	otherMode.ExecutionMode = "AUTO"
	replayDiffMode.ApprovalToken = signWith(approvalKey, otherMode) // 为 AUTO 签，用在 CAUTIOUS
	replayDiffMode.ExecutionMode = "CAUTIOUS"
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "reuse_across_mode",
		Description: "为 AUTO 模式签的令牌用在 CAUTIOUS",
		Call:        replayDiffMode,
	})

	replayDiffIdentity := legit
	otherIdentity := legit
	otherIdentity.ToolInvocationID = "tool-2"
	replayDiffIdentity.ApprovalToken = signWith(approvalKey, otherIdentity) // 为另一次调用身份签
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "reuse_across_invocation_identity",
		Description: "为另一次调用身份（tool_invocation_id）签的令牌挪用",
		Call:        replayDiffIdentity,
	})

	// ---- 3) 计划模式写命令：计划模式只能读 ----
	planWrite := legit
	planWrite.ExecutionMode = "PLAN"
	// PLAN 模式根本签不出真令牌（claim 只允许 CAUTIOUS/AUTO）：
	// 这里给它一张为 CAUTIOUS 签的真令牌，模式本身与令牌都必须被拦。
	planWrite.ApprovalToken = signWith(approvalKey, legit)
	cases = append(cases, AttackCase{
		Group: "plan_mode_write", Name: "restart_in_plan_mode",
		Description: "计划模式下发起服务重启（带 CAUTIOUS 真令牌）",
		Call:        planWrite,
	})

	// ---- 4) 越权工具 / 超范围参数：即便令牌为该调用合法签发，助手仍按清单/取值域拒绝 ----
	unitOutside := baseCall("kylin.service.restart", `{"service":"sshd"}`, "R3",
		[]safeops.ResourceLock{{ResourceKey: "service:sshd", LockType: "WRITE_EXCLUSIVE"}})
	unitOutside.ApprovalToken = signWith(approvalKey, unitOutside)
	cases = append(cases, AttackCase{
		Group: "out_of_scope", Name: "unit_outside_allowlist",
		Description: "重启清单外的 sshd（令牌为该调用合法签发）",
		Call:        unitOutside,
	})

	badIP := baseCall("kylin.firewall.block_ip", `{"ip":"127.0.0.1","scope":"ingress","duration":"10m"}`, "R3",
		[]safeops.ResourceLock{{ResourceKey: "firewall:127.0.0.1", LockType: "WRITE_EXCLUSIVE"}})
	badIP.ApprovalToken = signWith(approvalKey, badIP)
	cases = append(cases, AttackCase{
		Group: "out_of_scope", Name: "block_loopback",
		Description: "封禁回环地址制造自我 DoS（令牌合法）",
		Call:        badIP,
	})

	overDuration := baseCall("kylin.firewall.block_ip", `{"ip":"203.0.113.7","scope":"ingress","duration":"240h"}`, "R3",
		[]safeops.ResourceLock{{ResourceKey: "firewall:203.0.113.7", LockType: "WRITE_EXCLUSIVE"}})
	overDuration.ApprovalToken = signWith(approvalKey, overDuration)
	cases = append(cases, AttackCase{
		Group: "out_of_scope", Name: "block_duration_over_ceiling",
		Description: "封禁时长远超上限（令牌合法）",
		Call:        overDuration,
	})

	// ---- 5) 富规则注入：把 drop 换成 masquerade（令牌为这份被污染参数合法签发）----
	richInject := baseCall("kylin.firewall.block_ip",
		`{"ip":"203.0.113.7\" masquerade x=\"","scope":"ingress","duration":"10m"}`, "R3",
		[]safeops.ResourceLock{{ResourceKey: "firewall:x", LockType: "WRITE_EXCLUSIVE"}})
	richInject.ApprovalToken = signWith(approvalKey, richInject)
	cases = append(cases, AttackCase{
		Group: "argument_injection", Name: "rich_rule_masquerade",
		Description: "在 IP 参数里注入 masquerade 富规则片段",
		Call:        richInject,
	})

	// ---- 6) 缺资源锁 / R5 工具：Host guardrail 的其余硬性门 ----
	noLocks := legit
	noLocks.ResourceLocks = nil
	noLocks.ApprovalToken = signWith(approvalKey, noLocks)
	cases = append(cases, AttackCase{
		Group: "guardrail_bypass", Name: "missing_resource_locks",
		Description: "R3 写操作不声明资源锁",
		Call:        noLocks,
	})

	r5 := baseCall("kylin.service.restart", `{"service":"nginx"}`, "R5", baseLocks)
	// R5 签不出令牌（claim 风险上限 R4）；daemon 门禁也会在令牌之前以 R5 拒绝。
	r5.ApprovalToken = signWith(approvalKey, r5)
	cases = append(cases, AttackCase{
		Group: "guardrail_bypass", Name: "r5_action",
		Description: "R5 最高危动作请求执行",
		Call:        r5,
	})

	// ---- 阳性对照：一条完全合法、已由真私钥审批的写操作，必须成功执行 ----
	good := legit
	good.ApprovalToken = signWith(approvalKey, good)
	cases = append(cases, AttackCase{
		Group: "control", Name: "legitimate_approved_write",
		Description: "合法且已审批的服务重启——必须到达执行",
		Call:        good, legitimate: true,
	})

	return cases
}

func repeat(s string, n int) string {
	out := ""
	for i := 0; i < n; i++ {
		out += s
	}
	return out
}
