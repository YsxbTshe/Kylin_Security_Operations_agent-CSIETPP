package workspace

import (
	"testing"
	"time"
)

func TestCallReturnsErrorOnUnreachableSocket(t *testing.T) {
	client := NewClient("/nonexistent/socket.sock", time.Second)
	_, err := client.Call(Request{Action: "exec"})
	if err == nil {
		t.Fatal("expected error for unreachable socket")
	}
}

func TestNewClientDefaults(t *testing.T) {
	c := NewClient("", 0)
	if c.socketPath != DefaultSocketPath {
		t.Fatalf("expected default socket path, got %q", c.socketPath)
	}
	if c.timeout != 5*time.Minute {
		t.Fatalf("expected 5m timeout, got %v", c.timeout)
	}
}

func TestNewClientCustomValues(t *testing.T) {
	c := NewClient("/custom/path.sock", 10*time.Second)
	if c.socketPath != "/custom/path.sock" {
		t.Fatalf("expected custom socket path")
	}
	if c.timeout != 10*time.Second {
		t.Fatalf("expected 10s timeout")
	}
}
