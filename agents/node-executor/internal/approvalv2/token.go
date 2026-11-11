package approvalv2

import (
	"crypto/ecdsa"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/emmansun/gmsm/sm2"
	"google.golang.org/protobuf/proto"

	nodeexecutorv2 "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/controlstream/proto/v2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
)

const (
	TokenPrefix  = "kylin-approval-v1."
	TokenVersion = uint32(1)
)

var deterministic = proto.MarshalOptions{Deterministic: true}

type PublicKeyResolver interface {
	ResolveApprovalPublicKey(keyID string) (*ecdsa.PublicKey, bool)
}

type StaticPublicKeys map[string]*ecdsa.PublicKey

func (s StaticPublicKeys) ResolveApprovalPublicKey(keyID string) (*ecdsa.PublicKey, bool) {
	key, ok := s[keyID]
	return key, ok
}

func Issue(claim *nodeexecutorv2.ApprovalClaim, keyID string, privateKey *sm2.PrivateKey) (string, error) {
	if err := validateClaim(claim); err != nil {
		return "", err
	}
	if strings.TrimSpace(keyID) == "" {
		return "", errors.New("approval signing key ID is required")
	}
	payload, err := deterministic.Marshal(claim)
	if err != nil {
		return "", fmt.Errorf("serialize approval claim: %w", err)
	}
	signature, err := signing.Sign(privateKey, signing.ApprovalSigning, payload)
	if err != nil {
		return "", fmt.Errorf("sign approval claim: %w", err)
	}
	token := &nodeexecutorv2.ApprovalToken{
		Version:         TokenVersion,
		KeyId:           keyID,
		Claim:           claim,
		Sm2DerSignature: signature,
	}
	encoded, err := deterministic.Marshal(token)
	if err != nil {
		return "", fmt.Errorf("serialize approval token: %w", err)
	}
	return TokenPrefix + base64.RawURLEncoding.EncodeToString(encoded), nil
}

func Verify(encoded string, keys PublicKeyResolver, now time.Time) (*nodeexecutorv2.ApprovalClaim, error) {
	if !strings.HasPrefix(encoded, TokenPrefix) {
		return nil, errors.New("invalid approval token prefix")
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(encoded, TokenPrefix))
	if err != nil {
		return nil, errors.New("approval token is not valid base64url")
	}
	var token nodeexecutorv2.ApprovalToken
	if err := proto.Unmarshal(raw, &token); err != nil {
		return nil, fmt.Errorf("decode approval token: %w", err)
	}
	if token.Version != TokenVersion {
		return nil, fmt.Errorf("unsupported approval token version %d", token.Version)
	}
	if err := validateClaim(token.Claim); err != nil {
		return nil, err
	}
	publicKey, ok := keys.ResolveApprovalPublicKey(token.KeyId)
	if !ok {
		return nil, fmt.Errorf("unknown approval signing key %q", token.KeyId)
	}
	payload, err := deterministic.Marshal(token.Claim)
	if err != nil {
		return nil, fmt.Errorf("serialize approval claim: %w", err)
	}
	if !signing.Verify(publicKey, signing.ApprovalSigning, payload, token.Sm2DerSignature) {
		return nil, errors.New("approval token signature is invalid")
	}
	issuedAt := token.Claim.IssuedAt.AsTime()
	expiresAt := token.Claim.ExpiresAt.AsTime()
	if now.Before(issuedAt.Add(-30 * time.Second)) {
		return nil, errors.New("approval token is not active yet")
	}
	if !now.Before(expiresAt) {
		return nil, errors.New("approval token has expired")
	}
	return token.Claim, nil
}

// HasValidShape 只判断令牌的骨架是否正确（前缀、base64url、protobuf 可解码、
// 版本号与 claim 存在），不验证签名、有效期，也不校验 claim 内容。
//
// 这是 daemon 侧唯一能做的检查——它不持有任何签名材料。用途是快速失败：
// 骨架就不对的令牌不必再穿越特权边界到助手。它**不构成安全边界**，
// 真正的判定在 Verify，由持有公钥解析器的特权助手执行。
func HasValidShape(encoded string) bool {
	if !strings.HasPrefix(encoded, TokenPrefix) {
		return false
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(encoded, TokenPrefix))
	if err != nil {
		return false
	}
	var token nodeexecutorv2.ApprovalToken
	if err := proto.Unmarshal(raw, &token); err != nil {
		return false
	}
	return token.Version == TokenVersion && token.Claim != nil
}

func CoversInvocation(claim, actual *nodeexecutorv2.ApprovalClaim) bool {
	if claim == nil || actual == nil {
		return false
	}
	return claim.TenantId == actual.TenantId &&
		claim.RunId == actual.RunId &&
		claim.AgentInstanceId == actual.AgentInstanceId &&
		claim.AgentInvocationId == actual.AgentInvocationId &&
		claim.ToolInvocationId == actual.ToolInvocationId &&
		claim.ExecutionAttemptId == actual.ExecutionAttemptId &&
		claim.NodeId == actual.NodeId &&
		claim.ToolId == actual.ToolId &&
		claim.ToolVersion == actual.ToolVersion &&
		string(claim.ArgumentsSm3) == string(actual.ArgumentsSm3) &&
		claim.RiskCeiling >= actual.RiskCeiling &&
		claim.ExecutionMode == actual.ExecutionMode &&
		claim.LockVersion == actual.LockVersion &&
		claim.KeyPurpose == string(signing.ApprovalSigning)
}

func validateClaim(claim *nodeexecutorv2.ApprovalClaim) error {
	if claim == nil {
		return errors.New("approval claim is required")
	}
	if claim.Version != TokenVersion {
		return fmt.Errorf("unsupported approval claim version %d", claim.Version)
	}
	if claim.KeyPurpose != string(signing.ApprovalSigning) {
		return errors.New("approval claim has wrong key purpose")
	}
	for name, value := range map[string]string{
		"tenant_id": claim.TenantId, "run_id": claim.RunId,
		"agent_instance_id": claim.AgentInstanceId, "agent_invocation_id": claim.AgentInvocationId,
		"tool_invocation_id": claim.ToolInvocationId, "execution_attempt_id": claim.ExecutionAttemptId,
		"node_id": claim.NodeId, "tool_id": claim.ToolId, "tool_version": claim.ToolVersion,
	} {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("approval claim %s is required", name)
		}
	}
	if len(claim.ArgumentsSm3) != 32 {
		return errors.New("approval claim arguments_sm3 must be 32 bytes")
	}
	if claim.RiskCeiling < nodeexecutorv2.RiskCode_RISK_CODE_R0 || claim.RiskCeiling > nodeexecutorv2.RiskCode_RISK_CODE_R4 {
		return errors.New("approval claim risk ceiling must be R0 through R4")
	}
	if claim.ExecutionMode != nodeexecutorv2.ExecutionMode_EXECUTION_MODE_CAUTIOUS &&
		claim.ExecutionMode != nodeexecutorv2.ExecutionMode_EXECUTION_MODE_AUTO {
		return errors.New("approval claim execution mode must be CAUTIOUS or AUTO")
	}
	if claim.LockVersion == 0 {
		return errors.New("approval claim lock_version is required")
	}
	if claim.IssuedAt == nil || claim.ExpiresAt == nil || !claim.ExpiresAt.AsTime().After(claim.IssuedAt.AsTime()) {
		return errors.New("approval claim validity window is invalid")
	}
	if claim.ExpiresAt.AsTime().Sub(claim.IssuedAt.AsTime()) > 5*time.Minute {
		return errors.New("approval token validity exceeds five minutes")
	}
	return nil
}
