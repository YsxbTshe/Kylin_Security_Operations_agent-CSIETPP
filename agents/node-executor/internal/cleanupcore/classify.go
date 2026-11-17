package cleanupcore

// 文件分类与保护名单——「系统垃圾清理」场景的判据层。
//
// 赛题要求这个场景「结合运维知识库区分临时缓存、过期日志、可清理文件和关键保护文件」，
// 并且「不会误删数据库日志、业务配置文件、关键数据」。本文件把那份运维知识落成一组
// 可解释、可审计的规则：每个被扫到的文件都会带上分类、判定、命中的规则名和中文理由，
// 评委与运维可以逐条复核，而不是相信一个黑盒的「可清理 N 个文件」。
//
// 三条设计约束：
//
//  1. **默认保留**。只有明确落进「临时缓存 / 过期日志 / 长期未修改的非关键文件」三类
//     的文件才会被动；认不出来的一律 RETAINED。新增一种可清理形态必须改这份代码，
//     配置文件只能收紧（追加保护模式），不能放宽。
//  2. **保护优先**。保护判定跑在所有可清理判定之前，命中即终止。`mysql-slow.log`
//     同时像「日志」和「数据库文件」，它必须被判成后者。
//  3. **只用文件名与 mtime**。不引入外部依赖，不 exec，不读文件内容——这段逻辑最终在
//     privhelper 里以 root 运行，判据面越窄越好。
//
// 已知代价（如实记录，不藏在实现里）：「活动日志」用的是 mtime 启发式，不做 /proc 打开
// 句柄检查，所以一个刚好在 ActiveLogWindow 之外静默了一会儿的活动日志仍可能被判成过期。
// 兜底是 execute 先归档后截断、且可按 SM3 校验回滚——判错的代价是可恢复的，
// 而引入 /proc 扫描会把一段 root 代码的判据面从「两个字段」扩大到「解析全机进程表」。

