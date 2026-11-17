// Package cleanupcore 承载清理工具（归档/截断/回滚）的文件操作核心。
//
// 单独成包是为了让特权助手（cmd/privhelper）能以 root 执行这些操作，而不必把整个
// executor 包（连同它的命令构建面）拉进特权进程。daemon 侧只保留只读的 plan 与
// verify，共用本包的候选枚举与元数据读取。
//
// 为什么必须由助手执行（ADR-002 §4.2 的闭合）：清理会截断/删除目标目录里的文件。
// 只要目标目录对 daemon 的 uid 可写，被攻陷的 daemon 就能绕过工具直接做裸文件 I/O，
// 任何审批门都形同虚设。把目录改为 root 属主、执行下沉到助手后，这些目录只有助手能写、
// 且只在审批令牌校验通过时才写——审批才真正成为操作系统层面的强制点。
package cleanupcore

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/gmcrypto"
)

// Candidate 是一个被扫到的文件，连同它的分类结论。
//
// ModTime / AgeDays 是判据本身：没有 mtime，「清理过期日志」这个最基础的判断在数据层
// 就没有输入。Category / Decision / Rule / Reason 让每一条结论都可复核——审批人看到的
// 不是「可清理 12 个文件」，而是每个文件为什么可清理、为什么不能清理。
type Candidate struct {
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	ModTime  string `json:"mod_time"`
	AgeDays  int    `json:"age_days"`
	Category string `json:"category"`
	Decision string `json:"decision"`
	Rule     string `json:"rule"`
	Reason   string `json:"reason"`
}

// ScanResult 是一次目录扫描的完整结论：可清理 / 保留 / 拒绝三分，各自带明细与计数。
//
// 三分而不是二分，是因为「不清理」有两种性质完全不同的原因：PROTECTED 是策略红线
// （数据库文件、业务配置、审计证据，永远不碰），RETAINED 只是这次不满足条件
// （日志还在保留期内、文件刚写过）。审批人必须能一眼分开这两类。
type ScanResult struct {
	TargetDir      string
	ScannedCount   int
	Cleanable      []Candidate
	Retained       []Candidate
	Protected      []Candidate
	CleanableCount int
	RetainedCount  int
	ProtectedCount int
	CleanableBytes int64
	// Truncated 表示扫描因为上限提前结束，结论只覆盖了目录的一部分。
	// 必须如实报出去：一个「只扫了一半」的清理计划被当成全量，会让人以为剩下的都不可清理。
	Truncated bool
	// Categories 是分类直方图，用于给出「临时缓存 3 / 过期日志 5 / 关键保护 2」这种概览。
	Categories map[string]int
}

// maxScanEntries 是单次扫描检视的文件数硬上限。
// plan 是只读工具但要在 30 秒超时内返回，目录里塞了几十万个文件时必须能停下来。
const maxScanEntries = 2000

type BackupFile struct {
	Path       string `json:"path"`
	BackupPath string `json:"backup_path"`
	Size       int64  `json:"size"`
	Mode       string `json:"mode"`
	ModTime    string `json:"mod_time"`
	SM3        string `json:"sm3"`
	UID        uint32 `json:"uid"`
	GID        uint32 `json:"gid"`
}

type Metadata struct {
	RollbackID string       `json:"rollback_id"`
	TargetDir  string       `json:"target_dir"`
	Mode       string       `json:"mode"`
	BackupPath string       `json:"backup_path"`
	Files      []BackupFile `json:"files"`
	CreatedAt  string       `json:"created_at"`
}

