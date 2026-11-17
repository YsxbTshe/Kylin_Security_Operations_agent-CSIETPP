// kylin-privhelper 是以 root 运行的特权助手（ADR-002）。
//
// 它是整个系统里唯一持有 root 的进程，因此刻意保持极小：
//   - 不连网络、不接触 LLM、不加载插件、不解析控制面下发的任何配置；
//   - 只监听一个 unix socket，只认识一张固定的工具→操作映射表；
//   - 允许清单与审批公钥都来自 root 拥有的本地文件，非特权的 daemon 改不了。
//
// 非特权的 kylin-node-executor 通过它执行需要特权的写操作。daemon 自身永不持有特权，
// 也不持有任何签名材料——审批令牌由控制面以 SM2 私钥签发，助手只持公钥验真，
// 攻陷 daemon 无法伪造审批令牌。
package main

import (
	"bufio"
	"context"
	"crypto/ecdsa"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"os/user"
	"strconv"
	"strings"
	"syscall"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/approvalv2"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/cleanupcore"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/privops"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
)

const (
	defaultConfigPath    = "/etc/kylin-privhelper/allow.conf"
	defaultPublicKeyPath = "/etc/kylin-privhelper/approval.pub"
	defaultPublicKeyID   = "approval-1"
	defaultStorePath     = "/var/lib/kylin-privhelper/rollback"
	defaultLedgerPath    = "/var/lib/kylin-privhelper/approval-ledger"
	defaultPeerUser      = "kylin-agent"
)

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	if err := run(); err != nil {
		log.Fatalf("privhelper: %v", err)
	}
}

func run() error {
	if os.Geteuid() != 0 {
		// 助手的全部价值在于它是特权边界的另一侧。以非 root 启动说明部署有误，
		// 此时必须失败——否则会得到一个「看起来在跑、写操作却全失败」的系统。
		return fmt.Errorf("必须以 root 运行")
	}

	policy, err := loadPolicy(envOr("KYLIN_PRIVHELPER_CONFIG", defaultConfigPath))
	if err != nil {
		return err
	}

	// 清理作用域与判据阈值同样来自 root 持有的配置文件。读不到就 fail-closed 回退到
	// 内置的两个演示目录——不阻断启动（清理配置缺失不该把服务启停一起废掉），
	// 但必须在 journal 里留一行，否则「以为配好了其实没生效」是最坏的一种失败。
	cleanupReport := cleanupcore.Configure(envOr(cleanupcore.ConfigPathEnv, cleanupcore.DefaultConfigPath))
	if cleanupReport.FallbackReason != "" {
		log.Printf("警告：清理配置未生效，已 fail-closed 回退到内置演示目录 —— %s", cleanupReport)
	} else {
		log.Printf("%s", cleanupReport)
	}
	for _, rejected := range cleanupReport.Rejected {
		log.Printf("警告：清理配置中的目录 %s 未通过校验，已丢弃", rejected)
	}
	publicKey, err := loadApprovalPublicKey(envOr("KYLIN_PRIVHELPER_APPROVAL_PUB", defaultPublicKeyPath))
	if err != nil {
		return err
	}
	keyID := envOr("KYLIN_PRIVHELPER_APPROVAL_KEY_ID", defaultPublicKeyID)
	peerUID, peerGID, err := lookupPeer(envOr("KYLIN_PRIVHELPER_PEER_USER", defaultPeerUser))
	if err != nil {
		return err
	}

	// 回滚存储 root 拥有：备份文件不可被非特权进程篡改，否则回滚完整性无从保证。
	rollbackStore := envOr("KYLIN_PRIVHELPER_ROLLBACK_STORE", defaultStorePath)
	if err := os.MkdirAll(rollbackStore, 0o755); err != nil {
		return fmt.Errorf("创建回滚存储目录失败：%w", err)
	}
	policy.RollbackStore = rollbackStore

	// 已消费审批令牌的台账，root-only（0700 目录 / 0600 日志）。
	//
	// 它必须在助手重启后仍然存在：否则「一次性消费」只是进程内的说法，
	// 一次重启就会在令牌 TTL 内重开重放窗口（同一张令牌可以再封一次、再清一次）。
	// 构造失败即启动失败——带着一个写不进去的台账跑起来，等于把防重放悄悄关掉。
	ledgerDir := envOr("KYLIN_PRIVHELPER_APPROVAL_LEDGER", defaultLedgerPath)
	ledger, err := privops.NewFileReplayGuard(privops.ReplayGuardOptions{
		Dir:   ledgerDir,
		Audit: func(format string, args ...any) { log.Printf(format, args...) },
	})
	if err != nil {
		return fmt.Errorf("打开审批令牌消费台账失败：%w", err)
	}
	defer ledger.Close()

	socketPath := envOr("KYLIN_PRIVHELPER_SOCKET", privops.DefaultSocketPath)
	listener, err := listen(socketPath, peerUID, peerGID)
	if err != nil {
		return err
	}
	defer func() {
		_ = listener.Close()
		_ = os.Remove(socketPath)
	}()

	server := privops.NewServer(privops.ServerOptions{
		Policy:         policy,
		KeyResolver:    approvalv2.StaticPublicKeys{keyID: publicKey},
		AllowedPeerUID: peerUID,
		CommandTimeout: 30 * time.Second,
		ReplayGuard:    ledger,
		// 审计直接进 journal。助手的每一次判定都要留痕——它是特权边界，
		// 「拒绝了什么」和「执行了什么」同等重要。
		Audit: func(format string, args ...any) { log.Printf(format, args...) },
	})

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	log.Printf("privhelper 就绪：socket=%s 调用方uid=%d 允许服务=%v 允许作用域=%v 封禁上限=%d分钟 限速=%d次/分 回滚存储=%s 消费台账=%s",
		socketPath, peerUID, policy.AllowedUnits, policy.AllowedScopes, policy.MaxBlockMinutes, policy.MaxOpsPerMinute, rollbackStore, ledgerDir)
	return server.Serve(ctx, listener)
}

