package cleanupcore

// 清理作用域与判据阈值的可配置化——由 root 持有的配置文件提供。
//
// 改这件事的动机很实在：此前 AllowedDirs 是 Go 常量数组，扩展一个目录要改代码、
// 重新编译**以 root 运行的 privhelper**、再重新分发二进制。部署方想让 Agent 清理
// /opt/myapp/logs，代价是重新过一遍二进制发布流程——这不是安全，这是不可用。
//
// 但可配置不等于可放宽。这里的设计红线：
//
//   - **仍是白名单，默认拒绝**。配置文件给的是「允许哪些目录」，不是「禁止哪些目录」。
//     ResolveTarget 依旧是精确匹配，`/tmp/aiops-demo-logs-evil` 依旧被拒。
//   - **fail-closed**。文件缺失、读不了、不是 root 属主、权限过宽、内容非法——
//     任何一种情况都回退到内置的两个演示目录，绝不退化成「允许任意目录」。
//     回退原因会带在 Report 里，由调用方打进日志，不做静默降级。
//   - **配置也过白名单**。配置里写的每个目录还要再过一遍硬禁清单（/etc、/boot、
//     数据库目录、以及任何是硬禁目录祖先的路径）与最小深度检查。root 写错一行配置
//     不该等于把 /var/log 整个交出去。
//   - **保护名单只能加不能减**。cleanup_protect_extra 只往内置保护清单上追加；
//     没有任何配置项能让 *.db 或 *.conf 变成可清理。
//
// 文件格式沿用 privhelper allow.conf 的极简 key=value（见 cmd/privhelper/main.go），
// 属主校验也照搬同一套判据：解析器越简单，越不容易成为攻击面。

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// DefaultConfigPath 是清理配置的默认位置，与 privhelper 的 allow.conf 同目录同属主要求。
const DefaultConfigPath = "/etc/kylin-privhelper/cleanup.conf"

// ConfigPathEnv 允许 unit 文件覆盖配置路径。注意它只能指向另一个 root 属主的文件——
// 属主校验在 LoadConfig 里，改环境变量绕不过去。
const ConfigPathEnv = "KYLIN_CLEANUP_CONFIG"

// DefaultAllowedDirs 是配置缺失时的兜底作用域：原来那两个演示目录，一个不多。
var DefaultAllowedDirs = []string{"/var/log/aiops-demo", "/tmp/aiops-demo-logs"}

// AllowedDirs 是清理当前生效的目录白名单（精确匹配，非前缀匹配）。
// 默认等于 DefaultAllowedDirs；Configure 成功加载配置后才会被替换。
var AllowedDirs = append([]string(nil), DefaultAllowedDirs...)

// ActivePolicy 是当前生效的分类阈值。
var ActivePolicy = DefaultPolicy()

// hardBlockedExact 是绝不允许出现在白名单里的目录（精确匹配）。
// 这些要么是系统根基，要么范围大到一次参数错误就能造成不可逆损失。
var hardBlockedExact = []string{
	"/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/media", "/mnt",
	"/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var",
	"/var/cache", "/var/lib", "/var/log", "/var/run", "/var/spool", "/var/tmp",
}

// hardBlockedSubtrees 是连同子树一起禁止的路径：白名单里任何落在这些子树下的目录都拒绝。
// /var/log/audit 是审计证据所在，/var/lib/mysql 之流是数据库数据目录——
// 把它们交给一个会截断文件的工具，等于把「不可逆」和「最不该丢」放在了一起。
var hardBlockedSubtrees = []string{
	"/boot", "/dev", "/etc", "/proc", "/root", "/sys", "/usr", "/bin", "/sbin", "/lib", "/lib64",
	"/var/log/audit", "/var/lib/mysql", "/var/lib/postgresql", "/var/lib/pgsql",
	"/var/lib/mongodb", "/var/lib/redis", "/var/lib/etcd", "/var/lib/docker",
}

