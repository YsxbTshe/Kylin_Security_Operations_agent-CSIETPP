package sandbox

import "encoding/json"

// SeccompProfile 生成容器使用的 seccomp profile JSON。
// 在默认 Docker/Podman profile 基础上额外禁止 mount、ptrace、clone with new namespace，
// 阻止沙箱逃逸的关键系统调用路径。
func SeccompProfile() string {
	profile := map[string]any{
		"defaultAction": "SCMP_ACT_ALLOW",
		"syscalls": []map[string]any{
			{
				"names": []string{
					"mount",
					"umount2",
					"umount",
					"pivot_root",
					"chroot",
				},
				"action": "SCMP_ACT_ERRNO",
			},
			{
				"names":  []string{"ptrace"},
				"action": "SCMP_ACT_ERRNO",
			},
			{
				"names":  []string{"clone3"},
				"action": "SCMP_ACT_ERRNO",
			},
			{
				"names":  []string{"clone"},
				"action": "SCMP_ACT_ERRNO",
				"args": []map[string]any{
					{
						"index": 0,
						"value": cloneNewNamespaceFlags(),
						"op":    "SCMP_CMP_MASKED_EQ",
						"mask":  cloneNewNamespaceFlags(),
					},
				},
			},
			{
				"names": []string{
					"unshare",
					"setns",
				},
				"action": "SCMP_ACT_ERRNO",
			},
			{
				"names": []string{
					"swapon",
					"swapoff",
				},
				"action": "SCMP_ACT_ERRNO",
			},
			{
				"names": []string{
					"init_module",
					"finit_module",
					"delete_module",
				},
				"action": "SCMP_ACT_ERRNO",
			},
			{
				"names": []string{
					"kexec_load",
					"kexec_file_load",
				},
				"action": "SCMP_ACT_ERRNO",
			},
		},
	}
	data, _ := json.MarshalIndent(profile, "", "  ")
	return string(data)
}

// cloneNewNamespaceFlags 是 CLONE_NEWNS | CLONE_NEWUTS | CLONE_NEWIPC |
// CLONE_NEWUSER | CLONE_NEWPID | CLONE_NEWNET | CLONE_NEWCGROUP 的位掩码。
// 用于 seccomp 的 MASKED_EQ 匹配：只要 clone 参数里带了任意 new namespace 标志就拒绝。
func cloneNewNamespaceFlags() uint64 {
	return 0x7C020000
}
