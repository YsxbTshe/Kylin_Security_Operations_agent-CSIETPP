package privops

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// ReplayGuard 是审批令牌「一次性消费」的台账。
//
// 它只回答一个问题：这枚指纹此前是否已经被消费过。判定必须在**执行之前**完成，
// 且一旦返回 true（允许执行），该事实必须已经落盘——否则助手在执行途中崩溃再重启，
// 同一张令牌就能在 TTL 内被再用一次。
type ReplayGuard interface {
	// Consume 登记 fingerprint 的消费。retainUntil 之前该指纹必须一直被记住
	// （台账可以在此之后遗忘它——令牌那时早已过期，验签阶段就会拒绝）。
	//
	// 返回值语义是刻意三分的：
	//   (true, nil)   —— 首次消费，已持久化，可以执行；
	//   (false, nil)  —— 重放，拒绝；
	//   (false, err)  —— 台账不可用（磁盘满、权限错、fd 失效）。调用方**必须拒绝执行**。
	//                    绝不允许因为写不进台账就退回内存模式放行：那等于攻击者
	//                    只要把台账写坏就能重开重放窗口。
	Consume(fingerprint string, retainUntil time.Time, now time.Time) (bool, error)
}

// ledgerRecord 是台账里的一条消费记录。字段名压到最短：这个文件的每条记录都要
// 单独 fsync，写入量直接落在特权写操作的关键路径上。
type ledgerRecord struct {
	Fingerprint string `json:"fp"`
	RetainUntil int64  `json:"u"` // Unix 秒
}

const (
	// ledgerFileName 是追加日志的文件名。压缩时的临时文件与它同目录，保证 rename 原子。
	ledgerFileName = "consumed.log"
	// ledgerTempName 是压缩用的临时文件名。固定名字（而非随机名）便于崩溃后被识别与覆盖。
	ledgerTempName = "consumed.log.tmp"

	// ledgerCompactFloor / ledgerCompactRatio 控制压缩触发点：日志条数既要超过下限，
	// 又要达到存活条数的若干倍，才值得做一次全量重写。两个条件都要满足，避免
	// 「存活条数本来就多」时反复压缩。
	ledgerCompactFloor = 64
	ledgerCompactRatio = 4
)

// FileReplayGuard 用「追加日志 + 定期原子压缩」持久化已消费令牌的指纹。
//
// # 为什么选这个方案
//
// 候选有三：单文件 JSON 原子替换、SQLite、追加日志 + 压缩。选第三个，理由：
//
//   - **单文件原子替换**在每次消费时都要重写全量、fsync、rename、再 fsync 目录。
//     它落在每一次特权写操作的前置路径上，代价随台账条数线性增长，而这条路径
//     恰恰是不能慢的（用户在等 systemctl restart 的结果）。
//   - **SQLite** 要么引入 CGO，要么引入一个几万行的纯 Go 实现——而这个进程是全系统
//     唯一的 root 进程，ADR-002 的整个论证建立在「它足够小、可以逐行看完」之上。
//     为了一张最多几百行的去重表引入一个数据库，是拿攻击面换不需要的功能。
//   - **追加日志**每次消费只写一条几十字节的记录 + 一次 fsync，代价与台账大小无关；
//     无界增长的问题用「按保留窗口压缩」解决，而压缩不在关键路径上（可以失败、
//     可以推迟，不影响正确性）。
//
// # 崩溃安全
//
// 写入顺序是「先记消费、fsync、再执行」。这给出的保证是：
//
//   - fsync 返回后崩溃 → 记录已落盘，重启后该令牌被拒。可能的代价是操作实际没执行
//     而令牌已作废——这是刻意选的方向：宁可让运维重新发起一次审批，也不能让一次
//     封禁/清理被悄悄执行两遍。
//   - fsync 返回前崩溃 → Consume 从未返回，因此**执行从未发生**。此时文件尾部可能
//     残留半条记录；打开时会把最后一个换行符之后的残缺字节截掉。丢弃这条残缺记录
//     是安全的，因为它对应的操作根本没执行过。
//   - 压缩过程中崩溃 → 旧日志原封不动（临时文件写在别处，rename 才是切换点），
//     重启后从旧日志恢复，只是多留了一些本可回收的记录。
//
// # 并发
//
// 「查重 + 追加 + fsync」整体在同一把互斥锁内完成，因此同一张令牌被并发提交时
// 只有一个能拿到 true。fsync 被串行化是有意的：特权操作本身就有每分钟次数上限
// （Policy.MaxOpsPerMinute，默认 30），把它们排成队不构成瓶颈。
//
// # 有界性
//
// 存活集合 = 保留窗口（令牌 TTL 5 分钟 + 宽限 10 分钟）内的消费记录。在默认限速下
// 上限约为 15 × 30 = 450 条 × 约 60 字节 ≈ 27 KB；压缩把磁盘上的日志控制在存活集合的
// 常数倍以内。
type FileReplayGuard struct {
	mu      sync.Mutex
	dir     string
	logPath string
	file    *os.File
	// live 是内存中的存活集合，来源是启动时重放日志 + 运行期追加。它只是磁盘台账的
	// 缓存：判定以它为准，但每条记录都先落盘才进入它。
	live map[string]time.Time
	// onDisk 是当前日志文件里的记录条数（含已过期、等待压缩回收的），用于触发压缩。
	onDisk int
	audit  func(format string, args ...any)
}

