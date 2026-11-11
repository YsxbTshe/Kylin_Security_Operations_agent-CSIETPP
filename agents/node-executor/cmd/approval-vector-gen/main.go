package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/types/known/timestamppb"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	nodeexecutorv2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
)

func main() {
	privatePath := flag.String("private-key", "../../contracts/test-vectors/TEST_ONLY_sm2_private.pem", "test SM2 private key")
	flag.Parse()
	privatePEM, err := os.ReadFile(*privatePath)
	check(err)
	privateKey, err := signing.ParsePrivateKeyPEM(privatePEM)
	check(err)
	issuedAt := time.Unix(1_800_000_000, 0).UTC()
	claim := &nodeexecutorv2.ApprovalClaim{
		Version: 1, TenantId: "org-alpha", RunId: "run-vector-1", AgentInstanceId: "agent-vector-1",
		AgentInvocationId: "invocation-vector-1", ToolInvocationId: "tool-vector-1", ExecutionAttemptId: "attempt-vector-1",
		NodeId: "node-vector-1", ToolId: "kylin.service.restart", ToolVersion: "1.0.0",
		ArgumentsSm3: []byte{0x8d, 0x4c, 0x5a, 0x71, 0x91, 0x4f, 0x80, 0x68, 0x3a, 0x91, 0xab, 0x11, 0x7e, 0x89, 0x0b, 0x0f, 0x2d, 0x71, 0x8c, 0x78, 0x5f, 0x22, 0xc1, 0xe7, 0xde, 0x6f, 0xd1, 0x6c, 0x51, 0x30, 0x66, 0x7a},
		RiskCeiling:  nodeexecutorv2.RiskCode_RISK_CODE_R3, ExecutionMode: nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS,
		LockVersion: 42, KeyPurpose: string(signing.ApprovalSigning), IssuedAt: timestamppb.New(issuedAt), ExpiresAt: timestamppb.New(issuedAt.Add(5 * time.Minute)),
	}
	token, err := approvalv2.Issue(claim, "approval-test-1", privateKey)
	check(err)
	claimJSON, err := protojson.MarshalOptions{UseProtoNames: true}.Marshal(claim)
	check(err)
	var claimValue any
	check(json.Unmarshal(claimJSON, &claimValue))
	output, err := json.MarshalIndent(map[string]any{
		"description": "TEST ONLY: fixed Java/Go Approval Token interoperability vector",
		"keyId":       "approval-test-1", "sm2UserId": "KYLIN-APPROVAL-V1", "claim": claimValue, "token": token,
	}, "", "  ")
	check(err)
	fmt.Println(string(output))
}

func check(err error) {
	if err != nil {
		panic(err)
	}
}