// minAllowedDepth 要求白名单目录至少两级（/opt/app 可以，/opt 不行）。
// 一级目录几乎必然是系统目录，把它整个交出去从来不是部署方的本意。
const minAllowedDepth = 2

// Config 是从配置文件解析出来的内容。
type Config struct {
	AllowedDirs []string
	Policy      Policy
}

// Report 记录一次 Configure 的结果，供调用方打进启动日志。
// 「用了默认值」必须在日志里看得见——静默降级会让人以为配置已经生效。
type Report struct {
	// Source 是实际生效的来源："default"（内置兜底）或配置文件路径。
	Source string
	// FallbackReason 非空表示发生了 fail-closed 回退，内容是回退原因。
	FallbackReason string
	AllowedDirs    []string
	Policy         Policy
	// Rejected 是配置里写了但没通过校验、被丢弃的目录及原因。
	Rejected []string
}

// Configure 加载配置并应用到包级的 AllowedDirs / ActivePolicy。
//
// 永远不返回 error：清理配置读不了不该让 privhelper 起不来（那会连服务启停一起废掉），
// 但也绝不能悄悄放宽——读不了就退回内置两目录，并把原因写进 Report 由调用方喊出来。
func Configure(path string) Report {
	trimmed := strings.TrimSpace(path)
	if trimmed == "" {
		trimmed = DefaultConfigPath
	}
	config, rejected, err := LoadConfig(trimmed)
	if err != nil {
		AllowedDirs = append([]string(nil), DefaultAllowedDirs...)
		ActivePolicy = DefaultPolicy()
		return Report{
			Source:         "default",
			FallbackReason: err.Error(),
			AllowedDirs:    AllowedDirs,
			Policy:         ActivePolicy,
			Rejected:       rejected,
		}
	}
	AllowedDirs = config.AllowedDirs
	ActivePolicy = config.Policy
	return Report{Source: trimmed, AllowedDirs: config.AllowedDirs, Policy: config.Policy, Rejected: rejected}
}

// String 让 Report 能直接进日志一行。
func (r Report) String() string {
	text := fmt.Sprintf("清理作用域来源=%s 允许目录=%v 日志保留=%d天 陈旧文件=%d天 活动窗口=%s",
		r.Source, r.AllowedDirs, r.Policy.ExpiredLogDays, r.Policy.StaleFileDays, r.Policy.ActiveLogWindow)
	if len(r.Rejected) > 0 {
		text += fmt.Sprintf(" 已拒绝的配置项=%v", r.Rejected)
	}
	if r.FallbackReason != "" {
		text += fmt.Sprintf("（fail-closed 回退：%s）", r.FallbackReason)
	}
	return text
}

// LoadConfig 读取并校验配置文件。
//
// 返回的 rejected 列出「配置里写了但被判非法」的目录——它们被丢弃而不是让整份配置失效：
// 一个写错的目录不该把另外几个正确的目录一起废掉。但如果**所有**目录都非法，
// 那是配置根本没写对，按错误处理，交由 Configure 回退。
func LoadConfig(path string) (Config, []string, error) {
	config := Config{Policy: DefaultPolicy()}
	var rejected []string

	file, err := os.Open(path)
	if err != nil {
		return config, rejected, fmt.Errorf("读取清理配置 %s 失败：%w", path, err)
	}
	defer file.Close()

	// 属主校验与 privhelper allow.conf 同判据：必须 root 属主、组和其他用户不可写。
	// 可写就意味着非特权进程能自己把白名单改宽——那白名单就不是白名单了。
	if err := requireRootOwnedFile(file, path, 0o022); err != nil {
		return config, rejected, err
	}
	return parseConfig(file)
}