// ResolveTarget 校验并归一化目标目录，仅接受白名单内的目录。
//
// 精确匹配而非前缀匹配：`/tmp/aiops-demo-logs-evil` 会被拒。白名单来自 root 持有的
// 配置文件（见 config.go），配置读不到时回退到内置的两个演示目录——任何情况下
// 都不会退化成「允许任意目录」。
func ResolveTarget(raw string) (string, error) {
	target := strings.TrimSpace(raw)
	if target == "" {
		if len(AllowedDirs) == 0 {
			return "", fmt.Errorf("cleanup allowlist is empty")
		}
		target = AllowedDirs[len(AllowedDirs)-1]
	}
	cleaned := filepath.ToSlash(filepath.Clean(target))
	for _, allowed := range AllowedDirs {
		if cleaned != allowed {
			continue
		}
		// 目标本身是符号链接时拒绝：白名单比对的是字符串，若 /tmp/aiops-demo-logs
		// 被换成指向 /etc 的软链，字符串比对会通过而实际操作落在 /etc 上。
		// 这里用 Lstat 而不是 EvalSymlinks——只判定末段是不是链接，不解析整条路径，
		// 因为在 macOS 这类 /tmp 本身即软链的系统上解析整条路径会误伤。
		if info, err := os.Lstat(cleaned); err == nil && info.Mode()&os.ModeSymlink != 0 {
			return "", fmt.Errorf("cleanup target %s is a symlink and is refused", cleaned)
		}
		return cleaned, nil
	}
	return "", fmt.Errorf("cleanup target %s is not in the cleanup allowlist", raw)
}

// Execute 归档（可选截断）目标目录下的候选文件，并写出回滚元数据。
//
// storeRoot 是回滚包的存储根，由调用方（助手）显式给出——助手用 root-only 的目录，
// 使备份不可被非特权进程篡改。返回的 JSON 只含核心字段；rollback_plan 之类的展示性
// 装饰由更上层按需附加，本包不掺入。
func Execute(targetDir, mode string, maxFiles int, storeRoot string, nowUTC time.Time) (string, error) {
	resolved, err := ResolveTarget(targetDir)
	if err != nil {
		return "", err
	}
	if mode == "" {
		mode = "archive_then_truncate"
	}
	if mode != "archive" && mode != "archive_then_truncate" {
		return "", fmt.Errorf("cleanup mode must be archive or archive_then_truncate")
	}
	// 分类在 root 侧重跑一遍，execute 只动 CLEANABLE 的文件。
	// daemon 侧 plan 的结论在这里不作数——助手不信任调用方递过来的任何判断，
	// 否则被攻陷的 daemon 只要伪造一份「什么都可清理」的 plan 就能绕过保护名单。
	scan, err := ScanDir(resolved, maxFiles, nowUTC, ActivePolicy)
	if err != nil {
		return "", err
	}
	candidates := scan.Cleanable
	if len(candidates) == 0 {
		// NOOP 也要把「为什么什么都没清」说清楚：目录空、还是全被保护名单挡下了。
		// 只回一个 NOOP 会让运维以为工具坏了。
		return marshalResult(map[string]any{
			"tool":             "kylin.cleanup.execute",
			"status":           "NOOP",
			"target_dir":       resolved,
			"mode":             mode,
			"files":            []Candidate{},
			"scanned_count":    scan.ScannedCount,
			"skipped_count":    scan.ProtectedCount + scan.RetainedCount,
			"protected_count":  scan.ProtectedCount,
			"retained_count":   scan.RetainedCount,
			"skipped":          skippedList(scan),
			"category_summary": scan.Categories,
		})
	}

	rollbackID := newRollbackID(nowUTC)
	backupRoot := filepath.Join(storeRoot, rollbackID)
	filesRoot := filepath.Join(backupRoot, "files")
	// backupRoot 0755：daemon（非 root）要能穿越它读到 rollback.json 做独立核验。
	// files/ 0700 + 备份文件 0600：备份内容仍 root-only，不可被非特权进程读取或篡改。
	if err := os.MkdirAll(backupRoot, 0o755); err != nil {
		return "", err
	}
	if err := os.MkdirAll(filesRoot, 0o700); err != nil {
		return "", err
	}
	// MkdirAll 受 umask 影响，显式收紧一次，确保 files/ 确为 0700。
	if err := os.Chmod(filesRoot, 0o700); err != nil {
		return "", err
	}
	files := make([]BackupFile, 0, len(candidates))
	totalBytes := int64(0)
	for _, candidate := range candidates {
		rel, err := filepath.Rel(resolved, candidate.Path)
		if err != nil || strings.HasPrefix(rel, "..") {
			return "", fmt.Errorf("cleanup candidate is outside target dir")
		}
		info, err := os.Stat(candidate.Path)
		if err != nil {
			return "", err
		}
		meta, err := fileMetadata(candidate.Path, info)
		if err != nil {
			return "", err
		}
		backupPath := filepath.Join(filesRoot, rel)
		if err := os.MkdirAll(filepath.Dir(backupPath), 0o700); err != nil {
			return "", err
		}
		if err := copyFile(candidate.Path, backupPath); err != nil {
			return "", err
		}
		if mode == "archive_then_truncate" {
			if err := os.Truncate(candidate.Path, 0); err != nil {
				return "", err
			}
		}
		meta.BackupPath = backupPath
		files = append(files, meta)
		totalBytes += candidate.Size
	}
	metadata := Metadata{
		RollbackID: rollbackID,
		TargetDir:  resolved,
		Mode:       mode,
		BackupPath: backupRoot,
		Files:      files,
		CreatedAt:  nowUTC.Format(time.RFC3339),
	}
	if err := writeMetadata(backupRoot, metadata); err != nil {
		return "", err
	}
	return marshalResult(map[string]any{
		"tool":             "kylin.cleanup.execute",
		"status":           "EXECUTED",
		"rollback_id":      rollbackID,
		"backup_path":      backupRoot,
		"target_dir":       resolved,
		"mode":             mode,
		"files":            files,
		"freed_bytes":      totalBytes,
		"bytes_archived":   totalBytes,
		"scanned_count":    scan.ScannedCount,
		"skipped_count":    scan.ProtectedCount + scan.RetainedCount,
		"protected_count":  scan.ProtectedCount,
		"retained_count":   scan.RetainedCount,
		"skipped":          skippedList(scan),
		"category_summary": scan.Categories,
	})
}

