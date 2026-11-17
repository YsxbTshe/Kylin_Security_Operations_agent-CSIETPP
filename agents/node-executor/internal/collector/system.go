package collector

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/pkg/model"
)

type Service struct{}

func NewService() Service {
	return Service{}
}

func (Service) CollectSystemSnapshot(nodeID string) model.SystemSnapshot {
	hostname, _ := os.Hostname()
	if strings.TrimSpace(hostname) == "" {
		hostname = nodeID
	}

	return model.SystemSnapshot{
		NodeID:      nodeID,
		Hostname:    hostname,
		IPAddress:   detectPrimaryIPv4(),
		OSVersion:   detectOSVersion(),
		CPUUsage:    detectCPUUsage(),
		MemoryUsage: detectMemoryUsage(),
		DiskUsage:   detectDiskUsage("/"),
		LoadAverage: detectLoadAverage(),
		UptimeText:  detectUptime(),
	}
}

func detectPrimaryIPv4() string {
	interfaces, err := net.Interfaces()
	if err != nil {
		return "-"
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addresses {
			ipNet, ok := addr.(*net.IPNet)
			if !ok || ipNet.IP == nil || ipNet.IP.IsLoopback() {
				continue
			}
			if ipv4 := ipNet.IP.To4(); ipv4 != nil {
				return ipv4.String()
			}
		}
	}
	return "-"
}

func detectOSVersion() string {
	if content, err := os.ReadFile("/etc/os-release"); err == nil {
		for _, line := range strings.Split(string(content), "\n") {
			if strings.HasPrefix(line, "PRETTY_NAME=") {
				return strings.Trim(strings.TrimPrefix(line, "PRETTY_NAME="), "\"")
			}
		}
	}
	return runtime.GOOS
}

func detectCPUUsage() string {
	firstIdle, firstTotal, err := readCPUStat()
	if err != nil {
		return "-"
	}
	time.Sleep(120 * time.Millisecond)
	secondIdle, secondTotal, err := readCPUStat()
	if err != nil || secondTotal <= firstTotal {
		return "-"
	}
	idleDelta := secondIdle - firstIdle
	totalDelta := secondTotal - firstTotal
	usage := 100 * (1 - float64(idleDelta)/float64(totalDelta))
	if usage < 0 {
		usage = 0
	}
	return fmt.Sprintf("%.1f%%", usage)
}

func readCPUStat() (uint64, uint64, error) {
	content, err := os.ReadFile("/proc/stat")
	if err != nil {
		return 0, 0, err
	}
	lines := strings.Split(string(content), "\n")
	if len(lines) == 0 {
		return 0, 0, fmt.Errorf("missing cpu stat")
	}
	fields := strings.Fields(lines[0])
	if len(fields) < 5 {
		return 0, 0, fmt.Errorf("invalid cpu stat")
	}
	var total uint64
	values := make([]uint64, 0, len(fields)-1)
	for _, field := range fields[1:] {
		value, err := strconv.ParseUint(field, 10, 64)
		if err != nil {
			return 0, 0, err
		}
		values = append(values, value)
		total += value
	}
	idle := values[3]
	if len(values) > 4 {
		idle += values[4]
	}
	return idle, total, nil
}

func detectMemoryUsage() string {
	content, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return "-"
	}
	var totalKB, availableKB float64
	for _, line := range strings.Split(string(content), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		switch fields[0] {
		case "MemTotal:":
			totalKB, _ = strconv.ParseFloat(fields[1], 64)
		case "MemAvailable:":
			availableKB, _ = strconv.ParseFloat(fields[1], 64)
		}
	}
	if totalKB <= 0 {
		return "-"
	}
	used := totalKB - availableKB
	percent := (used / totalKB) * 100
	return fmt.Sprintf("%.1f%% (%.1fG/%.1fG)", percent, used/1024/1024, totalKB/1024/1024)
}

func detectDiskUsage(path string) string {
	output, err := exec.Command("df", "-k", path).Output()
	if err != nil {
		return "-"
	}
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	if len(lines) < 2 {
		return "-"
	}
	fields := strings.Fields(lines[1])
	if len(fields) < 5 {
		return "-"
	}
	totalKB, err := strconv.ParseFloat(fields[1], 64)
	if err != nil {
		return "-"
	}
	usedKB, err := strconv.ParseFloat(fields[2], 64)
	if err != nil {
		return "-"
	}
	total := totalKB * 1024
	used := usedKB * 1024
	if total <= 0 {
		return "-"
	}
	percent := (used / total) * 100
	return fmt.Sprintf("%.1f%% (%.1fG/%.1fG)", percent, used/1024/1024/1024, total/1024/1024/1024)
}

func detectLoadAverage() string {
	content, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return "-"
	}
	fields := strings.Fields(string(content))
	if len(fields) < 3 {
		return "-"
	}
	return strings.Join(fields[:3], " ")
}

func detectUptime() string {
	content, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return "-"
	}
	fields := strings.Fields(string(content))
	if len(fields) == 0 {
		return "-"
	}
	seconds, err := strconv.ParseFloat(fields[0], 64)
	if err != nil {
		return "-"
	}
	duration := time.Duration(seconds) * time.Second
	hours := int(duration.Hours())
	minutes := int(duration.Minutes()) % 60
	if hours >= 24 {
		return fmt.Sprintf("%dd %dh", hours/24, hours%24)
	}
	return fmt.Sprintf("%dh %dm", hours, minutes)
}