// listen 创建 socket 并把属主设为 root:<调用方>，权限 0660。
//
// 文件权限只是第一道；真正的身份判定是连接建立后的 SO_PEERCRED（见 privops.PeerUID）。
// 两者都要有：权限挡住绝大多数误连，SO_PEERCRED 保证即使权限被改错也不会放行。
func listen(socketPath string, peerUID uint32, peerGID int) (net.Listener, error) {
	// 残留的 socket 文件会让 bind 失败。只在确认它确实是 socket 时才删，
	// 避免配置写错时误删一个普通文件。
	if info, err := os.Stat(socketPath); err == nil {
		if info.Mode()&os.ModeSocket == 0 {
			return nil, fmt.Errorf("%s 已存在且不是 socket，拒绝覆盖", socketPath)
		}
		_ = os.Remove(socketPath)
	}
	listener, err := net.Listen("unix", socketPath)
	if err != nil {
		return nil, fmt.Errorf("监听 %s 失败：%w", socketPath, err)
	}
	if err := os.Chown(socketPath, 0, peerGID); err != nil {
		_ = listener.Close()
		return nil, fmt.Errorf("设置 socket 属主失败：%w", err)
	}
	if err := os.Chmod(socketPath, 0o660); err != nil {
		_ = listener.Close()
		return nil, fmt.Errorf("设置 socket 权限失败：%w", err)
	}
	return listener, nil
}

