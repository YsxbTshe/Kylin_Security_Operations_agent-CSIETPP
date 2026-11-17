package signing

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSM2WithSM3PurposeSeparation(t *testing.T) {
	privatePEM, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "contracts", "test-vectors", "TEST_ONLY_sm2_private.pem"))
	if err != nil {
		t.Fatal(err)
	}
	publicPEM, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "contracts", "test-vectors", "TEST_ONLY_sm2_public.pem"))
	if err != nil {
		t.Fatal(err)
	}
	privateKey, err := ParsePrivateKeyPEM(privatePEM)
	if err != nil {
		t.Fatal(err)
	}
	publicKey, err := ParsePublicKeyPEM(publicPEM)
	if err != nil {
		t.Fatal(err)
	}

	payload := []byte("kylin-contract-vector-v1")
	signature, err := Sign(privateKey, ApprovalSigning, payload)
	if err != nil {
		t.Fatal(err)
	}
	if !Verify(publicKey, ApprovalSigning, payload, signature) {
		t.Fatal("approval signature did not verify")
	}
	if Verify(publicKey, CatalogSigning, payload, signature) {
		t.Fatal("approval signature must not verify for catalog purpose")
	}
	if Verify(publicKey, ApprovalSigning, []byte("tampered"), signature) {
		t.Fatal("tampered payload must not verify")
	}
}
