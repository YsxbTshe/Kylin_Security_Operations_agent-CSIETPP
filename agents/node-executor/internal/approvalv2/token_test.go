package approvalv2

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"

	nodeexecutorv2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
)

func TestVerifiesSharedJavaGoVector(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	publicPEM, err := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	if err != nil {
		t.Fatal(err)
	}
	publicKey, err := signing.ParsePublicKeyPEM(publicPEM)
	if err != nil {
		t.Fatal(err)
	}
	vectorBytes, err := os.ReadFile(filepath.Join(root, "approval-token-v1.json"))
	if err != nil {
		t.Fatal(err)
	}
	var vector struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(vectorBytes, &vector); err != nil {
		t.Fatal(err)
	}
	claim, err := Verify(vector.Token, StaticPublicKeys{"approval-test-1": publicKey}, time.Date(2027, 1, 15, 8, 1, 0, 0, time.UTC))
	if err != nil {
		t.Fatal(err)
	}
	if claim.RunId != "run-vector-1" || claim.LockVersion != 42 {
		t.Fatalf("unexpected shared claim: %+v", claim)
	}
}

func TestIssueVerifyAndExactCoverage(t *testing.T) {
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

	now := time.Unix(1_800_000_000, 0).UTC()
	claim := testClaim(now)
	token, err := Issue(claim, "approval-test-1", privateKey)
	if err != nil {
		t.Fatal(err)
	}
	verified, err := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if !CoversInvocation(verified, claim) {
		t.Fatal("verified claim must cover the exact invocation")
	}

	tampered := protoCloneClaim(claim)
	tampered.NodeId = "node-2"
	if CoversInvocation(verified, tampered) {
		t.Fatal("approval must not cover a different node")
	}
	if _, err := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(6*time.Minute)); err == nil {
		t.Fatal("expired token must be rejected")
	}
}

func testClaim(now time.Time) *nodeexecutorv2.ApprovalClaim {
	return &nodeexecutorv2.ApprovalClaim{
		Version: 1, TenantId: "org-alpha", RunId: "run-1", AgentInstanceId: "agent-1",
		AgentInvocationId: "model-call-1", ToolInvocationId: "tool-call-1", ExecutionAttemptId: "attempt-1",
		NodeId: "node-1", ToolId: "kylin.service.restart", ToolVersion: "1.0.0",
		ArgumentsSm3: make([]byte, 32), RiskCeiling: nodeexecutorv2.RiskCode_RISK_CODE_R3,
		ExecutionMode: nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS, LockVersion: 7,
		KeyPurpose: string(signing.ApprovalSigning), IssuedAt: timestamppb.New(now), ExpiresAt: timestamppb.New(now.Add(5 * time.Minute)),
	}
}

func protoCloneClaim(value *nodeexecutorv2.ApprovalClaim) *nodeexecutorv2.ApprovalClaim {
	return proto.Clone(value).(*nodeexecutorv2.ApprovalClaim)
}

func TestForgeryWithTamperedSignatureIsRejected(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	token, err := Issue(testClaim(now), "approval-test-1", privateKey)
	if err != nil {
		t.Fatal(err)
	}
	forged := token[:len(token)-4] + "AAAA"
	if _, err := Verify(forged, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute)); err == nil {
		t.Fatal("forged signature must be rejected")
	}
}

func TestReplayWithExpiredTokenIsRejected(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	token, err := Issue(testClaim(now), "approval-test-1", privateKey)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(6*time.Minute)); err == nil {
		t.Fatal("expired token must be rejected")
	}
}

func TestCrossNodeBindingIsEnforced(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	original := testClaim(now)
	token, _ := Issue(original, "approval-test-1", privateKey)
	verified, err := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	different := protoCloneClaim(original)
	different.NodeId = "node-2"
	if CoversInvocation(verified, different) {
		t.Fatal("approval must not cover a different node")
	}
}

func TestCrossRunBindingIsEnforced(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	original := testClaim(now)
	token, _ := Issue(original, "approval-test-1", privateKey)
	verified, _ := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute))

	different := protoCloneClaim(original)
	different.RunId = "run-999"
	if CoversInvocation(verified, different) {
		t.Fatal("approval must not cover a different run")
	}
}

func TestParameterTamperingViaArgumentsSm3IsDetected(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	original := testClaim(now)
	token, _ := Issue(original, "approval-test-1", privateKey)
	verified, _ := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute))

	tampered := protoCloneClaim(original)
	tampered.ArgumentsSm3 = make([]byte, 32)
	tampered.ArgumentsSm3[0] = 0xFF
	if CoversInvocation(verified, tampered) {
		t.Fatal("tampered arguments digest must not be covered")
	}
}

func TestExecutionModeTamperingIsDetected(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	original := testClaim(now)
	token, _ := Issue(original, "approval-test-1", privateKey)
	verified, _ := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute))

	if verified.ExecutionMode != nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS {
		t.Fatal("verified claim should be CAUTIOUS")
	}
	switched := protoCloneClaim(original)
	switched.ExecutionMode = nodeexecutorv2.ExecutionMode_EXECUTION_MODE_AUTO
	if CoversInvocation(verified, switched) {
		t.Fatal("mode switch must not be covered")
	}
}

func TestUnknownKeyIdIsRejected(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	token, _ := Issue(testClaim(now), "approval-test-1", privateKey)
	if _, err := Verify(token, StaticPublicKeys{"wrong-key-id": publicKey}, now.Add(time.Minute)); err == nil {
		t.Fatal("unknown key ID must be rejected")
	}
}

func TestLockVersionTamperingIsDetected(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	original := testClaim(now)
	token, _ := Issue(original, "approval-test-1", privateKey)
	verified, _ := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute))

	bumped := protoCloneClaim(original)
	bumped.LockVersion = 999
	if CoversInvocation(verified, bumped) {
		t.Fatal("lock version tampering must not be covered")
	}
}

func TestRiskCeilingEscalationIsDetected(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	privatePEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_private.pem"))
	publicPEM, _ := os.ReadFile(filepath.Join(root, "TEST_ONLY_sm2_public.pem"))
	privateKey, _ := signing.ParsePrivateKeyPEM(privatePEM)
	publicKey, _ := signing.ParsePublicKeyPEM(publicPEM)

	now := time.Unix(1_800_000_000, 0).UTC()
	original := testClaim(now)
	token, _ := Issue(original, "approval-test-1", privateKey)
	verified, _ := Verify(token, StaticPublicKeys{"approval-test-1": publicKey}, now.Add(time.Minute))

	if verified.RiskCeiling != nodeexecutorv2.RiskCode_RISK_CODE_R3 {
		t.Fatal("verified claim should be R3")
	}
	escalated := protoCloneClaim(original)
	escalated.RiskCeiling = nodeexecutorv2.RiskCode_RISK_CODE_R4
	if CoversInvocation(verified, escalated) {
		t.Fatal("risk ceiling escalation must not be covered")
	}
}
