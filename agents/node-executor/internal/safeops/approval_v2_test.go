package safeops

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/emmansun/gmsm/sm2"
	"github.com/emmansun/gmsm/sm3"
	"google.golang.org/protobuf/types/known/timestamppb"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	nodeexecutorv2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
)

var (
	testSignKeyOnce sync.Once
	testSignKey     *sm2.PrivateKey
	testSignKeyErr  error
)

// testApprovalKey 懒加载 contracts/test-vectors 里的测试 SM2 私钥。
// 仅测试使用；daemon 生产路径不持有任何签名材料。
func testApprovalKey() *sm2.PrivateKey {
	testSignKeyOnce.Do(func() {
		pemBytes, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "contracts", "test-vectors", "TEST_ONLY_sm2_private.pem"))
		if err != nil {
			testSignKeyErr = err
			return
		}
		testSignKey, testSignKeyErr = signing.ParsePrivateKeyPEM(pemBytes)
	})
	if testSignKeyErr != nil {
		panic(testSignKeyErr)
	}
	return testSignKey
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

// approvalTokenForTest 为一条调用签发真实的 SM2 审批令牌（仅供测试）。
//
// daemon 侧门禁只做骨架校验，但这里仍签发完整令牌：调用里缺失的 V2 身份字段
// 用固定测试值补齐。claim 的执行模式固定为 CAUTIOUS——PLAN 模式根本签不出
// 令牌（validateClaim 拒绝），这正是「计划模式写命令即使带令牌也必须被拒」的前提。
func approvalTokenForTest(call SafeToolCall) string {
	now := time.Now().UTC()
	digest := sm3.Sum([]byte(call.ArgumentsJSON))
	lockVersion := call.LockVersion
	if lockVersion == 0 {
		lockVersion = 1
	}
	claim := &nodeexecutorv2.ApprovalClaim{
		Version:            1,
		TenantId:           firstNonEmpty(call.TenantID, "tenant-test"),
		RunId:              firstNonEmpty(call.TaskID, "task-test"),
		AgentInstanceId:    firstNonEmpty(call.AgentInstanceID, "agent-test"),
		AgentInvocationId:  firstNonEmpty(call.AgentInvocationID, "model-call-test"),
		ToolInvocationId:   firstNonEmpty(call.ToolInvocationID, "tool-invocation-test"),
		ExecutionAttemptId: firstNonEmpty(call.ExecutionAttemptID, "attempt-test"),
		NodeId:             firstNonEmpty(call.NodeID, "node-test"),
		ToolId:             firstNonEmpty(call.ToolName, "kylin.service.restart"),
		ToolVersion:        firstNonEmpty(call.ToolVersion, "1.0.0"),
		ArgumentsSm3:       digest[:],
		RiskCeiling:        riskCeilingForTest(call.RiskCode),
		ExecutionMode:      nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS,
		LockVersion:        lockVersion,
		KeyPurpose:         string(signing.ApprovalSigning),
		IssuedAt:           timestamppb.New(now),
		ExpiresAt:          timestamppb.New(now.Add(5 * time.Minute)),
	}
	token, err := approvalv2.Issue(claim, "approval-test-1", testApprovalKey())
	if err != nil {
		panic(err)
	}
	return token
}

func riskCeilingForTest(riskCode string) nodeexecutorv2.RiskCode {
	switch strings.ToUpper(strings.TrimSpace(riskCode)) {
	case "R4", "HIGH":
		return nodeexecutorv2.RiskCode_RISK_CODE_R4
	case "R3":
		return nodeexecutorv2.RiskCode_RISK_CODE_R3
	case "R2", "MEDIUM":
		return nodeexecutorv2.RiskCode_RISK_CODE_R2
	case "R1":
		return nodeexecutorv2.RiskCode_RISK_CODE_R1
	default:
		return nodeexecutorv2.RiskCode_RISK_CODE_R0
	}
}
