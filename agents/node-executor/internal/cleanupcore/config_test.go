package cleanupcore

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// restoreGlobals 保证配置类测试不会把包级状态泄漏给别的测试。
func restoreGlobals(t *testing.T) {
	t.Helper()
	oldDirs, oldPolicy := AllowedDirs, ActivePolicy
	t.Cleanup(func() { AllowedDirs, ActivePolicy = oldDirs, oldPolicy })
}

// fail-closed 第一条：配置文件不存在时回退到内置两个演示目录，而不是放开作用域。
func TestConfigureFallsBackWhenConfigMissing(t *testing.T) {
	restoreGlobals(t)
	AllowedDirs = []string{"/should/be/replaced"}

	report := Configure(filepath.Join(t.TempDir(), "does-not-exist.conf"))

	if report.FallbackReason == "" {
		t.Fatal("配置缺失必须在 Report 里留下回退原因，不能静默降级")
	}
	if report.Source != "default" {
		t.Fatalf("期望来源 default，实际 %s", report.Source)
	}
	if !slices.Equal(AllowedDirs, DefaultAllowedDirs) {
		t.Fatalf("期望回退到内置演示目录 %v，实际 %v", DefaultAllowedDirs, AllowedDirs)
	}
	// 回退绝不能变成「允许任意目录」。
	for _, target := range []string{"/etc", "/var/log", "/", "/opt/anything"} {
		if _, err := ResolveTarget(target); err == nil {
			t.Fatalf("回退后 %s 仍必须被拒绝", target)
		}
	}
	if _, err := ResolveTarget("/var/log/aiops-demo"); err != nil {
		t.Fatalf("回退后内置演示目录应仍可用：%v", err)
	}
}

// fail-closed 第二条：配置文件存在但不是 root 属主时，同样回退。
// 非 root 属主意味着这份「白名单」可以被非特权进程改宽——那它就不是白名单了。
func TestConfigureFallsBackWhenConfigNotRootOwned(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("以 root 运行时无法构造非 root 属主的文件")
	}
	restoreGlobals(t)

	path := filepath.Join(t.TempDir(), "cleanup.conf")
	if err := os.WriteFile(path, []byte("cleanup_allowed_dirs=/opt/app/logs\n"), 0o644); err != nil {
		t.Fatalf("write config: %v", err)
	}

	report := Configure(path)
	if report.FallbackReason == "" {
		t.Fatal("非 root 属主的配置必须被拒绝并回退")
	}
	if !strings.Contains(report.FallbackReason, "root") {
		t.Fatalf("回退原因应点明属主问题，实际 %q", report.FallbackReason)
	}
	if !slices.Equal(AllowedDirs, DefaultAllowedDirs) {
		t.Fatalf("期望回退到 %v，实际 %v", DefaultAllowedDirs, AllowedDirs)
	}
	if _, err := ResolveTarget("/opt/app/logs"); err == nil {
		t.Fatal("被拒绝的配置里的目录绝不能生效")
	}
}

// 配置权限过宽（组/其他用户可写）同样按 fail-closed 处理。
func TestConfigureFallsBackWhenConfigWorldWritable(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("以 root 运行时该用例由属主校验以外的分支覆盖")
	}
	restoreGlobals(t)

	path := filepath.Join(t.TempDir(), "cleanup.conf")
	if err := os.WriteFile(path, []byte("cleanup_allowed_dirs=/opt/app/logs\n"), 0o666); err != nil {
		t.Fatalf("write config: %v", err)
	}
	if report := Configure(path); report.FallbackReason == "" {
		t.Fatal("权限过宽的配置必须被拒绝并回退")
	}
}

