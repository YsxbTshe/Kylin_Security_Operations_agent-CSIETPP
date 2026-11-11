package config

import (
	"path/filepath"
	"testing"
)

func TestLoadLifecycleDefaults(t *testing.T) {
	cfg := Load()
	if cfg.RecoveryMode != "cleanup" {
		t.Errorf("RecoveryMode = %q, want cleanup", cfg.RecoveryMode)
	}
	if cfg.SnapshotKeep <= 0 {
		t.Errorf("SnapshotKeep = %d, want > 0", cfg.SnapshotKeep)
	}
	if cfg.SnapshotMaxAgeHours <= 0 {
		t.Errorf("SnapshotMaxAgeHours = %d, want > 0", cfg.SnapshotMaxAgeHours)
	}
	if cfg.IdleTimeoutSec <= 0 {
		t.Errorf("IdleTimeoutSec = %d, want > 0", cfg.IdleTimeoutSec)
	}
	if !cfg.AuditEnabled {
		t.Errorf("AuditEnabled default should be true")
	}
	want := filepath.Join(cfg.WorkspaceRoot, "audit", "workspace-audit.ndjson")
	if cfg.AuditLogPath != want {
		t.Errorf("AuditLogPath = %q, want %q", cfg.AuditLogPath, want)
	}
}

func TestLoadLifecycleOverrides(t *testing.T) {
	t.Setenv("KYLIN_WORKSPACE_RECOVERY_MODE", "recreate")
	t.Setenv("KYLIN_WORKSPACE_SNAPSHOT_KEEP", "3")
	t.Setenv("KYLIN_WORKSPACE_IDLE_TIMEOUT", "42")
	t.Setenv("KYLIN_WORKSPACE_AUDIT_ENABLED", "false")
	t.Setenv("KYLIN_WORKSPACE_AUDIT_LOG", "/tmp/ws-audit.ndjson")

	cfg := Load()
	if cfg.RecoveryMode != "recreate" {
		t.Errorf("RecoveryMode = %q, want recreate", cfg.RecoveryMode)
	}
	if cfg.SnapshotKeep != 3 {
		t.Errorf("SnapshotKeep = %d, want 3", cfg.SnapshotKeep)
	}
	if cfg.IdleTimeoutSec != 42 {
		t.Errorf("IdleTimeoutSec = %d, want 42", cfg.IdleTimeoutSec)
	}
	if cfg.AuditEnabled {
		t.Errorf("AuditEnabled should respect false")
	}
	if cfg.AuditLogPath != "/tmp/ws-audit.ndjson" {
		t.Errorf("AuditLogPath = %q", cfg.AuditLogPath)
	}
}
