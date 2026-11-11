package canonicaljson

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestMatchesSharedRFC8785Vector(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..", "contracts", "test-vectors")
	input, err := os.ReadFile(filepath.Join(root, "canonical-json-input.json"))
	if err != nil {
		t.Fatal(err)
	}
	expected, err := os.ReadFile(filepath.Join(root, "canonical-json-expected.json"))
	if err != nil {
		t.Fatal(err)
	}
	actual, err := Transform(input)
	if err != nil {
		t.Fatal(err)
	}
	if string(actual) != strings.TrimSpace(string(expected)) {
		t.Fatalf("canonical JSON mismatch: got %s", actual)
	}
}
