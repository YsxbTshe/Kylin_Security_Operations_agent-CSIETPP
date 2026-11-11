package executor

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

func (s Service) kysecStatus() (string, error) {
	if runtime.GOOS != "linux" {
		return nativeGuardJSON("kylin.security.kysec_status", false, "unsupported", "KYSEC is only available on supported Kylin Linux hosts", map[string]string{})
	}
	evidence := map[string]string{}
	if content, ok := readFirstExisting(evidence, "/sys/kernel/security/kysec/status", "/proc/kysec/status", "/proc/sys/kernel/kysec"); ok {
		status := nativeStatus(content, "enabled", "active", "on", "1")
		return nativeGuardJSON("kylin.security.kysec_status", true, status, "KYSEC status file detected", evidence)
	}
	if content, ok := readFirstExisting(evidence, "/sys/kernel/security/lsm"); ok && containsAnyText(content, "kysec", "kylin", "ksaf") {
		return nativeGuardJSON("kylin.security.kysec_status", true, "active", "KYSEC is present in Linux security module list", evidence)
	}
	return nativeGuardJSON("kylin.security.kysec_status", false, "unsupported", "KYSEC evidence was not found on this host", evidence)
}

func (s Service) selinuxStatus() (string, error) {
	if runtime.GOOS != "linux" {
		return nativeGuardJSON("kylin.security.selinux_status", false, "unsupported", "SELinux is only available on Linux hosts", map[string]string{})
	}
	evidence := map[string]string{}
	if content, ok := readFirstExisting(evidence, "/sys/fs/selinux/enforce"); ok {
		status := "permissive"
		if strings.TrimSpace(content) == "1" {
			status = "enforcing"
		}
		return nativeGuardJSON("kylin.security.selinux_status", true, status, "SELinux enforce file detected", evidence)
	}
	if output, err := commandOutput("getenforce"); err == nil {
		evidence["getenforce"] = output
		status := strings.ToLower(strings.TrimSpace(output))
		if status == "" {
			status = "unknown"
		}
		if status == "disabled" {
			// SELinux is disabled; on Kylin hosts KYSEC (ksaf) is the native control,
			// so a disabled SELinux is not in use here and must not mark the host critical.
			return nativeGuardJSON("kylin.security.selinux_status", false, "disabled", "SELinux is disabled; KYSEC is the active control on this host", evidence)
		}
		return nativeGuardJSON("kylin.security.selinux_status", true, status, "getenforce command detected", evidence)
	}
	return nativeGuardJSON("kylin.security.selinux_status", false, "unsupported", "SELinux evidence was not found on this host", evidence)
}

func (s Service) auditdStatus() (string, error) {
	if runtime.GOOS != "linux" {
		return nativeGuardJSON("kylin.security.auditd_status", false, "unsupported", "auditd is only available on Linux hosts", map[string]string{})
	}
	evidence := map[string]string{}
	if output, err := commandOutput("systemctl", "is-active", "auditd"); err == nil {
		evidence["systemctl_is_active"] = output
		status := strings.ToLower(strings.TrimSpace(output))
		if status == "" {
			status = "unknown"
		}
		return nativeGuardJSON("kylin.security.auditd_status", true, status, "auditd systemd unit detected", evidence)
	} else {
		evidence["systemctl_is_active_error"] = err.Error()
	}
	if fileExists("/usr/sbin/auditctl") || fileExists("/sbin/auditctl") {
		return nativeGuardJSON("kylin.security.auditd_status", true, "unknown", "auditctl exists but auditd service status is unavailable", evidence)
	}
	return nativeGuardJSON("kylin.security.auditd_status", false, "unsupported", "auditd evidence was not found on this host", evidence)
}

func (s Service) securitySwitchStatus() (string, error) {
	if runtime.GOOS != "linux" {
		return nativeGuardJSON("kylin.security.security_switch_status", false, "unsupported", "security-switch is only available on supported Kylin Linux hosts", map[string]string{})
	}
	evidence := map[string]string{}
	if content, ok := readFirstExisting(evidence, "/etc/security-switch.conf", "/etc/kylin-security-switch.conf", "/etc/default/security-switch"); ok {
		status := "unknown"
		normalized := strings.ToLower(content)
		switch {
		case containsAnyText(normalized, "strict"):
			status = "strict"
		case containsAnyText(normalized, "standard", "on", "enable"):
			status = "standard"
		case containsAnyText(normalized, "off", "disable", "false", "0"):
			status = "off"
		}
		return nativeGuardJSON("kylin.security.security_switch_status", true, status, "security-switch config file detected", evidence)
	}
	// 麒麟 V11 只认 --get / --set；旧的 `status` 子命令在该版本不被接受。
	// 先试 --get，再回退 status，避免把语法错误当成「本机没有 security-switch」。
	if output, err := commandOutput("security-switch", "--get"); err == nil {
		evidence["security-switch_get"] = output
		status := interpretSecuritySwitchOutput(output)
		return nativeGuardJSON("kylin.security.security_switch_status", true, status, "security-switch --get detected", evidence)
	} else if err != nil {
		evidence["security-switch_get_error"] = err.Error()
	}
	if output, err := commandOutput("security-switch", "status"); err == nil {
		evidence["security-switch_status"] = output
		status := interpretSecuritySwitchOutput(output)
		return nativeGuardJSON("kylin.security.security_switch_status", true, status, "security-switch command detected", evidence)
	} else if err != nil {
		evidence["security-switch_status_error"] = err.Error()
	}
	return nativeGuardJSON("kylin.security.security_switch_status", false, "unsupported", "security-switch evidence was not found on this host", evidence)
}

func interpretSecuritySwitchOutput(output string) string {
	normalized := strings.ToLower(strings.TrimSpace(output))
	if normalized == "" {
		return "unknown"
	}
	switch {
	case containsAnyText(normalized, "strict"):
		return "strict"
	case containsAnyText(normalized, "standard", "on", "enable"):
		return "standard"
	case containsAnyText(normalized, "off", "disable", "false"):
		return "off"
	default:
		return strings.Fields(normalized)[0]
	}
}

func nativeGuardJSON(tool string, supported bool, status string, message string, evidence map[string]string) (string, error) {
	payload := map[string]any{
		"tool":      tool,
		"supported": supported,
		"status":    status,
		"message":   message,
		"evidence":  evidence,
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func readFirstExisting(evidence map[string]string, paths ...string) (string, bool) {
	for _, path := range paths {
		data, err := os.ReadFile(path)
		if err != nil {
			if !errors.Is(err, os.ErrNotExist) {
				evidence[path] = err.Error()
			}
			continue
		}
		content := strings.TrimSpace(string(data))
		evidence[path] = content
		return content, true
	}
	return "", false
}

func nativeStatus(content string, healthyValues ...string) string {
	normalized := strings.ToLower(strings.TrimSpace(content))
	for _, healthy := range healthyValues {
		if normalized == healthy || strings.Contains(normalized, healthy) {
			return "active"
		}
	}
	if containsAnyText(normalized, "disable", "disabled", "off", "0") {
		return "disabled"
	}
	return "unknown"
}

func containsAnyText(value string, needles ...string) bool {
	for _, needle := range needles {
		if strings.Contains(strings.ToLower(value), strings.ToLower(needle)) {
			return true
		}
	}
	return false
}

func commandOutput(name string, args ...string) (string, error) {
	if _, err := exec.LookPath(name); err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, name, args...).CombinedOutput()
	if ctx.Err() != nil {
		return "", ctx.Err()
	}
	return strings.TrimSpace(string(output)), err
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
