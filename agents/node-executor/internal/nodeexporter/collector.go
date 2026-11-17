package nodeexporter

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"math"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const defaultTimeout = 2 * time.Second

type Snapshot struct {
	Status                   string   `json:"status"`
	URL                      string   `json:"url,omitempty"`
	CollectedAtUnix          int64    `json:"collectedAtUnix"`
	Error                    string   `json:"error,omitempty"`
	CPUUsagePercent          *float64 `json:"cpuUsagePercent,omitempty"`
	MemoryUsagePercent       *float64 `json:"memoryUsagePercent,omitempty"`
	MemoryTotalBytes         *float64 `json:"memoryTotalBytes,omitempty"`
	MemoryAvailableBytes     *float64 `json:"memoryAvailableBytes,omitempty"`
	FilesystemUsagePercent   *float64 `json:"filesystemUsagePercent,omitempty"`
	FilesystemSizeBytes      *float64 `json:"filesystemSizeBytes,omitempty"`
	FilesystemAvailableBytes *float64 `json:"filesystemAvailableBytes,omitempty"`
	Load1                    *float64 `json:"load1,omitempty"`
	Load5                    *float64 `json:"load5,omitempty"`
	Load15                   *float64 `json:"load15,omitempty"`
}

type Client struct {
	url         string
	timeout     time.Duration
	httpClient  *http.Client
	mu          sync.Mutex
	previous    cpuCounters
	hasPrevious bool
}

type cpuCounters struct {
	Idle  float64
	Total float64
}

type sample struct {
	Name   string
	Labels map[string]string
	Value  float64
}

func NewClient(url string) *Client {
	return &Client{url: strings.TrimSpace(url), timeout: defaultTimeout}
}

func (c *Client) Collect(ctx context.Context) Snapshot {
	if c == nil || strings.TrimSpace(c.url) == "" {
		return Snapshot{Status: "DISABLED", CollectedAtUnix: time.Now().Unix()}
	}
	client := c.httpClient
	if client == nil {
		client = &http.Client{Timeout: c.timeout}
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url, nil)
	if err != nil {
		return Snapshot{Status: "ERROR", URL: c.url, CollectedAtUnix: time.Now().Unix(), Error: err.Error()}
	}
	response, err := client.Do(request)
	if err != nil {
		return Snapshot{Status: "DOWN", URL: c.url, CollectedAtUnix: time.Now().Unix(), Error: err.Error()}
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return Snapshot{Status: "DOWN", URL: c.url, CollectedAtUnix: time.Now().Unix(), Error: fmt.Sprintf("HTTP %d", response.StatusCode)}
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 4*1024*1024))
	if err != nil {
		return Snapshot{Status: "ERROR", URL: c.url, CollectedAtUnix: time.Now().Unix(), Error: err.Error()}
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	snapshot, counters := ParseText(string(body), c.previous, c.hasPrevious, time.Now().Unix(), c.url)
	if counters.Total > 0 {
		c.previous = counters
		c.hasPrevious = true
	}
	return snapshot
}