// ReplayGuardOptions 是 FileReplayGuard 的构造参数。
type ReplayGuardOptions struct {
	// Dir 是台账目录。必须只有 root 可读写：非特权进程若能改它，就能删掉自己的
	// 消费记录，防重放形同虚设。
	Dir string
	// Now 供测试注入时钟；缺省 time.Now。
	Now func() time.Time
	// Audit 记录压缩失败之类不影响正确性、但运维需要知道的事件。
	Audit func(format string, args ...any)
}

// NewFileReplayGuard 打开（必要时创建）台账目录与日志，重放存活记录并做一次压缩。
//
// 任何一步失败都返回错误：助手必须启动即失败，而不是带着一个写不进去的台账跑起来。
func NewFileReplayGuard(options ReplayGuardOptions) (*FileReplayGuard, error) {
	dir := options.Dir
	if dir == "" {
		return nil, fmt.Errorf("消费台账目录不能为空")
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}
	audit := options.Audit
	if audit == nil {
		audit = func(string, ...any) {}
	}

	// 0700：台账只对 root 有意义，非特权进程连目录都不该能列。MkdirAll 受 umask 影响，
	// 显式收紧一次。
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("创建消费台账目录 %s 失败：%w", dir, err)
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return nil, fmt.Errorf("收紧消费台账目录 %s 权限失败：%w", dir, err)
	}

	guard := &FileReplayGuard{
		dir:     dir,
		logPath: filepath.Join(dir, ledgerFileName),
		live:    make(map[string]time.Time),
		audit:   audit,
	}
	// 上一轮压缩若崩在中途会留下临时文件。它从来不是权威数据（rename 才是切换点），
	// 直接清掉，避免下次压缩误以为目录不干净。
	_ = os.Remove(filepath.Join(dir, ledgerTempName))

	if err := guard.repairTail(); err != nil {
		return nil, err
	}
	if err := guard.load(now()); err != nil {
		return nil, err
	}
	// 启动即压缩一次：把上一个生命周期里已过期的记录清掉，日志不会跨重启单调增长。
	if err := guard.compactLocked(); err != nil {
		return nil, err
	}
	return guard, nil
}

// repairTail 截掉最后一个换行符之后的残缺字节。
//
// 只有「fsync 未返回就崩溃」会留下残缺记录，而那意味着对应的特权操作从未执行，
// 因此丢弃它不会放过任何真正发生过的消费。不修的话，下一次 O_APPEND 写入会
// 拼接到半条记录后面，把两条记录一起毁掉。
func (g *FileReplayGuard) repairTail() error {
	info, err := os.Stat(g.logPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return fmt.Errorf("检查消费台账 %s 失败：%w", g.logPath, err)
	}
	if info.Size() == 0 {
		return nil
	}
	data, err := os.ReadFile(g.logPath)
	if err != nil {
		return fmt.Errorf("读取消费台账 %s 失败：%w", g.logPath, err)
	}
	if data[len(data)-1] == '\n' {
		return nil
	}
	keep := int64(bytes.LastIndexByte(data, '\n') + 1) // 无换行符时为 0，整个文件都是残缺的
	if err := os.Truncate(g.logPath, keep); err != nil {
		return fmt.Errorf("修复消费台账 %s 尾部失败：%w", g.logPath, err)
	}
	g.audit("消费台账 %s 尾部有 %d 字节残缺记录，已截断（对应操作未执行）", g.logPath, int64(len(data))-keep)
	return nil
}

