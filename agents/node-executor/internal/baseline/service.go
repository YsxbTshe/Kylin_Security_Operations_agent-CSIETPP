package baseline

import (
	"os"
	"strings"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/pkg/model"
)

type Service struct{}

func NewService() Service {
	return Service{}
}

func (Service) RunChecks(nodeID string) []model.BaselineCheck {
	return []model.BaselineCheck{
		checkSSHRootLogin(nodeID),
		checkSudoPolicy(nodeID),
	}
}

func checkSSHRootLogin(nodeID string) model.BaselineCheck {
	content, err := os.ReadFile("/etc/ssh/sshd_config")
	if err != nil {
		return model.BaselineCheck{
			NodeID:  nodeID,
			Name:    "ssh-root-login",
			Status:  "UNKNOWN",
			Message: "sshd_config unavailable: " + err.Error(),
		}
	}

	for _, line := range strings.Split(string(content), "\n") {
		value := strings.TrimSpace(line)
		if value == "" || strings.HasPrefix(value, "#") {
			continue
		}
		fields := strings.Fields(value)
		if len(fields) >= 2 && strings.EqualFold(fields[0], "PermitRootLogin") {
			if strings.EqualFold(fields[1], "no") || strings.EqualFold(fields[1], "prohibit-password") {
				return model.BaselineCheck{
					NodeID:  nodeID,
					Name:    "ssh-root-login",
					Status:  "PASS",
					Message: "PermitRootLogin is restricted",
				}
			}
			return model.BaselineCheck{
				NodeID:  nodeID,
				Name:    "ssh-root-login",
				Status:  "WARN",
				Message: "PermitRootLogin allows direct root login",
			}
		}
	}

	return model.BaselineCheck{
		NodeID:  nodeID,
		Name:    "ssh-root-login",
		Status:  "UNKNOWN",
		Message: "PermitRootLogin is not explicitly configured",
	}
}

func checkSudoPolicy(nodeID string) model.BaselineCheck {
	content, err := os.ReadFile("/etc/sudoers")
	if err != nil {
		return model.BaselineCheck{
			NodeID:  nodeID,
			Name:    "sudo-policy",
			Status:  "UNKNOWN",
			Message: "sudoers unavailable: " + err.Error(),
		}
	}

	for _, line := range strings.Split(string(content), "\n") {
		value := strings.TrimSpace(line)
		if value == "" || strings.HasPrefix(value, "#") {
			continue
		}
		if strings.Contains(value, "NOPASSWD:") {
			return model.BaselineCheck{
				NodeID:  nodeID,
				Name:    "sudo-policy",
				Status:  "WARN",
				Message: "sudoers contains NOPASSWD rule",
			}
		}
	}

	return model.BaselineCheck{
		NodeID:  nodeID,
		Name:    "sudo-policy",
		Status:  "PASS",
		Message: "sudoers has no active NOPASSWD rule",
	}
}
