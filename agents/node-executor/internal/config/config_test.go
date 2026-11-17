package config

import "testing"

func TestLoadRequiresExplicitEnrollmentToken(t *testing.T) {
	t.Setenv("KYLIN_ENROLLMENT_TOKEN", "")

	cfg := Load()

	if cfg.EnrollmentToken != "" {
		t.Fatalf("expected empty enrollment token when KYLIN_ENROLLMENT_TOKEN is not set")
	}
}

func TestLoadTrimsEnrollmentToken(t *testing.T) {
	t.Setenv("KYLIN_ENROLLMENT_TOKEN", "  test-token  ")

	cfg := Load()

	if cfg.EnrollmentToken != "test-token" {
		t.Fatalf("expected trimmed enrollment token, got %q", cfg.EnrollmentToken)
	}
}

func TestLoadCommandTimeoutSeconds(t *testing.T) {
	t.Setenv("KYLIN_COMMAND_TIMEOUT_SECONDS", "45")

	cfg := Load()

	if cfg.CommandTimeoutSeconds != 45 {
		t.Fatalf("expected command timeout 45, got %d", cfg.CommandTimeoutSeconds)
	}
}

func TestLoadMetricsAddress(t *testing.T) {
	t.Setenv("KYLIN_NODE_EXECUTOR_METRICS_ADDRESS", "127.0.0.1:19200")

	cfg := Load()

	if cfg.MetricsAddress != "127.0.0.1:19200" {
		t.Fatalf("expected metrics address override, got %q", cfg.MetricsAddress)
	}
}

func TestLoadNodeExporterURL(t *testing.T) {
	t.Setenv("KYLIN_NODE_EXPORTER_URL", " http://127.0.0.1:9100/metrics ")

	cfg := Load()

	if cfg.NodeExporterURL != "http://127.0.0.1:9100/metrics" {
		t.Fatalf("expected node exporter URL override, got %q", cfg.NodeExporterURL)
	}
}

func TestLoadRunnerPoolSettings(t *testing.T) {
	t.Setenv("KYLIN_SCHEDULER_MAX_PARALLEL", "3")
	t.Setenv("KYLIN_RUNNER_POOL_SIZE", "2")

	cfg := Load()

	if cfg.SchedulerMaxParallel != 3 {
		t.Fatalf("expected scheduler max parallel 3, got %d", cfg.SchedulerMaxParallel)
	}
	if cfg.RunnerPoolSize != 2 {
		t.Fatalf("expected runner pool size 2, got %d", cfg.RunnerPoolSize)
	}
}
