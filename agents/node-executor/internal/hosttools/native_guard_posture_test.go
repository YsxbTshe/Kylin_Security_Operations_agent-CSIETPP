package hosttools

import "testing"

func TestNativeGuardOverall(t *testing.T) {
	if got := nativeGuardOverall([]NativeGuardPostureItem{{Tool: "a", Supported: true, Status: "active"}}); got != "healthy" {
		t.Fatalf("expected healthy, got %s", got)
	}
	if got := nativeGuardOverall([]NativeGuardPostureItem{{Tool: "a", Supported: true, Status: "permissive"}}); got != "warning" {
		t.Fatalf("expected warning, got %s", got)
	}
	if got := nativeGuardOverall([]NativeGuardPostureItem{{Tool: "a", Supported: true, Status: "disabled"}}); got != "critical" {
		t.Fatalf("expected critical, got %s", got)
	}
	if got := nativeGuardOverall([]NativeGuardPostureItem{{Tool: "a", Supported: false, Status: "unsupported"}}); got != "unsupported" {
		t.Fatalf("expected unsupported, got %s", got)
	}
}

func TestNativeGuardCountsSkipUnsupported(t *testing.T) {
	critical, warning := nativeGuardCounts([]NativeGuardPostureItem{
		{Tool: "a", Supported: true, Status: "inactive"},
		{Tool: "b", Supported: true, Status: "permissive"},
		{Tool: "c", Supported: false, Status: "unsupported"},
		{Tool: "d", Supported: true, Status: "error"},
	})
	if critical != 2 || warning != 1 {
		t.Fatalf("expected 2 critical / 1 warning, got %d / %d", critical, warning)
	}
}
