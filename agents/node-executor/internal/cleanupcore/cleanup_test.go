package cleanupcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func withAllowedDir(t *testing.T, dir string) {
	t.Helper()
	old := AllowedDirs
	AllowedDirs = []string{filepath.Clean(dir)}
	t.Cleanup(func() { AllowedDirs = old })
}

// withDefaultPolicy 把包级阈值复位成默认值，避免测试之间互相污染。
func withDefaultPolicy(t *testing.T) {
	t.Helper()
	old := ActivePolicy
	ActivePolicy = DefaultPolicy()
	t.Cleanup(func() { ActivePolicy = old })
}

// writeAged 写一个文件并把它的 mtime 拨到 ageDays 天前，让「过期」判据可被确定性地测到。
func writeAged(t *testing.T, dir, name, content string, ageDays int) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir %s: %v", filepath.Dir(path), err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
	when := time.Now().Add(-time.Duration(ageDays) * 24 * time.Hour)
	if err := os.Chtimes(path, when, when); err != nil {
		t.Fatalf("chtimes %s: %v", path, err)
	}
	return path
}

func TestExecuteArchivesTruncatesAndRollbackRestores(t *testing.T) {
	targetDir := t.TempDir()
	store := t.TempDir()
	withAllowedDir(t, targetDir)
	withDefaultPolicy(t)

	// 已轮转的历史日志：写入方已切到新文件，归档 + 截断是安全的。
	filePath := writeAged(t, targetDir, "app.log.1", "demo log content", 1)
	now := time.Now().UTC()

	out, err := Execute(targetDir, "archive_then_truncate", 20, store, now)
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	var executed map[string]any
	if err := json.Unmarshal([]byte(out), &executed); err != nil {
		t.Fatalf("parse execute output: %v", err)
	}
	rollbackID, _ := executed["rollback_id"].(string)
	if rollbackID == "" {
		t.Fatalf("expected rollback_id: %s", out)
	}
	if executed["status"] != "EXECUTED" {
		t.Fatalf("expected EXECUTED, got %v", executed["status"])
	}
	if data, _ := os.ReadFile(filePath); len(data) != 0 {
		t.Fatalf("expected file truncated, got %d bytes", len(data))
	}

	// 权限分层：daemon（非 root）要能穿越 backupRoot 读 rollback.json 做独立核验，
	// 但备份内容（files/）必须 root-only，不可被非特权进程读取或篡改。
	backupRoot := filepath.Join(store, rollbackID)
	if perm := statPerm(t, backupRoot); perm != 0o755 {
		t.Fatalf("backupRoot 应 0755 可穿越，实际 %#o", perm)
	}
	if perm := statPerm(t, filepath.Join(backupRoot, "rollback.json")); perm != 0o644 {
		t.Fatalf("元数据应 0644 可读，实际 %#o", perm)
	}
	if perm := statPerm(t, filepath.Join(backupRoot, "files")); perm != 0o700 {
		t.Fatalf("files/ 应 0700 root-only，实际 %#o", perm)
	}

	rbOut, err := Rollback(rollbackID, store, now)
	if err != nil {
		t.Fatalf("rollback: %v", err)
	}
	var rolledBack map[string]any
	if err := json.Unmarshal([]byte(rbOut), &rolledBack); err != nil {
		t.Fatalf("parse rollback output: %v", err)
	}
	if rolledBack["status"] != "ROLLED_BACK" {
		t.Fatalf("expected ROLLED_BACK, got %v", rolledBack["status"])
	}
	if data, _ := os.ReadFile(filePath); string(data) != "demo log content" {
		t.Fatalf("expected restored content, got %q", string(data))
	}
}