// load 重放日志，把仍在保留窗口内的记录装进内存，并打开追加句柄。
func (g *FileReplayGuard) load(now time.Time) error {
	file, err := os.Open(g.logPath)
	switch {
	case err == nil:
		defer file.Close()
		scanner := bufio.NewScanner(file)
		scanner.Buffer(make([]byte, 0, 4096), 64*1024)
		for scanner.Scan() {
			line := bytes.TrimSpace(scanner.Bytes())
			if len(line) == 0 {
				continue
			}
			var record ledgerRecord
			if err := json.Unmarshal(line, &record); err != nil || record.Fingerprint == "" {
				// 认不出的行只可能来自外部篡改或介质损坏。不能当作「没有记录」放行，
				// 但也没有指纹可比对——保守起见跳过，压缩时它会被丢掉，
				// 同时留一条审计说明台账被动过。
				g.audit("消费台账 %s 存在无法解析的记录，已忽略", g.logPath)
				continue
			}
			until := time.Unix(record.RetainUntil, 0).UTC()
			if !until.After(now) {
				continue // 已过保留窗口：令牌本身早已过期，验签阶段就会拒
			}
			if existing, ok := g.live[record.Fingerprint]; !ok || until.After(existing) {
				g.live[record.Fingerprint] = until
			}
		}
		if err := scanner.Err(); err != nil {
			return fmt.Errorf("重放消费台账 %s 失败：%w", g.logPath, err)
		}
	case os.IsNotExist(err):
		// 首次启动，没有台账。
	default:
		return fmt.Errorf("打开消费台账 %s 失败：%w", g.logPath, err)
	}
	return nil
}

// Consume 实现 ReplayGuard。查重、追加、fsync 全程持锁，保证并发下同一指纹只有一次成功。
func (g *FileReplayGuard) Consume(fingerprint string, retainUntil time.Time, now time.Time) (bool, error) {
	g.mu.Lock()
	defer g.mu.Unlock()

	if g.file == nil {
		return false, fmt.Errorf("消费台账未打开或已关闭")
	}
	g.pruneLocked(now)
	if _, used := g.live[fingerprint]; used {
		return false, nil
	}
	// 先落盘再登记内存：反过来会出现「内存说已消费、磁盘没有」的窗口，重启即漏。
	if err := g.appendLocked(ledgerRecord{Fingerprint: fingerprint, RetainUntil: retainUntil.Unix()}); err != nil {
		return false, err
	}
	g.live[fingerprint] = retainUntil
	g.onDisk++

	// 压缩不在正确性路径上：失败只意味着日志暂时偏大，本次消费已经落盘且有效。
	if g.onDisk >= ledgerCompactFloor && g.onDisk >= ledgerCompactRatio*len(g.live) {
		if err := g.compactLocked(); err != nil {
			g.audit("压缩消费台账失败（不影响本次判定，日志将继续增长）：%v", err)
		}
	}
	return true, nil
}

// appendLocked 追加一条记录并 fsync。返回 nil 即表示这条消费已经durable。
func (g *FileReplayGuard) appendLocked(record ledgerRecord) error {
	encoded, err := json.Marshal(record)
	if err != nil {
		return fmt.Errorf("编码消费记录失败：%w", err)
	}
	encoded = append(encoded, '\n')
	// 单次 write：记录只有几十字节，O_APPEND 下不会与并发写交错（本进程内还有锁，
	// 跨进程由 systemd 保证只有一个助手实例）。
	if _, err := g.file.Write(encoded); err != nil {
		return fmt.Errorf("写入消费台账失败：%w", err)
	}
	if err := g.file.Sync(); err != nil {
		return fmt.Errorf("刷盘消费台账失败：%w", err)
	}
	return nil
}