// skippedList 把「拒绝清理」与「本次保留」两类合成一份明细。
// 拒绝的排在前面：那才是审批人最需要先看到的一类（数据库文件、业务配置、审计证据）。
func skippedList(scan ScanResult) []Candidate {
	skipped := make([]Candidate, 0, len(scan.Protected)+len(scan.Retained))
	skipped = append(skipped, scan.Protected...)
	skipped = append(skipped, scan.Retained...)
	return skipped
}

// Rollback 从回滚包恢复文件，并逐个用 SM3 校验恢复结果。
func Rollback(rollbackID, storeRoot string, nowUTC time.Time) (string, error) {
	if err := validateRollbackID(rollbackID); err != nil {
		return "", err
	}
	backupRoot := filepath.Join(storeRoot, rollbackID)
	metadata, err := ReadMetadata(storeRoot, rollbackID)
	if err != nil {
		return "", err
	}
	_ = backupRoot
	restoredBytes := int64(0)
	for _, item := range metadata.Files {
		if err := os.MkdirAll(filepath.Dir(item.Path), 0o700); err != nil {
			return failedRollback(metadata, rollbackID, item.Path, "RESTORE_PREPARE_FAILED", err.Error(), restoredBytes, nowUTC)
		}
		if err := copyFile(item.BackupPath, item.Path); err != nil {
			return failedRollback(metadata, rollbackID, item.Path, "RESTORE_COPY_FAILED", err.Error(), restoredBytes, nowUTC)
		}
		if err := restoreFileMetadata(item); err != nil {
			return failedRollback(metadata, rollbackID, item.Path, "RESTORE_METADATA_FAILED", err.Error(), restoredBytes, nowUTC)
		}
		if item.SM3 != "" {
			restoredHash, err := FileSM3(item.Path)
			if err != nil {
				return failedRollback(metadata, rollbackID, item.Path, "RESTORE_VERIFY_FAILED", err.Error(), restoredBytes, nowUTC)
			}
			if restoredHash != item.SM3 {
				return failedRollback(metadata, rollbackID, item.Path, "RESTORE_VERIFY_FAILED", "restored file hash mismatch", restoredBytes, nowUTC)
			}
		}
		// 助手以 root 运行，恢复原始属主。
		if (item.UID != 0 || item.GID != 0) && os.Geteuid() == 0 {
			_ = os.Chown(item.Path, int(item.UID), int(item.GID))
		}
		restoredBytes += item.Size
	}
	return marshalResult(map[string]any{
		"tool":           "kylin.cleanup.rollback",
		"status":         "ROLLED_BACK",
		"rollback_id":    rollbackID,
		"target_dir":     metadata.TargetDir,
		"files":          metadata.Files,
		"bytes_restored": restoredBytes,
	})
}

