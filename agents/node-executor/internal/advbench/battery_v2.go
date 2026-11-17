package advbench

import (
	"encoding/base64"
	"fmt"
	"strings"
	"time"

	"github.com/emmansun/gmsm/sm2"
	"github.com/emmansun/gmsm/sm3"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	nodeexecutorv2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/safeops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
)

// V2/SM2 对抗桩扩展（Phase 7 迭代 7.1 验收基线 18.2）。
//
// attackBattery 只覆盖了 V1 HMAC 令牌；新架构的审批令牌是 SM2 签名的 ApprovalClaim
// （approvalv2）。这里按同样的「像一次合法下发、只在一处越界」方法，把攻击面扩展到
// V2：伪造签名、未知签名密钥、过期令牌、跨 run / 跨 node / 跨参数挪用。所有攻击都经
// 真实的 safeops.Runner（本地 guardrail 形态校验）+ 真实的 privops.Server（KeyResolver
// 权威验签 + 绑定校验）执行；阳性对照证明合法 V2 审批写操作端到端可达执行。

// v2ClaimFor 按调用字段组装 12 元绑定的 ApprovalClaim。
func v2ClaimFor(call safeops.SafeToolCall, issuedAt, expiresAt time.Time) *nodeexecutorv2.ApprovalClaim {
	digest := sm3.Sum([]byte(call.ArgumentsJSON))
	return &nodeexecutorv2.ApprovalClaim{
		Version:            approvalv2.TokenVersion,
		TenantId:           call.TenantID,
		RunId:              call.TaskID,
		AgentInstanceId:    call.AgentInstanceID,
		AgentInvocationId:  call.AgentInvocationID,
		ToolInvocationId:   call.ToolInvocationID,
		ExecutionAttemptId: call.ExecutionAttemptID,
		NodeId:             call.NodeID,
		ToolId:             call.ToolName,
		ToolVersion:        call.ToolVersion,
		ArgumentsSm3:       digest[:],
		RiskCeiling:        nodeexecutorv2.RiskCode_RISK_CODE_R3,
		ExecutionMode:      nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS,
		LockVersion:        call.LockVersion,
		KeyPurpose:         string(signing.ApprovalSigning),
		IssuedAt:           timestamppb.New(issuedAt),
		ExpiresAt:          timestamppb.New(expiresAt),
	}
}

// issueV2 为一条调用签发 V2 令牌；mutate 允许攻击用例在签名前篡改 claim 的某一处。
func issueV2(privateKey *sm2.PrivateKey, keyID string, call safeops.SafeToolCall,
	issuedAt, expiresAt time.Time, mutate func(*nodeexecutorv2.ApprovalClaim)) string {
	claim := v2ClaimFor(call, issuedAt, expiresAt)
	if mutate != nil {
		mutate(claim)
	}
	token, err := approvalv2.Issue(claim, keyID, privateKey)
	if err != nil {
		panic(fmt.Sprintf("advbench: issue V2 token: %v", err))
	}
	return token
}

// tamperV2Signature 保持 claim 不变、只破坏签名 DER 的首字节——验签必须失败。
func tamperV2Signature(token string) string {
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(token, approvalv2.TokenPrefix))
	if err != nil {
		panic(fmt.Sprintf("advbench: decode V2 token: %v", err))
	}
	var parsed nodeexecutorv2.ApprovalToken
	if err := proto.Unmarshal(raw, &parsed); err != nil {
		panic(fmt.Sprintf("advbench: unmarshal V2 token: %v", err))
	}
	if len(parsed.Sm2DerSignature) == 0 {
		panic("advbench: V2 token has no signature to tamper")
	}
	parsed.Sm2DerSignature[0] ^= 0xFF
	out, err := proto.MarshalOptions{Deterministic: true}.Marshal(&parsed)
	if err != nil {
		panic(fmt.Sprintf("advbench: marshal tampered V2 token: %v", err))
	}
	return approvalv2.TokenPrefix + base64.RawURLEncoding.EncodeToString(out)
}