// parseConfig 是纯解析部分，与属主校验分开，便于单测覆盖解析与校验语义
// （单测环境造不出 root 属主的文件，但解析逻辑必须被测到）。
func parseConfig(reader io.Reader) (Config, []string, error) {
	config := Config{Policy: DefaultPolicy()}
	var rejected []string
	var err error

	var rawDirs []string
	dirsSeen := false
	scanner := bufio.NewScanner(reader)
	line := 0
	for scanner.Scan() {
		line++
		text := strings.TrimSpace(scanner.Text())
		if text == "" || strings.HasPrefix(text, "#") {
			continue
		}
		key, value, found := strings.Cut(text, "=")
		if !found {
			return config, rejected, fmt.Errorf("清理配置第 %d 行格式非法，应为 key=value", line)
		}
		key, value = strings.TrimSpace(key), strings.TrimSpace(value)
		switch key {
		case "cleanup_allowed_dirs":
			rawDirs = splitCommaList(value)
			dirsSeen = true
		case "cleanup_expired_log_days":
			config.Policy.ExpiredLogDays, err = strconv.Atoi(value)
		case "cleanup_stale_file_days":
			config.Policy.StaleFileDays, err = strconv.Atoi(value)
		case "cleanup_active_log_minutes":
			var minutes int
			minutes, err = strconv.Atoi(value)
			config.Policy.ActiveLogWindow = time.Duration(minutes) * time.Minute
		case "cleanup_protect_extra":
			config.Policy.ExtraProtect, err = parseProtectPatterns(value)
		default:
			// 认不出的键一律报错。拼错一个键名却静默沿用默认值，
			// 会让部署方以为配置已经生效——这条和 allow.conf 的取舍一致。
			return config, rejected, fmt.Errorf("清理配置含未知配置项 %q（第 %d 行）", key, line)
		}
		if err != nil {
			return config, rejected, fmt.Errorf("清理配置项 %s 取值非法：%w", key, err)
		}
	}
	if err := scanner.Err(); err != nil {
		return config, rejected, fmt.Errorf("读取清理配置失败：%w", err)
	}

	config.Policy = clampPolicy(config.Policy)

	if !dirsSeen {
		// 只配了阈值、没配目录：作用域保持内置两目录，这是合法的用法。
		config.AllowedDirs = append([]string(nil), DefaultAllowedDirs...)
		return config, rejected, nil
	}

	allowed := make([]string, 0, len(rawDirs))
	seen := map[string]bool{}
	for _, raw := range rawDirs {
		cleaned, err := ValidateAllowedDir(raw)
		if err != nil {
			rejected = append(rejected, fmt.Sprintf("%s（%s）", raw, err.Error()))
			continue
		}
		if seen[cleaned] {
			continue
		}
		seen[cleaned] = true
		allowed = append(allowed, cleaned)
	}
	if len(allowed) == 0 {
		return config, rejected, fmt.Errorf("清理配置的 cleanup_allowed_dirs 没有任何合法目录")
	}
	config.AllowedDirs = allowed
	return config, rejected, nil
}

// ValidateAllowedDir 校验一个配置进来的目录是否可以进白名单。
//
// 这道校验是「配置也要过白名单」的落点：root 可以写这个文件，但写错一行不该等于
// 把系统目录交给一个会截断文件的工具。
func ValidateAllowedDir(raw string) (string, error) {
	dir := strings.TrimSpace(raw)
	if dir == "" {
		return "", fmt.Errorf("空目录")
	}
	if strings.ContainsRune(dir, 0) {
		return "", fmt.Errorf("含非法字符")
	}
	if !strings.HasPrefix(dir, "/") {
		return "", fmt.Errorf("必须是绝对路径")
	}
	cleaned := filepath.ToSlash(filepath.Clean(dir))
	// Clean 之后与原串（去掉尾斜杠后）不一致，说明写了 ..、. 或重复斜杠。
	// 直接拒绝而不是「帮它归一化」：白名单里出现 /var/log/aiops-demo/../../etc
	// 这种写法，本身就说明这行配置需要人再看一眼。
	if cleaned != strings.TrimRight(dir, "/") && cleaned != dir {
		return "", fmt.Errorf("路径未归一化（含 . / .. / 重复斜杠）")
	}
	if strings.Contains(cleaned, "..") {
		return "", fmt.Errorf("含上跳路径")
	}
	if depth(cleaned) < minAllowedDepth {
		return "", fmt.Errorf("层级过浅，至少需要 %d 级目录", minAllowedDepth)
	}
	for _, blocked := range hardBlockedExact {
		if cleaned == blocked {
			return "", fmt.Errorf("命中硬禁目录 %s", blocked)
		}
	}
	for _, subtree := range hardBlockedSubtrees {
		if cleaned == subtree || strings.HasPrefix(cleaned, subtree+"/") {
			return "", fmt.Errorf("位于硬禁子树 %s 之下", subtree)
		}
		// 反向：配置的目录是硬禁目录的祖先时同样拒绝——放行 /var/lib 就等于
		// 连 /var/lib/mysql 一起放行了。
		if strings.HasPrefix(subtree, cleaned+"/") {
			return "", fmt.Errorf("是硬禁目录 %s 的上级", subtree)
		}
	}
	return cleaned, nil
}

