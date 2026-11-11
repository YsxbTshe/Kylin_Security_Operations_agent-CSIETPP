package nodeexporter

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

const testMetricsFirst = `# HELP node_cpu_seconds_total Seconds the CPUs spent in each mode.
node_cpu_seconds_total{cpu="0",mode="idle"} 100
node_cpu_seconds_total{cpu="0",mode="user"} 50
node_cpu_seconds_total{cpu="0",mode="system"} 50
node_memory_MemTotal_bytes 1000
node_memory_MemAvailable_bytes 250
node_filesystem_size_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 2000
node_filesystem_avail_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 500
node_load1 0.5
node_load5 0.4
node_load15 0.3
`

const testMetricsSecond = `node_cpu_seconds_total{cpu="0",mode="idle"} 110
node_cpu_seconds_total{cpu="0",mode="user"} 70
node_cpu_seconds_total{cpu="0",mode="system"} 70
node_memory_MemTotal_bytes 1000
node_memory_MemAvailable_bytes 200
node_filesystem_size_bytes{device="tmpfs",fstype="tmpfs",mountpoint="/"} 100
node_filesystem_avail_bytes{device="tmpfs",fstype="tmpfs",mountpoint="/"} 90
node_filesystem_size_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 2000
node_filesystem_avail_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 400
node_load1 0.8
node_load5 0.7
node_load15 0.6
`

func TestParseTextExtractsNodeExporterSnapshot(t *testing.T) {
	first, counters := ParseText(testMetricsFirst, cpuCounters{}, false, 100, "http://127.0.0.1:9100/metrics")
	if first.Status != "UP" || first.CPUUsagePercent != nil {
		t.Fatalf("expected first scrape without CPU percent, got %+v", first)
	}
	if first.MemoryUsagePercent == nil || *first.MemoryUsagePercent != 75 {
		t.Fatalf("expected memory usage 75, got %+v", first.MemoryUsagePercent)
	}
	if first.FilesystemUsagePercent == nil || *first.FilesystemUsagePercent != 75 {
		t.Fatalf("expected filesystem usage 75, got %+v", first.FilesystemUsagePercent)
	}
	if first.Load1 == nil || *first.Load1 != 0.5 {
		t.Fatalf("expected load1 0.5, got %+v", first.Load1)
	}

	second, _ := ParseText(testMetricsSecond, counters, true, 101, "http://127.0.0.1:9100/metrics")
	if second.CPUUsagePercent == nil || *second.CPUUsagePercent != 80 {
		t.Fatalf("expected CPU usage 80, got %+v", second.CPUUsagePercent)
	}
	if second.MemoryUsagePercent == nil || *second.MemoryUsagePercent != 80 {
		t.Fatalf("expected memory usage 80, got %+v", second.MemoryUsagePercent)
	}
	if second.FilesystemUsagePercent == nil || *second.FilesystemUsagePercent != 80 {
		t.Fatalf("expected filesystem usage 80, got %+v", second.FilesystemUsagePercent)
	}
}

func TestClientCollectDisabledWithoutURL(t *testing.T) {
	client := NewClient("")
	snapshot := client.Collect(context.Background())
	if snapshot.Status != "DISABLED" {
		t.Fatalf("expected disabled snapshot, got %+v", snapshot)
	}
}

func TestClientCollectsFromHTTPServer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(testMetricsFirst))
	}))
	defer server.Close()

	client := NewClient(server.URL)
	snapshot := client.Collect(context.Background())
	if snapshot.Status != "UP" || snapshot.MemoryUsagePercent == nil || *snapshot.MemoryUsagePercent != 75 {
		t.Fatalf("unexpected snapshot: %+v", snapshot)
	}
}