// loadPolicy 读取 root 拥有的允许清单。
//
// 刻意用极简的 key=value 而不是 JSON/YAML：这个文件决定特权边界，
// 解析器越简单，越不容易成为攻击面。
func loadPolicy(path string) (privops.Policy, error) {
	policy := privops.Policy{MaxBlockMinutes: 60, MaxOpsPerMinute: 30}
	file, err := os.Open(path)
	if err != nil {
		return policy, fmt.Errorf("读取允许清单 %s 失败：%w", path, err)
	}
	defer file.Close()

	if err := requireRootOwned(file, path, 0o022); err != nil {
		return policy, err
	}

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, found := strings.Cut(line, "=")
		if !found {
			return policy, fmt.Errorf("允许清单第 %q 行格式非法，应为 key=value", line)
		}
		key, value = strings.TrimSpace(key), strings.TrimSpace(value)
		switch key {
		case "allowed_units":
			policy.AllowedUnits = splitList(value)
		case "allowed_scopes":
			policy.AllowedScopes = splitList(value)
		case "max_block_minutes":
			policy.MaxBlockMinutes, err = strconv.Atoi(value)
		case "max_ops_per_minute":
			policy.MaxOpsPerMinute, err = strconv.Atoi(value)
		default:
			// 认不出的键一律报错而不是忽略：拼错一个键名却静默生效默认值，
			// 会让人以为限制已经配上了。
			return policy, fmt.Errorf("允许清单含未知配置项 %q", key)
		}
		if err != nil {
			return policy, fmt.Errorf("允许清单项 %s 取值非法：%w", key, err)
		}
	}
	if err := scanner.Err(); err != nil {
		return policy, fmt.Errorf("读取允许清单失败：%w", err)
	}
	if len(policy.AllowedUnits) == 0 && len(policy.AllowedScopes) == 0 {
		return policy, fmt.Errorf("允许清单为空，助手无事可做；请检查 %s", path)
	}
	return policy, nil
}

// loadApprovalPublicKey 读取 root 拥有的 SM2 审批公钥。
//
// 公钥文件必须 root 属主且他人不可写：可写就意味着能被换成攻击者的公钥，
// 助手会把伪造令牌验成真。允许他人可读无妨——公钥本就不是秘密材料。
func loadApprovalPublicKey(path string) (*ecdsa.PublicKey, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("读取审批公钥 %s 失败：%w", path, err)
	}
	defer file.Close()
	if err := requireRootOwned(file, path, 0o022); err != nil {
		return nil, err
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取审批公钥失败：%w", err)
	}
	publicKey, err := signing.ParsePublicKeyPEM(content)
	if err != nil {
		return nil, fmt.Errorf("解析审批公钥 %s 失败：%w", path, err)
	}
	return publicKey, nil
}

// requireRootOwned 校验文件属主为 root 且未开放 forbiddenBits 指定的权限位。
// 启动即失败，而不是运行时才发现边界是假的。
func requireRootOwned(file *os.File, path string, forbiddenBits os.FileMode) error {
	info, err := file.Stat()
	if err != nil {
		return fmt.Errorf("检查 %s 属性失败：%w", path, err)
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

func splitList(value string) []string {
	var items []string
	for _, item := range strings.Split(value, ",") {
		if trimmed := strings.TrimSpace(item); trimmed != "" {
			items = append(items, trimmed)
		}
	}
	return items
}

func lookupPeer(name string) (uint32, int, error) {
	account, err := user.Lookup(name)
	if err != nil {
		return 0, 0, fmt.Errorf("查找调用方用户 %s 失败：%w", name, err)
	}
	uid, err := strconv.ParseUint(account.Uid, 10, 32)
	if err != nil {
		return 0, 0, fmt.Errorf("解析 %s 的 uid 失败：%w", name, err)
	}
	if uid == 0 {
		return 0, 0, fmt.Errorf("调用方用户 %s 不能是 root", name)
	}
	gid, err := strconv.Atoi(account.Gid)
	if err != nil {
		return 0, 0, fmt.Errorf("解析 %s 的 gid 失败：%w", name, err)
	}
	return uint32(uid), gid, nil
}

func envOr(key string, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}