// clampPolicy 把阈值收进安全区间。
//
// 为什么要 clamp 一个 root 才能写的文件：不是防 root，是防手滑。
// cleanup_expired_log_days=0 会让「所有日志都算过期」，一次笔误就把保留期整个抹掉；
// 空值/负值同理。落在区间外的值一律回退到默认值，而不是取边界值——
// 取边界值会掩盖配置写错这件事。
func clampPolicy(policy Policy) Policy {
	defaults := DefaultPolicy()
	if policy.ExpiredLogDays < 1 || policy.ExpiredLogDays > 3650 {
		policy.ExpiredLogDays = defaults.ExpiredLogDays
	}
	if policy.StaleFileDays < 1 || policy.StaleFileDays > 3650 {
		policy.StaleFileDays = defaults.StaleFileDays
	}
	if policy.ActiveLogWindow < time.Minute || policy.ActiveLogWindow > 24*time.Hour {
		policy.ActiveLogWindow = defaults.ActiveLogWindow
	}
	return policy
}

// parseProtectPatterns 解析追加保护模式，并要求每个模式都是合法的通配符。
// 非法模式直接让整份配置失败——一个永远匹配不上的保护模式比没有更危险，
// 它会让部署方以为某类文件已经受保护了。
func parseProtectPatterns(value string) ([]string, error) {
	patterns := splitCommaList(value)
	for _, pattern := range patterns {
		if strings.ContainsRune(pattern, '/') {
			return nil, fmt.Errorf("保护模式 %q 只能匹配文件名，不能含路径分隔符", pattern)
		}
		if _, err := filepath.Match(pattern, "probe"); err != nil {
			return nil, fmt.Errorf("保护模式 %q 非法：%w", pattern, err)
		}
	}
	return patterns, nil
}

// requireRootOwnedFile 校验文件属主为 root 且未开放 forbiddenBits 指定的权限位。
// 与 cmd/privhelper 的 requireRootOwned 判据一致，这里独立实现是因为那份在 package main 里。
func requireRootOwnedFile(file *os.File, path string, forbiddenBits os.FileMode) error {
	info, err := file.Stat()
	if err != nil {
		return fmt.Errorf("检查 %s 属性失败：%w", path, err)
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("%s 不是普通文件", path)
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return fmt.Errorf("无法确认 %s 的属主", path)
	}
	if stat.Uid != 0 {
		return fmt.Errorf("%s 必须属 root（当前 uid=%d）", path, stat.Uid)
	}
	if mode := info.Mode().Perm(); mode&forbiddenBits != 0 {
		return fmt.Errorf("%s 权限过宽（当前 %#o）", path, mode)
	}
	return nil
}

func depth(cleaned string) int {
	parts := strings.Split(strings.Trim(cleaned, "/"), "/")
	count := 0
	for _, part := range parts {
		if part != "" {
			count++
		}
	}
	return count
}

func splitCommaList(value string) []string {
	var items []string
	for _, item := range strings.Split(value, ",") {
		if trimmed := strings.TrimSpace(item); trimmed != "" {
			items = append(items, trimmed)
		}
	}
	return items
}