func ParseText(text string, previous cpuCounters, hasPrevious bool, collectedAtUnix int64, url string) (Snapshot, cpuCounters) {
	snapshot := Snapshot{Status: "UP", URL: strings.TrimSpace(url), CollectedAtUnix: collectedAtUnix}
	currentCPU := cpuCounters{}
	memoryTotal := math.NaN()
	memoryAvailable := math.NaN()
	filesystemSize := math.NaN()
	filesystemAvailable := math.NaN()

	for _, item := range parseSamples(text) {
		switch item.Name {
		case "node_cpu_seconds_total":
			mode := item.Labels["mode"]
			currentCPU.Total += item.Value
			if mode == "idle" || mode == "iowait" {
				currentCPU.Idle += item.Value
			}
		case "node_memory_MemTotal_bytes":
			memoryTotal = item.Value
		case "node_memory_MemAvailable_bytes":
			memoryAvailable = item.Value
		case "node_filesystem_size_bytes":
			if item.Labels["mountpoint"] == "/" && !ignoredFilesystem(item.Labels["fstype"]) {
				filesystemSize = item.Value
			}
		case "node_filesystem_avail_bytes", "node_filesystem_free_bytes":
			if item.Labels["mountpoint"] == "/" && !ignoredFilesystem(item.Labels["fstype"]) {
				filesystemAvailable = item.Value
			}
		case "node_load1":
			snapshot.Load1 = roundedPtr(item.Value)
		case "node_load5":
			snapshot.Load5 = roundedPtr(item.Value)
		case "node_load15":
			snapshot.Load15 = roundedPtr(item.Value)
		}
	}

	if hasPrevious && currentCPU.Total > previous.Total {
		idleDelta := currentCPU.Idle - previous.Idle
		totalDelta := currentCPU.Total - previous.Total
		if totalDelta > 0 {
			usage := 100 * (1 - idleDelta/totalDelta)
			snapshot.CPUUsagePercent = percentPtr(usage)
		}
	}
	if memoryTotal > 0 && !math.IsNaN(memoryAvailable) {
		snapshot.MemoryTotalBytes = roundedPtr(memoryTotal)
		snapshot.MemoryAvailableBytes = roundedPtr(memoryAvailable)
		snapshot.MemoryUsagePercent = percentPtr(100 * (1 - memoryAvailable/memoryTotal))
	}
	if filesystemSize > 0 && !math.IsNaN(filesystemAvailable) {
		snapshot.FilesystemSizeBytes = roundedPtr(filesystemSize)
		snapshot.FilesystemAvailableBytes = roundedPtr(filesystemAvailable)
		snapshot.FilesystemUsagePercent = percentPtr(100 * (1 - filesystemAvailable/filesystemSize))
	}
	return snapshot, currentCPU
}

func parseSamples(text string) []sample {
	result := []sample{}
	scanner := bufio.NewScanner(strings.NewReader(text))
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		value, err := strconv.ParseFloat(fields[1], 64)
		if err != nil {
			continue
		}
		name, labels := parseMetricNameAndLabels(fields[0])
		if name == "" {
			continue
		}
		result = append(result, sample{Name: name, Labels: labels, Value: value})
	}
	return result
}

func parseMetricNameAndLabels(value string) (string, map[string]string) {
	labels := map[string]string{}
	open := strings.IndexByte(value, '{')
	if open < 0 {
		return strings.TrimSpace(value), labels
	}
	close := strings.LastIndexByte(value, '}')
	if close <= open {
		return strings.TrimSpace(value[:open]), labels
	}
	name := strings.TrimSpace(value[:open])
	for _, part := range splitLabels(value[open+1 : close]) {
		key, raw, ok := strings.Cut(part, "=")
		if !ok {
			continue
		}
		labels[strings.TrimSpace(key)] = strings.Trim(strings.TrimSpace(raw), "\"")
	}
	return name, labels
}

func splitLabels(value string) []string {
	parts := []string{}
	var builder strings.Builder
	inQuote := false
	escaped := false
	for _, char := range value {
		if escaped {
			builder.WriteRune(char)
			escaped = false
			continue
		}
		if char == '\\' {
			escaped = true
			builder.WriteRune(char)
			continue
		}
		if char == '"' {
			inQuote = !inQuote
			builder.WriteRune(char)
			continue
		}
		if char == ',' && !inQuote {
			parts = append(parts, builder.String())
			builder.Reset()
			continue
		}
		builder.WriteRune(char)
	}
	if builder.Len() > 0 {
		parts = append(parts, builder.String())
	}
	return parts
}

func ignoredFilesystem(fstype string) bool {
	switch strings.TrimSpace(fstype) {
	case "", "tmpfs", "devtmpfs", "overlay", "squashfs", "proc", "sysfs", "cgroup", "cgroup2":
		return true
	default:
		return false
	}
}

func percentPtr(value float64) *float64 {
	if value < 0 {
		value = 0
	}
	if value > 100 {
		value = 100
	}
	return roundedPtr(value)
}

func roundedPtr(value float64) *float64 {
	rounded := math.Round(value*100) / 100
	return &rounded
}
