package sandbox

import (
	"testing"
)

func TestContainerName(t *testing.T) {
	cfg := ContainerConfig{
		TenantID:    "org-alpha",
		AgentID:     "agent-01",
		WorkspaceID: "ws-abc123def456",
	}
	name := cfg.ContainerName()
	if name != "kylin-ws-org-alpha-agent-01-ws-abc123def" {
		t.Fatalf("unexpected name: %s", name)
	}
}

func TestContainerNameSanitizesSpecialChars(t *testing.T) {
	cfg := ContainerConfig{
		TenantID:    "ORG/ALPHA",
		AgentID:     "agent@01",
		WorkspaceID: "ws123",
	}
	name := cfg.ContainerName()
	for _, c := range name {
		if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
			t.Fatalf("container name contains invalid char %q in %q", string(c), name)
		}
	}
}

func TestIsValidPath(t *testing.T) {
	cases := []struct {
		path  string
		valid bool
	}{
		{"file.txt", true},
		{"src/main.go", true},
		{"a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p", true},
		{"../etc/passwd", false},
		{"foo/../../etc/passwd", false},
		{"/etc/passwd", false},
		{"file\x00.txt", false},
		{"", true},
		{".", true},
	}
	for _, tc := range cases {
		if got := IsValidPath(tc.path); got != tc.valid {
			t.Errorf("IsValidPath(%q) = %v, want %v", tc.path, got, tc.valid)
		}
	}
}

func TestIsValidPathRejectsDeepNesting(t *testing.T) {
	deep := "a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q"
	if IsValidPath(deep) {
		t.Fatal("should reject path deeper than 16 levels")
	}
}

func TestIsValidPathRejectsLongFileName(t *testing.T) {
	long := ""
	for i := 0; i < 260; i++ {
		long += "a"
	}
	if IsValidPath(long) {
		t.Fatal("should reject filename longer than 255 chars")
	}
}

func TestTruncateOutput(t *testing.T) {
	short := "hello"
	if got := truncateOutput(short, 100); got != short {
		t.Fatalf("short output should not be truncated")
	}
	long := "a]b]c]d]e]f]g]h]i]j]k]l]m]n]o]p]q]r]s]t]u]v]w]x]y]z"
	got := truncateOutput(long, 10)
	if len(got) > 30 {
		t.Fatalf("truncated output too long: %d", len(got))
	}
}

func TestSanitize(t *testing.T) {
	cases := []struct {
		input    string
		expected string
	}{
		{"hello", "hello"},
		{"HELLO", "hello"},
		{"hello world", "hello-world"},
		{"a/b/c", "a-b-c"},
	}
	for _, tc := range cases {
		if got := sanitize(tc.input); got != tc.expected {
			t.Errorf("sanitize(%q) = %q, want %q", tc.input, got, tc.expected)
		}
	}
}
