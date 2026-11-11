package executor

import (
	"encoding/json"
	"strings"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/cleanupcore"
)

// cleanupPlan 是只读的清理规划：枚举文件、逐个分类、估算可回收空间。
//
// 只有 plan 留在 daemon 本地——它不改动任何文件。真正的归档/截断（execute）与恢复
// （rollback）已下沉到特权助手执行（ADR-002 §4.2）：演示目录改为 root 属主后，daemon
// 的 uid 无法写它们，也就无法绕过审批直接截断；执行必须走持有密钥、会校验审批令牌的助手。
//
// plan 的分类结论**不会**被 execute 信任：助手在 root 侧用同一份规则重跑一遍
// （见 cleanupcore.Execute）。plan 的价值是让审批人在点「同意」之前看清将要动哪些文件、
// 哪些被保护名单挡下、为什么——而不是替 root 侧做决定。
func (s Service) cleanupPlan(arguments map[string]string) (string, error) {
	targetDir, err := cleanupcore.ResolveTarget(firstArg(arguments, "targetDir", "target_dir"))
	if err != nil {
		return "", err
	}
	maxFiles := normalizePositiveInt(firstArg(arguments, "maxFiles", "max_files"), 20, 100)
	policy := cleanupcore.ActivePolicy
	scan, err := cleanupcore.ScanDir(targetDir, maxFiles, time.Now().UTC(), policy)
	if err != nil {
		return "", err
	}
	return jsonResult(map[string]any{
		"tool":       "kylin.cleanup.plan",
		"target_dir": targetDir,
		"mode":       "archive_then_truncate",
		// candidate_count / reclaim_bytes 保持原义（本次将要动的文件与可回收字节），
		// 但现在它们只统计通过分类的可清理文件——被保护名单挡下的不再计入「可回收」，
		// 否则审批人看到的空间收益是虚的。
		"candidate_count":   scan.CleanableCount,
		"reclaim_bytes":     scan.CleanableBytes,
		"requires_approval": true,
		"candidates":        scan.Cleanable,
		"scanned_count":     scan.ScannedCount,
		"cleanable_count":   scan.CleanableCount,
		"protected_count":   scan.ProtectedCount,
		"retained_count":    scan.RetainedCount,
		"protected":         scan.Protected,
		"retained":          scan.Retained,
		"category_summary":  scan.Categories,
		"truncated":         scan.Truncated,
		"policy": map[string]any{
			"expired_log_days":        policy.ExpiredLogDays,
			"stale_file_days":         policy.StaleFileDays,
			"active_log_minutes":      int(policy.ActiveLogWindow / time.Minute),
			"allowed_dirs":            cleanupcore.AllowedDirs,
			"extra_protect":           policy.ExtraProtect,
			"protection_is_hardcoded": true,
		},
	})
}

func firstArg(arguments map[string]string, keys ...string) string {
	for _, key := range keys {
		if value := strings.TrimSpace(arguments[key]); value != "" {
			return value
		}
	}
	return ""
}

func jsonResult(value any) (string, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}