// 保护名单是这个场景的核心承诺：「不会误删数据库日志、业务配置文件、关键数据」。
// 这一条必须在真正动文件的 Execute 路径上成立，而不只是在只读的 plan 里成立——
// 否则被攻陷的 daemon 只要跳过 plan 直接调 execute 就能绕过它。
func TestExecuteNeverTouchesProtectedFiles(t *testing.T) {
	targetDir := t.TempDir()
	store := t.TempDir()
	withAllowedDir(t, targetDir)
	withDefaultPolicy(t)

	protected := map[string]string{
		"business.db":               "database payload",
		"nginx.conf":                "server { }",
		"session.sqlite":            "sqlite payload",
		"mysql-slow.log":            "slow query log",   // 扩展名是 .log，但它是数据库日志
		"postgresql-2026-08-01.log": "pg log",           // 轮转形态的数据库日志
		"audit.log.1":               "audit evidence",   // 轮转形态的审计证据
		"tls/server.pem":            "-----BEGIN-----",  // 受保护目录 + 凭据扩展名
		"data/payload.bin":          "critical dataset", // 受保护目录
	}
	for name, content := range protected {
		writeAged(t, targetDir, name, content, 400) // 故意做得非常旧：年龄不能压过保护
	}
	cleanable := writeAged(t, targetDir, "app.log.3", "rotated app log", 30)

	out, err := Execute(targetDir, "archive_then_truncate", 50, store, time.Now().UTC())
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	var executed map[string]any
	if err := json.Unmarshal([]byte(out), &executed); err != nil {
		t.Fatalf("parse execute output: %v", err)
	}
	if executed["status"] != "EXECUTED" {
		t.Fatalf("期望 EXECUTED（有一个可清理文件），实际 %v：%s", executed["status"], out)
	}
	for name, content := range protected {
		data, err := os.ReadFile(filepath.Join(targetDir, name))
		if err != nil {
			t.Fatalf("受保护文件 %s 不应消失：%v", name, err)
		}
		if string(data) != content {
			t.Fatalf("受保护文件 %s 被改动了：%q", name, string(data))
		}
	}
	if data, _ := os.ReadFile(cleanable); len(data) != 0 {
		t.Fatalf("可清理文件应被截断，实际 %d 字节", len(data))
	}
	if got := int(executed["protected_count"].(float64)); got != len(protected) {
		t.Fatalf("期望 %d 个受保护文件被记录，实际 %d：%s", len(protected), got, out)
	}
	// 结果里必须说明「为什么没清」，否则运维只能看到一个数字。
	skipped, _ := executed["skipped"].([]any)
	if len(skipped) == 0 {
		t.Fatalf("skipped 明细不应为空：%s", out)
	}
	first, _ := skipped[0].(map[string]any)
	if reason, _ := first["reason"].(string); reason == "" {
		t.Fatalf("每个被跳过的文件都应带中文理由：%s", out)
	}
}

// 全是受保护文件时必须 NOOP，且要说清楚原因——只回一个 NOOP 会让人以为工具坏了。
func TestExecuteReturnsNoopWithReasonsWhenEverythingProtected(t *testing.T) {
	targetDir := t.TempDir()
	store := t.TempDir()
	withAllowedDir(t, targetDir)
	withDefaultPolicy(t)

	writeAged(t, targetDir, "app.conf", "config", 500)
	writeAged(t, targetDir, "core.db", "database", 500)

	out, err := Execute(targetDir, "archive_then_truncate", 20, store, time.Now().UTC())
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	var executed map[string]any
	_ = json.Unmarshal([]byte(out), &executed)
	if executed["status"] != "NOOP" {
		t.Fatalf("期望 NOOP，实际 %v", executed["status"])
	}
	if got := int(executed["protected_count"].(float64)); got != 2 {
		t.Fatalf("期望 protected_count=2，实际 %d：%s", got, out)
	}
	if skipped, _ := executed["skipped"].([]any); len(skipped) != 2 {
		t.Fatalf("NOOP 也必须带上被跳过的明细：%s", out)
	}
}

