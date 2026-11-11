package gmcrypto

import "testing"

func TestSM3HexStringMatchesKnownVector(t *testing.T) {
	if got := SM3HexString("abc"); got != "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0" {
		t.Fatalf("unexpected SM3 digest: %s", got)
	}
}