func failedRollback(metadata Metadata, rollbackID, failedPath, failureType, message string, restoredBytes int64, nowUTC time.Time) (string, error) {
	return marshalResult(map[string]any{
		"tool":            "kylin.cleanup.rollback",
		"status":          "ROLLBACK_FAILED",
		"rollback_id":     rollbackID,
		"target_dir":      metadata.TargetDir,
		"failed_path":     failedPath,
		"failure_type":    failureType,
		"failure_message": message,
		"bytes_restored":  restoredBytes,
	})
}

func validateRollbackID(rollbackID string) error {
	id := strings.TrimSpace(rollbackID)
	if id == "" || !strings.HasPrefix(id, "rollback-") || strings.Contains(id, "/") || strings.Contains(id, "..") {
		return fmt.Errorf("cleanup rollback requires a valid rollbackId")
	}
	return nil
}

// ScanDir 枚举目标目录下的常规非空文件并逐个分类（供 plan 与 execute 共用）。
//
// 关键点：**plan 与 execute 跑的是同一段判据**。若只在 plan 里做分类、execute 仍然
// 无差别截断，那分类就只是给人看的装饰——被攻陷的 daemon 直接构造一次 execute 就能
// 绕过它。分类必须落在 root 侧真正动文件的那条路径上。
//
// maxFiles 限制「本次最多处理多少个可清理文件」（沿用原语义，也就是 execute 最多动几个
// 文件）；保留/拒绝清单同样按 maxFiles 截断展示，但计数覆盖全部扫到的文件。
func ScanDir(targetDir string, maxFiles int, now time.Time, policy Policy) (ScanResult, error) {
	result := ScanResult{TargetDir: targetDir, Categories: map[string]int{}}
	result.Cleanable = []Candidate{}
	result.Retained = []Candidate{}
	result.Protected = []Candidate{}

	info, err := os.Stat(targetDir)
	if err != nil {
		if os.IsNotExist(err) {
			return result, nil
		}
		return result, err
	}
	if !info.IsDir() {
		return result, fmt.Errorf("cleanup target is not a directory")
	}
	if maxFiles <= 0 {
		maxFiles = 20
	}

	err = filepath.WalkDir(targetDir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if result.ScannedCount >= maxScanEntries || result.CleanableCount >= maxFiles {
			result.Truncated = true
			return filepath.SkipAll
		}
		if entry.IsDir() {
			return nil
		}
		fileInfo, err := entry.Info()
		if err != nil {
			return err
		}
		// WalkDir 用 Lstat，符号链接的 Mode 不是 Regular，因此软链文件在这里就被丢掉：
		// 目标目录里放一条指向 /etc/shadow 的软链，也不会被归档或截断。
		if !fileInfo.Mode().IsRegular() || fileInfo.Size() <= 0 {
			return nil
		}
		rel, err := filepath.Rel(targetDir, path)
		if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return fmt.Errorf("cleanup candidate %s escapes the target dir", path)
		}
		result.ScannedCount++

		modTime := fileInfo.ModTime()
		verdict := Classify(rel, modTime, now, policy)
		candidate := Candidate{
			Path:     path,
			Size:     fileInfo.Size(),
			ModTime:  modTime.UTC().Format(time.RFC3339),
			AgeDays:  AgeDays(modTime, now),
			Category: verdict.Category,
			Decision: verdict.Decision,
			Rule:     verdict.Rule,
			Reason:   verdict.Reason,
		}
		result.Categories[verdict.Category]++
		switch verdict.Decision {
		case DecisionCleanable:
			result.CleanableCount++
			result.CleanableBytes += candidate.Size
			result.Cleanable = append(result.Cleanable, candidate)
		case DecisionProtected:
			result.ProtectedCount++
			if len(result.Protected) < maxFiles {
				result.Protected = append(result.Protected, candidate)
			}
		default:
			result.RetainedCount++
			if len(result.Retained) < maxFiles {
				result.Retained = append(result.Retained, candidate)
			}
		}
		return nil
	})
	if err != nil {
		return result, err
	}
	return result, nil
}

