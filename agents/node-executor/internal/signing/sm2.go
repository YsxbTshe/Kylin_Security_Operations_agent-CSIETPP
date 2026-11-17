package signing

import (
	"crypto/ecdsa"
	"crypto/rand"
	"encoding/pem"
	"errors"
	"fmt"

	"github.com/emmansun/gmsm/sm2"
	"github.com/emmansun/gmsm/smx509"
)

type KeyPurpose string

const (
	ApprovalSigning KeyPurpose = "approval-signing-key"
	CatalogSigning  KeyPurpose = "catalog-signing-key"
	NodeCA          KeyPurpose = "node-ca-key"
	PackageSigning  KeyPurpose = "package-signing-key"
)

func (p KeyPurpose) UserID() ([]byte, error) {
	switch p {
	case ApprovalSigning:
		return []byte("KYLIN-APPROVAL-V1"), nil
	case CatalogSigning:
		return []byte("KYLIN-CATALOG-V1"), nil
	case NodeCA:
		return []byte("KYLIN-NODE-CA-V1"), nil
	case PackageSigning:
		return []byte("KYLIN-PACKAGE-V1"), nil
	default:
		return nil, fmt.Errorf("unknown key purpose %q", p)
	}
}

func ParsePrivateKeyPEM(value []byte) (*sm2.PrivateKey, error) {
	block, _ := pem.Decode(value)
	if block == nil {
		return nil, errors.New("SM2 private key is not PEM")
	}
	parsed, err := smx509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse SM2 PKCS#8 private key: %w", err)
	}
	key, ok := parsed.(*sm2.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("private key is %T, not SM2", parsed)
	}
	return key, nil
}

func ParsePublicKeyPEM(value []byte) (*ecdsa.PublicKey, error) {
	block, _ := pem.Decode(value)
	if block == nil {
		return nil, errors.New("SM2 public key is not PEM")
	}
	parsed, err := smx509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse SM2 SubjectPublicKeyInfo: %w", err)
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || !sm2.IsSM2PublicKey(key) {
		return nil, fmt.Errorf("public key is %T, not SM2", parsed)
	}
	return key, nil
}

func Sign(privateKey *sm2.PrivateKey, purpose KeyPurpose, payload []byte) ([]byte, error) {
	if privateKey == nil {
		return nil, errors.New("SM2 private key is required")
	}
	userID, err := purpose.UserID()
	if err != nil {
		return nil, err
	}
	return privateKey.SignWithSM2(rand.Reader, userID, payload)
}

func Verify(publicKey *ecdsa.PublicKey, purpose KeyPurpose, payload, signature []byte) bool {
	if publicKey == nil || len(signature) == 0 {
		return false
	}
	userID, err := purpose.UserID()
	if err != nil {
		return false
	}
	return sm2.VerifyASN1WithSM2(publicKey, userID, payload, signature)
}