// pruneLocked 丢弃已过保留窗口的内存记录。磁盘上的对应记录由压缩回收。
func (g *FileReplayGuard) pruneLocked(now time.Time) {
	for fingerprint, until := range g.live {
		if !until.After(now) {
			delete(g.live, fingerprint)
		}
	}
}

// compactLocked 把存活集合原子地重写成新日志：临时文件 → fsync → rename → fsync 目录。
//
// rename 是唯一的切换点，因此压缩崩在任何一步都不会损坏权威数据：要么还是旧日志，
// 要么已经是完整的新日志，不存在中间态。
func (g *FileReplayGuard) compactLocked() error {
	tempPath := filepath.Join(g.dir, ledgerTempName)
	// 0600：台账内容只有 root 该看到。
	temp, err := os.OpenFile(tempPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return fmt.Errorf("创建消费台账临时文件失败：%w", err)
	}
	writer := bufio.NewWriter(temp)
	for fingerprint, until := range g.live {
		encoded, err := json.Marshal(ledgerRecord{Fingerprint: fingerprint, RetainUntil: until.Unix()})
		if err == nil {
			_, err = writer.Write(append(encoded, '\n'))
		}
		if err != nil {
			temp.Close()
			_ = os.Remove(tempPath)
			return fmt.Errorf("写入消费台账临时文件失败：%w", err)
		}
	}
	if err := writer.Flush(); err != nil {
		temp.Close()
		_ = os.Remove(tempPath)
		return fmt.Errorf("刷新消费台账临时文件失败：%w", err)
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		_ = os.Remove(tempPath)
		return fmt.Errorf("刷盘消费台账临时文件失败：%w", err)
	}
	if err := temp.Close(); err != nil {
		_ = os.Remove(tempPath)
		return fmt.Errorf("关闭消费台账临时文件失败：%w", err)
	}
	if err := os.Rename(tempPath, g.logPath); err != nil {
		_ = os.Remove(tempPath)
		return fmt.Errorf("替换消费台账失败：%w", err)
	}
	syncDir(g.dir) // best-effort：让 rename 本身尽快落盘

	// 切换句柄。旧句柄指向的 inode 已被 rename 覆盖，继续往里写等于写进一个
	// 无人读取的孤儿文件——那才是真正会漏掉重放的错误，所以重开失败必须
	// 把 file 置空，让后续 Consume 一律 fail-closed。
	if g.file != nil {
		_ = g.file.Close()
		g.file = nil
	}
	file, err := os.OpenFile(g.logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return fmt.Errorf("重开消费台账失败：%w", err)
	}
	g.file = file
	g.onDisk = len(g.live)
	return nil
}

// Close 关闭台账。关闭后 Consume 一律返回错误（fail-closed）。
func (g *FileReplayGuard) Close() error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.file == nil {
		return nil
	}
	err := g.file.Close()
	g.file = nil
	return err
}

// syncDir 尽力让目录项本身落盘。部分文件系统不支持对目录 fsync，失败不作为致命错误：
// 单条记录的持久性由文件自身的 fsync 保证，目录 fsync 只影响「文件刚被创建/替换」
// 这一瞬间的极端崩溃场景。
func syncDir(dir string) {
	handle, err := os.Open(dir)
	if err != nil {
		return
	}
	defer handle.Close()
	_ = handle.Sync()
}

// memoryReplayGuard 是**不持久化**的台账，只在进程生命周期内有效。
//
// 它的唯一用途是进程内测试与对抗基准（internal/advbench 在进程内直连 Server，
// 不落地任何文件）。部署形态的助手绝不能用它：cmd/privhelper 总是构造
// FileReplayGuard，构造失败即启动失败。NewServer 在未提供台账时会审计一条告警，
// 使这种降级不可能悄无声息地发生。
type memoryReplayGuard struct {
	mu   sync.Mutex
	live map[string]time.Time
}

func newMemoryReplayGuard() *memoryReplayGuard {
	return &memoryReplayGuard{live: make(map[string]time.Time)}
}

func (g *memoryReplayGuard) Consume(fingerprint string, retainUntil time.Time, now time.Time) (bool, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	for existing, until := range g.live {
		if !until.After(now) {
			delete(g.live, existing)
		}
	}
	if _, used := g.live[fingerprint]; used {
		return false, nil
	}
	g.live[fingerprint] = retainUntil
	return true, nil
}