// 正常解析：目录白名单 + 阈值 + 追加保护模式。
func TestParseConfigAcceptsValidContent(t *testing.T) {
	content := `
# 注释行
cleanup_allowed_dirs = /opt/myapp/logs , /var/log/aiops-demo
cleanup_expired_log_days=14
cleanup_stale_file_days=60
cleanup_active_log_minutes=5
cleanup_protect_extra=*.report,*.ledger
`
	config, rejected, err := parseConfig(strings.NewReader(content))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(rejected) != 0 {
		t.Fatalf("不应有被拒绝的目录：%v", rejected)
	}
	if !slices.Equal(config.AllowedDirs, []string{"/opt/myapp/logs", "/var/log/aiops-demo"}) {
		t.Fatalf("目录解析错误：%v", config.AllowedDirs)
	}
	if config.Policy.ExpiredLogDays != 14 || config.Policy.StaleFileDays != 60 {
		t.Fatalf("阈值解析错误：%+v", config.Policy)
	}
	if config.Policy.ActiveLogWindow != 5*time.Minute {
		t.Fatalf("活动窗口解析错误：%v", config.Policy.ActiveLogWindow)
	}
	if !slices.Equal(config.Policy.ExtraProtect, []string{"*.report", "*.ledger"}) {
		t.Fatalf("追加保护模式解析错误：%v", config.Policy.ExtraProtect)
	}
}

// 只配阈值、不配目录是合法用法：作用域保持内置两目录。
func TestParseConfigWithoutDirsKeepsDefaults(t *testing.T) {
	config, _, err := parseConfig(strings.NewReader("cleanup_expired_log_days=3\n"))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if !slices.Equal(config.AllowedDirs, DefaultAllowedDirs) {
		t.Fatalf("未配置目录时应保持内置演示目录，实际 %v", config.AllowedDirs)
	}
}

// 拼错的键名必须报错而不是静默沿用默认值——否则部署方会以为限制已经配上了。
func TestParseConfigRejectsUnknownKey(t *testing.T) {
	if _, _, err := parseConfig(strings.NewReader("cleanup_alowed_dirs=/opt/app/logs\n")); err == nil {
		t.Fatal("未知配置项必须报错")
	}
	if _, _, err := parseConfig(strings.NewReader("this line has no equals sign\n")); err == nil {
		t.Fatal("非 key=value 的行必须报错")
	}
}

// 非法的追加保护模式必须让整份配置失败：一个永远匹配不上的保护模式比没有更危险。
func TestParseConfigRejectsIllegalProtectPattern(t *testing.T) {
	if _, _, err := parseConfig(strings.NewReader("cleanup_protect_extra=[a-\n")); err == nil {
		t.Fatal("非法通配符必须报错")
	}
	if _, _, err := parseConfig(strings.NewReader("cleanup_protect_extra=/etc/*\n")); err == nil {
		t.Fatal("含路径分隔符的保护模式必须报错")
	}
}

// 一行写错的目录不该废掉另外几行正确的目录，但它必须被丢弃并报出来。
func TestParseConfigDropsIllegalDirsAndReportsThem(t *testing.T) {
	config, rejected, err := parseConfig(strings.NewReader(
		"cleanup_allowed_dirs=/opt/app/logs,/etc,/var/log/aiops-demo\n"))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if !slices.Equal(config.AllowedDirs, []string{"/opt/app/logs", "/var/log/aiops-demo"}) {
		t.Fatalf("非法目录未被丢弃：%v", config.AllowedDirs)
	}
	if len(rejected) != 1 || !strings.Contains(rejected[0], "/etc") {
		t.Fatalf("被丢弃的目录必须报出来：%v", rejected)
	}
}

// 全部目录都非法 = 配置根本没写对，按错误处理，交由 Configure 回退。
func TestParseConfigFailsWhenEveryDirIsIllegal(t *testing.T) {
	if _, _, err := parseConfig(strings.NewReader("cleanup_allowed_dirs=/etc,/boot,/\n")); err == nil {
		t.Fatal("目录全非法时必须整体失败")
	}
}