// ReadMetadata 读取回滚元数据。助手写出的 rollback.json 保持 0644 可检视
// （备份文件本身则 root-only），便于运维侧核对回滚包内容。
func ReadMetadata(storeRoot, rollbackID string) (Metadata, error) {
	if err := validateRollbackID(rollbackID); err != nil {
		return Metadata{}, err
	}
	data, err := os.ReadFile(filepath.Join(storeRoot, rollbackID, "rollback.json"))
	if err != nil {
		return Metadata{}, err
	}
	var metadata Metadata
	if err := json.Unmarshal(data, &metadata); err != nil {
		return Metadata{}, err
	}
	return metadata, nil
}

func copyFile(source, target string) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	output, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer output.Close()
	_, err = io.Copy(output, input)
	return err
}

func fileMetadata(path string, info os.FileInfo) (BackupFile, error) {
	hash, err := FileSM3(path)
	if err != nil {
		return BackupFile{}, err
	}
	result := BackupFile{
		Path:    path,
		Size:    info.Size(),
		Mode:    fmt.Sprintf("%04o", info.Mode().Perm()),
		ModTime: info.ModTime().UTC().Format(time.RFC3339),
		SM3:     hash,
	}
	if stat, ok := info.Sys().(*syscall.Stat_t); ok {
		result.UID = stat.Uid
		result.GID = stat.Gid
	}
	return result, nil
}

func restoreFileMetadata(item BackupFile) error {
	if item.Mode != "" {
		mode, err := strconv.ParseUint(item.Mode, 8, 32)
		if err == nil {
			if err := os.Chmod(item.Path, os.FileMode(mode)); err != nil {
				return err
			}
		}
	}
	if item.ModTime != "" {
		modTime, err := time.Parse(time.RFC3339, item.ModTime)
		if err == nil {
			if err := os.Chtimes(item.Path, modTime, modTime); err != nil {
				return err
			}
		}
	}
	return nil
}

// FileSM3 计算文件内容的 SM3 摘要。
func FileSM3(path string) (string, error) {
	input, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer input.Close()
	digest := gmcrypto.NewSM3()
	if _, err := io.Copy(digest, input); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

// writeMetadata 写出回滚元数据。0644：备份文件本身是 0600（root-only），
// 元数据则允许运维侧直接检视回滚包内容。
func writeMetadata(backupRoot string, metadata Metadata) error {
	data, err := json.MarshalIndent(metadata, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(backupRoot, "rollback.json"), data, 0o644)
}

func newRollbackID(nowUTC time.Time) string {
	random := make([]byte, 4)
	_, _ = rand.Read(random)
	return "rollback-" + nowUTC.Format("20060102150405") + "-" + hex.EncodeToString(random)
}

func marshalResult(value any) (string, error) {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}
