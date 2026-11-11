package controlstreamv2

import "testing"

func TestControlTLSServerNameFallsBackInOrder(t *testing.T) {
	t.Setenv(envControlServerName, "")
	t.Setenv("KYLIN_ENROLL_SERVER_NAME", "")
	if got := controlTLSServerName(); got != "localhost" {
		t.Fatalf("default ServerName must be localhost, got %q", got)
	}

	t.Setenv("KYLIN_ENROLL_SERVER_NAME", "enroll.example")
	if got := controlTLSServerName(); got != "enroll.example" {
		t.Fatalf("should fall back to enroll server name, got %q", got)
	}

	t.Setenv(envControlServerName, "control.example")
	if got := controlTLSServerName(); got != "control.example" {
		t.Fatalf("control server name must win, got %q", got)
	}
}