// 配置里的每个目录都要再过一遍硬禁清单：root 写错一行不该等于把系统目录交出去。
func TestValidateAllowedDirRefusesDangerousPaths(t *testing.T) {
	bad := []string{
		"", "  ",
		"relative/path",              // 非绝对路径
		"/", "/etc", "/boot", "/usr", // 系统根基
		"/var", "/var/log", "/var/lib", "/tmp", "/opt", // 范围过大
		"/etc/nginx/conf.d",     // 硬禁子树之下
		"/var/log/audit",        // 审计证据
		"/var/log/audit/nested", // 审计证据子目录
		"/var/lib/mysql/data",   // 数据库数据目录
		"/var/lib",              // 是 /var/lib/mysql 的上级
		"/opt/app/../../etc",    // 目录穿越
		"/opt/./app",            // 未归一化
		"/opt//app",             // 未归一化
		"/srv",                  // 单级目录
	}
	for _, dir := range bad {
		if cleaned, err := ValidateAllowedDir(dir); err == nil {
			t.Fatalf("%q 应被拒绝，却通过成 %q", dir, cleaned)
		}
	}

	good := map[string]string{
		"/opt/myapp/logs":       "/opt/myapp/logs",
		"/var/log/aiops-demo":   "/var/log/aiops-demo",
		"/tmp/aiops-demo-logs":  "/tmp/aiops-demo-logs",
		"/var/log/myapp/":       "/var/log/myapp",
		"/data/app/tmp-uploads": "/data/app/tmp-uploads",
	}
	for dir, want := range good {
		got, err := ValidateAllowedDir(dir)
		if err != nil {
			t.Fatalf("%q 应被接受：%v", dir, err)
		}
		if got != want {
			t.Fatalf("%q 期望归一化为 %q，实际 %q", dir, want, got)
		}
	}
}

// 手滑写出的阈值（0、负数、超大）一律回退到默认值，而不是取边界值——
// 取边界值会掩盖「配置写错了」这件事。
func TestClampPolicyRejectsUnsafeThresholds(t *testing.T) {
	defaults := DefaultPolicy()
	for _, raw := range []Policy{
		{ExpiredLogDays: 0, StaleFileDays: 0, ActiveLogWindow: 0},
		{ExpiredLogDays: -5, StaleFileDays: -5, ActiveLogWindow: -time.Hour},
		{ExpiredLogDays: 99999, StaleFileDays: 99999, ActiveLogWindow: 999 * time.Hour},
	} {
		got := clampPolicy(raw)
		if got.ExpiredLogDays != defaults.ExpiredLogDays ||
			got.StaleFileDays != defaults.StaleFileDays ||
			got.ActiveLogWindow != defaults.ActiveLogWindow {
			t.Fatalf("非法阈值 %+v 应回退到默认，实际 %+v", raw, got)
		}
	}
	// 合法值必须原样保留，否则配置就白配了。
	sane := Policy{ExpiredLogDays: 30, StaleFileDays: 90, ActiveLogWindow: 10 * time.Minute}
	got := clampPolicy(sane)
	if got.ExpiredLogDays != sane.ExpiredLogDays ||
		got.StaleFileDays != sane.StaleFileDays ||
		got.ActiveLogWindow != sane.ActiveLogWindow {
		t.Fatalf("合法阈值应原样保留，实际 %+v", got)
	}
}

// 白名单语义必须保持「默认拒绝 + 精确匹配」：配置扩展作用域后，
// 新目录可用，但既不是前缀匹配，也不会顺带放开别的目录。
func TestConfiguredDirsKeepExactMatchSemantics(t *testing.T) {
	restoreGlobals(t)

	config, _, err := parseConfig(strings.NewReader("cleanup_allowed_dirs=/opt/myapp/logs\n"))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	AllowedDirs = config.AllowedDirs

	if _, err := ResolveTarget("/opt/myapp/logs"); err != nil {
		t.Fatalf("配置进来的目录应可用：%v", err)
	}
	for _, target := range []string{
		"/opt/myapp/logs-evil",   // 前缀相近
		"/opt/myapp/logs/nested", // 子目录
		"/opt/myapp",             // 上级目录
		"/var/log/aiops-demo",    // 未在本次配置里
		"/etc",
	} {
		if _, err := ResolveTarget(target); err == nil {
			t.Fatalf("%s 必须被拒绝", target)
		}
	}
}
