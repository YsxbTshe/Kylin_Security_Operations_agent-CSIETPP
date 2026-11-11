package privops

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/cleanupcore"
)

// 清理是写操作：合法令牌下助手以 root 执行归档/截断；伪造令牌必须在动任何文件前被拒。
func TestCleanupExecuteThroughHelper(t *testing.T) {
	targetDir := t.TempDir()
	store := t.TempDir()
	oldAllowed := cleanupcore.AllowedDirs
	cleanupcore.AllowedDirs = []string{filepath.Clean(targetDir)}
	t.Cleanup(func() { cleanupcore.AllowedDirs = oldAllowed })

	// 用已轮转的历史日志作为可清理样本：分类判据要求候选必须是「确定不再被写入」的文件。
	logFile := filepath.Join(targetDir, "app.log.1")
	if err := os.WriteFile(logFile, []byte("noise"), 0o600); err != nil {
		t.Fatal(err)
	}
	// 同目录里放一个数据库文件：即便审批合法，助手也必须绕开它。
	protectedFile := filepath.Join(targetDir, "business.db")
	if err := os.WriteFile(protectedFile, []byte("critical rows"), 0o600); err != nil {
		t.Fatal(err)
	}

	fixture := newApprovalFixture(t)
	server := NewServer(ServerOptions{
		Policy:      Policy{AllowedUnits: []string{"nginx"}, MaxOpsPerMinute: 10, RollbackStore: store},
		KeyResolver: fixture.resolver(),
		Now:         func() time.Time { return fixture.now },
		ReplayGuard: fixture.ledger(t, t.TempDir()),
	})

	args := `{"targetDir":"` + targetDir + `","mode":"archive_then_truncate"}`

	// 伪造令牌（含已退役的 HMAC 形态）：必须被拒，且文件不被动。
	forged := fixture.signedRequest("kylin.cleanup.execute", args)
	forged.ApprovalToken = "approval-token-v2-0000000000000000-" + strings.Repeat("0", 63) + "ff"
	if got := server.Handle(t.Context(), forged); got.Error == "" {
		t.Fatal("伪造令牌的清理必须被拒绝")
	}
	if data, _ := os.ReadFile(logFile); string(data) != "noise" {
		t.Fatal("校验失败时文件不应被截断")
	}

	// 合法 SM2 令牌：执行成功，文件被截断。
	valid := fixture.signedRequest("kylin.cleanup.execute", args)
	got := server.Handle(t.Context(), valid)
	if got.Error != "" {
		t.Fatalf("合法清理不应失败：%s", got.Error)
	}
	var result map[string]any
	if err := json.Unmarshal([]byte(got.Output), &result); err != nil {
		t.Fatalf("解析清理结果：%v", err)
	}
	if result["status"] != "EXECUTED" {
		t.Fatalf("期望 EXECUTED，实际 %v", result["status"])
	}
	if data, _ := os.ReadFile(logFile); len(data) != 0 {
		t.Fatalf("文件应被截断，实际 %d 字节", len(data))
	}
	// 保护名单在 root 侧生效：合法审批也不能让助手动数据库文件。
	// 这一条是「不会误删数据库日志、业务配置文件、关键数据」在特权边界上的落点。
	if data, _ := os.ReadFile(protectedFile); string(data) != "critical rows" {
		t.Fatalf("受保护文件不应被清理，实际 %q", string(data))
	}
	if count, _ := result["protected_count"].(float64); int(count) != 1 {
		t.Fatalf("助手应报告 1 个受保护文件，实际输出 %s", got.Output)
	}
}

// 回滚同样是写操作，经助手执行、验令牌。这里验证助手的 rollback 分支端到端恢复文件。
func TestCleanupRollbackThroughHelper(t *testing.T) {
	targetDir := t.TempDir()
	store := t.TempDir()
	oldAllowed := cleanupcore.AllowedDirs
	cleanupcore.AllowedDirs = []string{filepath.Clean(targetDir)}
	t.Cleanup(func() { cleanupcore.AllowedDirs = oldAllowed })

	logFile := filepath.Join(targetDir, "app.log.1")
	if err := os.WriteFile(logFile, []byte("original content"), 0o600); err != nil {
		t.Fatal(err)
	}
	fixture := newApprovalFixture(t)
	server := NewServer(ServerOptions{
		Policy:      Policy{AllowedUnits: []string{"nginx"}, MaxOpsPerMinute: 10, RollbackStore: store},
		KeyResolver: fixture.resolver(),
		Now:         func() time.Time { return fixture.now },
		ReplayGuard: fixture.ledger(t, t.TempDir()),
	})

	// 先执行清理，拿到 rollback_id。
	execArgs := `{"targetDir":"` + targetDir + `","mode":"archive_then_truncate"}`
	execGot := server.Handle(t.Context(), fixture.signedRequest("kylin.cleanup.execute", execArgs))
	if execGot.Error != "" {
		t.Fatalf("execute 失败：%s", execGot.Error)
	}
	var executed map[string]any
	_ = json.Unmarshal([]byte(execGot.Output), &executed)
	rollbackID, ok := executed["rollback_id"].(string)
	if !ok || rollbackID == "" {
		t.Fatalf("execute 未返回 rollback_id：%s", execGot.Output)
	}
	if data, _ := os.ReadFile(logFile); len(data) != 0 {
		t.Fatal("清理后文件应被截断")
	}

	// 伪造令牌的回滚必须被拒。
	forged := fixture.signedRequest("kylin.cleanup.rollback", `{"rollbackId":"`+rollbackID+`"}`)
	forged.ApprovalToken = approvalv2.TokenPrefix + "!!!not-base64"
	if got := server.Handle(t.Context(), forged); got.Error == "" {
		t.Fatal("伪造令牌的回滚必须被拒绝")
	}

	// 合法令牌的回滚恢复文件。
	valid := fixture.signedRequest("kylin.cleanup.rollback", `{"rollbackId":"`+rollbackID+`"}`)
	if got := server.Handle(t.Context(), valid); got.Error != "" {
		t.Fatalf("合法回滚不应失败：%s", got.Error)
	}
	if data, _ := os.ReadFile(logFile); string(data) != "original content" {
		t.Fatalf("回滚应恢复原内容，实际 %q", string(data))
	}
}