import (
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// 文件分类。前四类直接对应赛题 PPT 点名的四种文件，后三类是「保留」的细分原因——
// 运维要能看出一个文件是「刚写过」还是「还没到保留期」还是「压根没认出来」。
const (
	CategoryProtected  = "PROTECTED"      // 关键保护文件：数据库/配置/凭据/审计证据，拒绝清理
	CategoryTempCache  = "TEMP_CACHE"     // 临时缓存：可清理
	CategoryExpiredLog = "EXPIRED_LOG"    // 过期日志（含已轮转归档）：可清理
	CategoryStaleFile  = "CLEANABLE_FILE" // 可清理文件：长期未修改的非关键文件
	CategoryActiveLog  = "ACTIVE_LOG"     // 活动日志：疑似正在写入，保留
	CategoryRecentLog  = "RECENT_LOG"     // 近期日志：未超过保留期，保留
	CategoryRetained   = "RETAINED_FILE"  // 其他文件：未识别，按默认拒绝保留
)

// 判定结果。PROTECTED 与 RETAINED 都不会被清理，但含义不同：
// PROTECTED 是「这类文件我们永远不碰」，RETAINED 是「这次不满足清理条件」。
// 分开是为了让审批人看清哪些是策略红线、哪些只是时间没到。
const (
	DecisionCleanable = "CLEANABLE"
	DecisionRetained  = "RETAINED"
	DecisionProtected = "PROTECTED"
)

// Policy 是清理判据的阈值集合。默认值保守；可由 root 持有的配置文件调整（见 config.go），
// 且调整时会被 clamp 在安全区间内。
type Policy struct {
	// ExpiredLogDays 日志保留天数：未轮转的日志超过这个天数才算过期。
	ExpiredLogDays int
	// StaleFileDays 非日志、非缓存的普通文件要「多久没动过」才进可清理。
	// 明显长于 ExpiredLogDays——这类文件我们并不知道它是什么，只知道它不像关键文件。
	StaleFileDays int
	// ActiveLogWindow 活动日志判定窗口：未轮转的日志在这个窗口内被写过就当作正在写入。
	ActiveLogWindow time.Duration
	// ExtraProtect 是配置文件追加的保护通配符。**只增不减**：内置保护清单无法被配置削弱。
	ExtraProtect []string
}

// DefaultPolicy 返回内置默认阈值。配置文件缺失时用它，这是 fail-closed 的一半
// （另一半是 AllowedDirs 回退到两个演示目录）。
func DefaultPolicy() Policy {
	return Policy{
		ExpiredLogDays:  7,
		StaleFileDays:   30,
		ActiveLogWindow: 30 * time.Minute,
	}
}

// Verdict 是单个文件的分类结论。Rule 是命中的规则标识（稳定字符串，便于审计对账），
// Reason 是给人看的中文说明。
type Verdict struct {
	Category string
	Decision string
	Rule     string
	Reason   string
}

// protectedGlobs 是关键保护文件的文件名通配符（对 basename 做小写匹配）。
//
// 这份清单刻意宁滥勿缺：漏保护一个文件的代价是数据丢失且不可逆，多保护一个文件的
// 代价只是少回收几 MB。所以像 *.json、*.bak 这种「可能是垃圾也可能是关键配置/备份」
// 的形态一律划进保护——由人来确认，而不是由 Agent 猜。
var protectedGlobs = []string{
	// 数据库文件与结构化数据：误删即业务数据丢失
	"*.db", "*.db-wal", "*.db-shm", "*.sqlite", "*.sqlite3", "*.mdb", "*.dbf",
	"*.frm", "*.ibd", "*.myd", "*.myi", "*.wal", "*.redo", "*.ldf", "*.mdf",
	"*.sql", "*.dump", "*.bak",
	// 业务配置：误删即服务起不来
	"*.conf", "*.cfg", "*.cnf", "*.ini", "*.properties", "*.toml",
	"*.yaml", "*.yml", "*.json", "*.xml", "*.env", ".env*",
	"*.service", "*.socket", "*.timer", "*.mount", "*.repo",
	// 凭据与证书：误删即无法启动或无法互信
	"*.pem", "*.key", "*.crt", "*.cer", "*.p12", "*.pfx", "*.jks", "*.keystore", "*.kdb",
	"id_rsa*", "id_ecdsa*", "id_ed25519*", "authorized_keys*", "known_hosts*",
	// 运行时状态文件：误删会让存活进程行为异常
	"*.pid", "*.sock", "*.lock", "*.lck",
	// 审计与登录证据：清掉等于毁证，这恰恰是攻击者最想让 Agent 干的事
	"audit.log*", "auth.log*", "secure", "secure-*", "secure.*",
	"wtmp*", "btmp*", "utmp*", "lastlog*", "sudo.log*", "sulog*",
	// 归档包本身不是「垃圾」，需要人工确认后再处理
	"*.tar", "*.tar.gz", "*.tgz", "*.tar.xz", "*.txz", "*.zip", "*.7z", "*.rar",
	// 许可与密钥材料
	"*.lic", "*.license",
}

// protectedNameParts 是出现在文件名里就足以判定为关键保护的子串（小写包含匹配）。
//
// 这条规则是「不会误删数据库日志」的直接落点：mysql-slow.log、mysqld.log、
// postgresql-2026-08-01.log 在扩展名上就是普通 .log，只有靠名字里的引擎名才能拦住。
var protectedNameParts = []string{
	"mysql", "mariadb", "postgres", "pgsql", "oracle", "oceanbase", "dm_svc",
	"redis", "mongod", "etcd", "kafka", "zookeeper", "elasticsearch", "clickhouse",
	"audit", "shadow", "passwd", "gshadow", "kysec", "selinux",
	"license", "keystore", "credential", "secret",
}

// protectedPathSegments 是「目录名一旦出现就保护整棵子树」的段名。
// 关键数据往往不靠文件名自证，而靠它所在的目录——data/、certs/、conf.d/ 里的东西
// 不管叫什么都不该被清理工具碰。
var protectedPathSegments = []string{
	"conf", "conf.d", "config", "configs", "etc",
	"certs", "cert", "ssl", "tls", "pki", "keys", ".ssh", ".gnupg",
	"data", "database", "db", "datafile", "audit", "backup", "backups",
}

// tempCacheGlobs 是临时缓存的文件名形态。这类文件按定义就是可重建的，
// 因此不设年龄门槛——「临时」本身就是清理依据。
var tempCacheGlobs = []string{
	"*.tmp", "*.temp", "*.swp", "*.swo", "*.swx", "*~",
	"*.cache", "*.part", "*.partial", "*.crdownload", "*.download",
	"core", "core.*", "*.core", "*.dmp", "*.hprof", "*.stackdump",
	".ds_store", "thumbs.db",
}

// tempCachePathSegments 同理，但作用于目录名。
var tempCachePathSegments = []string{"tmp", "temp", "cache", "caches", ".cache", "tempfiles"}

// logGlobs 判断「这是不是一个日志文件」。注意它只回答形态问题；
// 一个日志文件是过期、活动还是近期，由 Classify 里的年龄/轮转规则决定。
var logGlobs = []string{
	"*.log", "*.out", "*.err", "*.trace", "*.ndjson", "*_log", "*-log",
	"messages", "syslog", "dmesg", "boot.log", "cron", "maillog",
}

// compressionSuffixes 是轮转日志常见的压缩后缀。
var compressionSuffixes = []string{".gz", ".xz", ".bz2", ".zst", ".lz4", ".z", ".lzma"}

// Classify 给单个文件定分类与判定。
//
// 规则顺序即优先级，命中即返回：
//
//  1. 关键保护（名称通配 / 名称子串 / 所在目录段）      → PROTECTED，永不清理
//  2. 临时缓存                                          → CLEANABLE
//  3. 日志：已轮转 → 过期可清理；窗口内写过 → 活动保留；
//     超保留期 → 过期可清理；否则 → 近期保留
//  4. 长期未修改的普通文件                              → CLEANABLE
//  5. 其余                                              → RETAINED（默认拒绝）
//
// relPath 是相对目标目录的路径（用于目录段判定），modTime 是文件 mtime，now 是判定基准时刻。
func Classify(relPath string, modTime time.Time, now time.Time, policy Policy) Verdict {
	base := strings.ToLower(filepath.Base(relPath))
	segments := pathSegments(relPath)

	// —— 1. 关键保护 ——
	if pattern, ok := matchAnyGlob(base, protectedGlobs); ok {
		return Verdict{CategoryProtected, DecisionProtected, "protect.glob:" + pattern,
			"命中关键保护名单（" + pattern + "）：数据库/配置/凭据/审计类文件一律不清理"}
	}
	if pattern, ok := matchAnyGlob(base, normalizeGlobs(policy.ExtraProtect)); ok {
		return Verdict{CategoryProtected, DecisionProtected, "protect.extra:" + pattern,
			"命中部署方追加的保护名单（" + pattern + "）"}
	}
	if part, ok := containsAny(base, protectedNameParts); ok {
		return Verdict{CategoryProtected, DecisionProtected, "protect.name:" + part,
			"文件名含关键组件标识「" + part + "」：疑似数据库/安全组件的日志或数据，不清理"}
	}
	if seg, ok := matchAnySegment(segments, protectedPathSegments); ok {
		return Verdict{CategoryProtected, DecisionProtected, "protect.dir:" + seg,
			"位于受保护目录「" + seg + "/」之下：该子树被视为关键数据，不清理"}
	}

	ageDays := AgeDays(modTime, now)

	// —— 2. 临时缓存 ——
	if pattern, ok := matchAnyGlob(base, tempCacheGlobs); ok {
		return Verdict{CategoryTempCache, DecisionCleanable, "temp.glob:" + pattern,
			"临时缓存文件（" + pattern + "）：内容可重建，可清理"}
	}
	if seg, ok := matchAnySegment(segments, tempCachePathSegments); ok {
		return Verdict{CategoryTempCache, DecisionCleanable, "temp.dir:" + seg,
			"位于临时/缓存目录「" + seg + "/」之下：可清理"}
	}

	// —— 3. 日志 ——
	if stem, rotated := splitRotation(base); isLogName(stem) {
		switch {
		case rotated:
			return Verdict{CategoryExpiredLog, DecisionCleanable, "log.rotated",
				"已轮转归档的历史日志：写入方已切换到新文件，可清理"}
		case now.Sub(modTime) < policy.ActiveLogWindow:
			return Verdict{CategoryActiveLog, DecisionRetained, "log.active",
				"活动日志：最近 " + durationText(policy.ActiveLogWindow) + "内仍有写入，截断可能损坏正在写的日志，保留"}
		case ageDays >= policy.ExpiredLogDays:
			return Verdict{CategoryExpiredLog, DecisionCleanable, "log.expired",
				"过期日志：已 " + strconv.Itoa(ageDays) + " 天未更新，超过保留期 " + strconv.Itoa(policy.ExpiredLogDays) + " 天，可清理"}
		default:
			return Verdict{CategoryRecentLog, DecisionRetained, "log.recent",
				"近期日志：仅 " + strconv.Itoa(ageDays) + " 天未更新，未超过保留期 " + strconv.Itoa(policy.ExpiredLogDays) + " 天，保留"}
		}
	}

	// —— 4. 长期未修改的普通文件 ——
	if ageDays >= policy.StaleFileDays {
		return Verdict{CategoryStaleFile, DecisionCleanable, "file.stale",
			"可清理文件：非关键文件且已 " + strconv.Itoa(ageDays) + " 天未修改，超过 " + strconv.Itoa(policy.StaleFileDays) + " 天阈值"}
	}

	// —— 5. 兜底：默认拒绝 ——
	return Verdict{CategoryRetained, DecisionRetained, "file.unclassified",
		"未识别的文件类型且仅 " + strconv.Itoa(ageDays) + " 天未修改：按默认拒绝保留"}
}

// AgeDays 返回文件的天龄（向下取整）。mtime 在未来（时钟回拨或人为设置）时返回 0——
// 负的年龄没有意义，且绝不能因此让文件「看起来更旧」而被清理。
func AgeDays(modTime, now time.Time) int {
	delta := now.Sub(modTime)
	if delta <= 0 {
		return 0
	}
	return int(delta / (24 * time.Hour))
}

// isLogName 判断去掉轮转后缀后的名字是否像日志。
func isLogName(stem string) bool {
	_, ok := matchAnyGlob(stem, logGlobs)
	return ok
}

// splitRotation 剥掉 logrotate 风格的轮转后缀，返回原始日志名与「是否已轮转」。
//
// 覆盖的形态（先剥压缩后缀，再剥序号/日期后缀）：
//
//	app.log.1            → app.log, rotated
//	app.log.gz           → app.log, rotated
//	app.log-20260801.gz  → app.log, rotated
//	app.log.2026-08-01   → app.log, rotated
//	app.log.old          → app.log, rotated
//	app.log              → app.log, not rotated
//
// 刻意不用正则：这段代码在 root 进程里跑，手写的定长判断比正则更容易看懂，
// 也没有回溯行为。
func splitRotation(base string) (string, bool) {
	name := base
	rotated := false
	for _, suffix := range compressionSuffixes {
		if strings.HasSuffix(name, suffix) && len(name) > len(suffix) {
			name = strings.TrimSuffix(name, suffix)
			rotated = true
			break
		}
	}
	// 允许连剥数层：app.log.2026-08-01 的日期后缀会被 "." 与 "-" 切成好几段。
	// 上限 4 是防呆——真实轮转命名不会比这更深，超过就说明它不是轮转日志。
	for range 4 {
		idx := strings.LastIndexAny(name, ".-")
		if idx <= 0 || idx == len(name)-1 {
			break
		}
		suffix := name[idx+1:]
		if !isRotationToken(suffix) {
			break
		}
		name = name[:idx]
		rotated = true
	}
	return name, rotated
}

// isRotationToken 判断一个后缀片段是否是轮转标记：纯数字序号/日期，或 old/bak 之类的字面量。
func isRotationToken(token string) bool {
	if token == "old" || token == "orig" || token == "prev" {
		return true
	}
	if token == "" {
		return false
	}
	digits := 0
	for _, r := range token {
		switch {
		case r >= '0' && r <= '9':
			digits++
		case r == '-' || r == '_':
			// 允许 2026-08-01 这种带分隔符的日期
		default:
			return false
		}
	}
	// 至少要有一位数字，且不接受过长的串（避免把 app.1234567890123456 之类当轮转）
	return digits >= 1 && digits <= 14
}

// matchAnyGlob 对 name 依次尝试通配符，返回命中的模式。
// 模式解析失败（filepath.Match 报错）时按「不命中」处理——但对保护名单来说，
// 内置模式是常量、不会失败；追加模式在加载时已校验过。
func matchAnyGlob(name string, globs []string) (string, bool) {
	for _, glob := range globs {
		if ok, err := filepath.Match(glob, name); err == nil && ok {
			return glob, true
		}
	}
	return "", false
}

func containsAny(name string, parts []string) (string, bool) {
	for _, part := range parts {
		if strings.Contains(name, part) {
			return part, true
		}
	}
	return "", false
}

// matchAnySegment 检查相对路径的**目录**段（不含最后的文件名）是否命中。
func matchAnySegment(segments []string, wanted []string) (string, bool) {
	for _, segment := range segments {
		for _, want := range wanted {
			if segment == want {
				return segment, true
			}
		}
	}
	return "", false
}

// pathSegments 返回相对路径中除文件名外的各级目录名（小写）。
func pathSegments(relPath string) []string {
	dir := filepath.ToSlash(filepath.Dir(filepath.ToSlash(relPath)))
	if dir == "." || dir == "/" || dir == "" {
		return nil
	}
	parts := strings.Split(strings.Trim(dir, "/"), "/")
	segments := make([]string, 0, len(parts))
	for _, part := range parts {
		if part != "" {
			segments = append(segments, strings.ToLower(part))
		}
	}
	return segments
}

// normalizeGlobs 把配置里的追加模式小写化并丢掉空项。
func normalizeGlobs(globs []string) []string {
	if len(globs) == 0 {
		return nil
	}
	out := make([]string, 0, len(globs))
	for _, glob := range globs {
		if trimmed := strings.ToLower(strings.TrimSpace(glob)); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func durationText(d time.Duration) string {
	if d >= time.Hour && d%time.Hour == 0 {
		return strconv.Itoa(int(d/time.Hour)) + " 小时"
	}
	return strconv.Itoa(int(d/time.Minute)) + " 分钟"
}