// 目标目录里的软链不得被归档或截断：Walk 用 Lstat，软链不是常规文件，应当被丢弃。
func TestScanDirIgnoresSymlinks(t *testing.T) {
	targetDir := t.TempDir()
	outside := filepath.Join(t.TempDir(), "outside.log.1")
	if err := os.WriteFile(outside, []byte("outside payload"), 0o600); err != nil {
		t.Fatalf("write outside: %v", err)
	}
	if err := os.Symlink(outside, filepath.Join(targetDir, "linked.log.1")); err != nil {
		t.Skipf("当前环境不支持创建软链：%v", err)
	}
	writeAged(t, targetDir, "real.log.1", "rotated", 1)

	scan, err := ScanDir(targetDir, 20, time.Now().UTC(), DefaultPolicy())
	if err != nil {
		t.Fatalf("scan: %v", err)
	}
	for _, item := range scan.Cleanable {
		if filepath.Base(item.Path) == "linked.log.1" {
			t.Fatalf("软链不应进入候选：%+v", item)
		}
	}
	if scan.CleanableCount != 1 {
		t.Fatalf("只应有 real.log.1 一个候选，实际 %d", scan.CleanableCount)
	}
}

func TestRollbackFailsWhenBackupMissing(t *testing.T) {
	targetDir := t.TempDir()
	store := t.TempDir()
	withAllowedDir(t, targetDir)
	withDefaultPolicy(t)

	writeAged(t, targetDir, "app.log.1", "demo log content", 1)
	now := time.Now().UTC()

	out, err := Execute(targetDir, "archive_then_truncate", 20, store, now)
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	var executed map[string]any
	_ = json.Unmarshal([]byte(out), &executed)
	rollbackID, ok := executed["rollback_id"].(string)
	if !ok || rollbackID == "" {
		t.Fatalf("expected rollback_id: %s", out)
	}

	// 删掉备份文件，模拟备份丢失。
	meta, err := ReadMetadata(store, rollbackID)
	if err != nil {
		t.Fatalf("read metadata: %v", err)
	}
	if err := os.Remove(meta.Files[0].BackupPath); err != nil {
		t.Fatalf("remove backup: %v", err)
	}

	rbOut, err := Rollback(rollbackID, store, now)
	if err != nil {
		t.Fatalf("rollback should report structured failure, not error: %v", err)
	}
	var rolledBack map[string]any
	_ = json.Unmarshal([]byte(rbOut), &rolledBack)
	if rolledBack["status"] != "ROLLBACK_FAILED" {
		t.Fatalf("expected ROLLBACK_FAILED, got %v", rolledBack["status"])
	}
}

func TestExecuteRejectsTargetOutsideAllowlist(t *testing.T) {
	store := t.TempDir()
	withAllowedDir(t, t.TempDir()) // 允许清单里放一个别的目录
	now := time.Unix(1765000000, 0).UTC()

	if _, err := Execute("/etc", "archive", 20, store, now); err == nil {
		t.Fatal("清单外目录必须被拒绝")
	}
}

func TestResolveTargetRejectsTraversal(t *testing.T) {
	withAllowedDir(t, "/var/log/aiops-demo")
	bad := []string{
		"/var/log/aiops-demo/../../etc",
		"/var/log/aiops-demo/../aiops-demo/../../etc/passwd",
		"/etc/passwd",
		"relative/path",
		"../../etc",
		"/var/log/aiops-demo-evil",   // 前缀相近但不相等：精确匹配必须拒绝
		"/var/log/aiops-demo/nested", // 子目录也不在清单里
	}
	for _, target := range bad {
		if _, err := ResolveTarget(target); err == nil {
			t.Fatalf("%s 应被拒绝", target)
		}
	}
}

// 目标目录本身是软链时必须拒绝：白名单比对的是字符串，软链会让实际操作落在别处。
func TestResolveTargetRejectsSymlinkedTarget(t *testing.T) {
	root := t.TempDir()
	realDir := filepath.Join(root, "real")
	linkDir := filepath.Join(root, "link")
	if err := os.Mkdir(realDir, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.Symlink(realDir, linkDir); err != nil {
		t.Skipf("当前环境不支持创建软链：%v", err)
	}
	withAllowedDir(t, linkDir)

	if _, err := ResolveTarget(linkDir); err == nil {
		t.Fatal("目标目录是软链时必须被拒绝")
	}
}

func statPerm(t *testing.T, path string) os.FileMode {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat %s: %v", path, err)
	}
	return info.Mode().Perm()
}