// attackBatteryV2 是 V2/SM2 攻击面。基准密钥只有 harness 持有；对抗桩要么伪造签名、
// 要么挪用为别的调用签发的真令牌。
func attackBatteryV2(privateKey *sm2.PrivateKey) []AttackCase {
	baseLocks := []safeops.ResourceLock{{ResourceKey: "service:nginx", LockType: "WRITE_EXCLUSIVE"}}

	// 一条本应合法的 V2 服务重启调用（身份/追踪/绑定字段齐全），作为各种篡改的基准。
	legit := safeops.SafeToolCall{
		CommandID: "c", TaskID: "run-v2", NodeID: "node-1", ToolName: "kylin.service.restart",
		ArgumentsJSON: `{"service":"nginx"}`, RiskCode: "R3", TraceID: "tr", ExecutionMode: "CAUTIOUS",
		ResourceLocks:      baseLocks,
		TenantID:           "tenant-1",
		ToolInvocationID:   "tool-v2-1",
		ExecutionAttemptID: "attempt-v2-1",
		AgentInstanceID:    "agent-v2-1",
		AgentInvocationID:  "model-call-v2-1",
		ToolVersion:        "1.0.0",
		LockVersion:        7,
	}

	signLegit := func() string {
		return issueV2(privateKey, benchKeyID, legit, benchNow, benchNow.Add(5*time.Minute), nil)
	}

	cases := []AttackCase{}

	// ---- 1) 伪造：claim 完全正确，但签名被破坏 / 由未知密钥签发 ----
	tampered := legit
	tampered.ApprovalToken = tamperV2Signature(signLegit())
	cases = append(cases, AttackCase{
		Group: "forged_token", Name: "v2_tampered_signature",
		Description: "SM2 令牌 claim 不变、签名被篡改",
		Call:        tampered,
	})

	unknownKey := legit
	unknownKey.ApprovalToken = issueV2(privateKey, "attacker-unknown-key", legit,
		benchNow, benchNow.Add(5*time.Minute), nil)
	cases = append(cases, AttackCase{
		Group: "forged_token", Name: "v2_unknown_signing_key",
		Description: "用 KeyResolver 不认识的密钥 id 签发的 SM2 令牌",
		Call:        unknownKey,
	})

	// ---- 2) 重放/挪用：真密钥签发的令牌，换一处使用 ----
	expired := legit
	expired.ApprovalToken = issueV2(privateKey, benchKeyID, legit,
		benchNow.Add(-4*time.Minute), benchNow.Add(-time.Minute), nil)
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "v2_expired_token",
		Description: "已过期（窗口在过去）的 SM2 令牌",
		Call:        expired,
	})

	crossRun := legit
	crossRun.ApprovalToken = issueV2(privateKey, benchKeyID, legit,
		benchNow, benchNow.Add(5*time.Minute),
		func(claim *nodeexecutorv2.ApprovalClaim) { claim.RunId = "run-victim" })
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "v2_reuse_across_runs",
		Description: "为别的 run 签的 SM2 令牌挪用到当前 run",
		Call:        crossRun,
	})

	crossNode := legit
	crossNode.ApprovalToken = issueV2(privateKey, benchKeyID, legit,
		benchNow, benchNow.Add(5*time.Minute),
		func(claim *nodeexecutorv2.ApprovalClaim) { claim.NodeId = "node-2" })
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "v2_reuse_across_nodes",
		Description: "为 node-2 签的 SM2 令牌挪用到 node-1",
		Call:        crossNode,
	})

	crossArgs := legit
	otherArgs := legit
	otherArgs.ArgumentsJSON = `{"service":"business-service"}`
	crossArgs.ApprovalToken = issueV2(privateKey, benchKeyID, otherArgs,
		benchNow, benchNow.Add(5*time.Minute), nil)
	cases = append(cases, AttackCase{
		Group: "token_replay", Name: "v2_reuse_across_arguments",
		Description: "为 business-service 签的 SM2 令牌挪用到 nginx（参数 SM3 失配）",
		Call:        crossArgs,
	})

	// ---- 阳性对照：字段与 claim 完全一致的合法 V2 审批写操作，必须端到端执行 ----
	good := legit
	good.ApprovalToken = signLegit()
	cases = append(cases, AttackCase{
		Group: "control", Name: "legitimate_v2_approved_write",
		Description: "合法 SM2 审批的服务重启——必须穿过 guardrail 与助手到达执行",
		Call:        good, legitimate: true,
	})

	return cases
}
