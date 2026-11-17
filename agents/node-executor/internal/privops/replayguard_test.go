package privops

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// countingRunner 是并发安全的 Runner，用于并发用例（recordingRunner 的自增有竞态）。
type countingRunner struct {
	mu          sync.Mutex
	primaryRuns int
}

func (r *countingRunner) Run(_ context.Context, name string, args ...string) (string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	// 服务写操作完成后会额外执行一次 systemctl show 采集结果快照。
	// 防重放断言只统计具有副作用的主操作，不把只读快照计为第二次执行。
	if !(name == "systemctl" && len(args) > 0 && args[0] == "show") {
		r.primaryRuns++
	}
	return "ok", nil
}

func (r *countingRunner) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.primaryRuns
}

func ledgerLines(t *testing.T, dir string) int {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, ledgerFileName))
	if err != nil {
		t.Fatalf("读取台账失败：%v", err)
	}
	return bytes.Count(data, []byte("\n"))
}

// 本次修复的核心：privhelper 重启后，同一张令牌仍然必须被拒。
//
// 修复前「一次性消费」只存在于进程内的 map 里，重启即清空——在令牌 5 分钟 TTL 内，
// 同一张审批可以被执行第二次（重复封禁、重复清理）。这条用例通过「关闭台账 →
// 在同一目录上重开一本 → 换一个全新 Server」精确模拟助手重启。
func TestConsumedTokenIsStillRejectedAfterHelperRestart(t *testing.T) {
	fixture := newApprovalFixture(t)
	dir := t.TempDir()
	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)

	// 第一个生命周期：合法令牌执行一次。
	firstRunner := &recordingRunner{}
	firstLedger := fixture.ledger(t, dir)
	if got := fixture.serverWithLedger(firstRunner, firstLedger).Handle(t.Context(), request); got.Error != "" {
		t.Fatalf("首次使用不应失败：%s", got.Error)
	}
	if firstRunner.runs != 2 {
		t.Fatalf("首次应执行动作 + 状态快照，实际 %d", firstRunner.runs)
	}
	if err := firstLedger.Close(); err != nil {
		t.Fatalf("关闭台账失败：%v", err)
	}

	// 助手重启：同一目录、全新进程状态。令牌本身还在 TTL 内（时钟未推进）。
	secondRunner := &recordingRunner{}
	secondServer := fixture.serverWithLedger(secondRunner, fixture.ledger(t, dir))
	got := secondServer.Handle(t.Context(), request)
	if got.Error == "" {
		t.Fatal("重启后重放同一张令牌必须被拒绝——这正是持久化台账要关掉的窗口")
	}
	if secondRunner.runs != 0 {
		t.Fatalf("重启后重放不得执行，实际执行 %d 次", secondRunner.runs)
	}
}

// 重启后台账仍在，但**没被消费过**的令牌必须照常放行——防重放不能变成「重启后全拒」。
func TestUnconsumedTokenStillWorksAfterRestart(t *testing.T) {
	fixture := newApprovalFixture(t)
	dir := t.TempDir()

	used := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)
	first := fixture.ledger(t, dir)
	if got := fixture.serverWithLedger(&recordingRunner{}, first).Handle(t.Context(), used); got.Error != "" {
		t.Fatalf("首次使用不应失败：%s", got.Error)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}

	fresh := fixture.signedRequest("kylin.service.restart", `{"service":"business-service"}`)
	runner := &recordingRunner{}
	if got := fixture.serverWithLedger(runner, fixture.ledger(t, dir)).Handle(t.Context(), fresh); got.Error != "" {
		t.Fatalf("重启后未消费的令牌应照常放行：%s", got.Error)
	}
	if runner.runs != 2 {
		t.Fatalf("应执行动作 + 状态快照，实际 %d", runner.runs)
	}
}

// 崩溃安全：fsync 未返回就崩溃会在文件尾留下半条记录。重开时它必须被截掉，
// 且不能带坏此前的完整记录（否则一次崩溃就能抹掉一批消费记录）。
func TestTruncatedTailIsRepairedWithoutLosingEarlierRecords(t *testing.T) {
	fixture := newApprovalFixture(t)
	dir := t.TempDir()
	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)

	guard := fixture.ledger(t, dir)
	if got := fixture.serverWithLedger(&recordingRunner{}, guard).Handle(t.Context(), request); got.Error != "" {
		t.Fatalf("首次使用不应失败：%s", got.Error)
	}
	if err := guard.Close(); err != nil {
		t.Fatal(err)
	}

	// 模拟崩在写一半：追加一条没有换行符结尾的残缺记录。
	logPath := filepath.Join(dir, ledgerFileName)
	file, err := os.OpenFile(logPath, os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteString(`{"fp":"deadbeef","u":18`); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}

	runner := &recordingRunner{}
	server := fixture.serverWithLedger(runner, fixture.ledger(t, dir))
	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("残缺尾部被修复后，此前已消费的令牌仍必须被拒")
	}
	if runner.runs != 0 {
		t.Fatal("不得执行")
	}
	// 修复后追加的新记录必须能被正常解析——半条记录不能污染后续写入。
	fresh := fixture.signedRequest("kylin.service.restart", `{"service":"business-service"}`)
	if got := server.Handle(t.Context(), fresh); got.Error != "" {
		t.Fatalf("修复后新令牌应可用：%s", got.Error)
	}
}

// 保留窗口过后条目被回收，台账不会无界增长。
func TestLedgerPrunesExpiredEntriesAndStaysBounded(t *testing.T) {
	dir := t.TempDir()
	now := time.Unix(1_800_000_000, 0).UTC()
	clock := now
	guard, err := NewFileReplayGuard(ReplayGuardOptions{Dir: dir, Now: func() time.Time { return clock }})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = guard.Close() })

	// 写满 512 条，全部落在同一个保留窗口内。
	const batch = 512
	for i := range batch {
		ok, err := guard.Consume(fingerprintOf(i), clock.Add(15*time.Minute), clock)
		if err != nil || !ok {
			t.Fatalf("第 %d 条消费失败：ok=%v err=%v", i, ok, err)
		}
	}
	if got := len(guard.live); got != batch {
		t.Fatalf("窗口内应有 %d 条存活记录，实际 %d", batch, got)
	}

	// 时钟推过保留窗口后再消费一条：旧记录必须被回收，磁盘日志也必须被压缩。
	clock = clock.Add(30 * time.Minute)
	if ok, err := guard.Consume(fingerprintOf(9999), clock.Add(15*time.Minute), clock); err != nil || !ok {
		t.Fatalf("窗口外的新消费应成功：ok=%v err=%v", ok, err)
	}
	if got := len(guard.live); got != 1 {
		t.Fatalf("过期记录应被回收，存活应剩 1 条，实际 %d", got)
	}
	if lines := ledgerLines(t, dir); lines > 2 {
		t.Fatalf("磁盘日志应被压缩到与存活集合同量级，实际 %d 行", lines)
	}

	// 过期的指纹此时可以复用（其对应令牌早已过 TTL，验签阶段本就会拒），
	// 这正是台账有界的代价与依据。
	if ok, err := guard.Consume(fingerprintOf(0), clock.Add(15*time.Minute), clock); err != nil || !ok {
		t.Fatalf("已过保留窗口的指纹应可再次登记：ok=%v err=%v", ok, err)
	}
}

// 重启时也会压缩：跨生命周期的日志不会单调增长。
func TestLedgerCompactsOnOpen(t *testing.T) {
	dir := t.TempDir()
	clock := time.Unix(1_800_000_000, 0).UTC()
	guard, err := NewFileReplayGuard(ReplayGuardOptions{Dir: dir, Now: func() time.Time { return clock }})
	if err != nil {
		t.Fatal(err)
	}
	for i := range 40 {
		if _, err := guard.Consume(fingerprintOf(i), clock.Add(15*time.Minute), clock); err != nil {
			t.Fatal(err)
		}
	}
	if err := guard.Close(); err != nil {
		t.Fatal(err)
	}
	if lines := ledgerLines(t, dir); lines != 40 {
		t.Fatalf("关闭前应有 40 行，实际 %d", lines)
	}

	later := clock.Add(time.Hour)
	reopened, err := NewFileReplayGuard(ReplayGuardOptions{Dir: dir, Now: func() time.Time { return later }})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	if lines := ledgerLines(t, dir); lines != 0 {
		t.Fatalf("重开时应压缩掉全部过期记录，实际 %d 行", lines)
	}
}

// fail-closed 之一：台账写不进去时必须拒绝执行，而不是放行。
func TestUnavailableLedgerRejectsExecution(t *testing.T) {
	fixture := newApprovalFixture(t)
	dir := t.TempDir()
	guard := fixture.ledger(t, dir)
	// 关掉底层句柄，模拟写入失败（磁盘满 / IO 错 / fd 失效都会走到同一分支）。
	if err := guard.Close(); err != nil {
		t.Fatal(err)
	}

	runner := &recordingRunner{}
	server := fixture.serverWithLedger(runner, guard)
	got := server.Handle(t.Context(), fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`))
	if got.Error == "" {
		t.Fatal("台账不可用时必须拒绝执行（fail-closed），不得放行")
	}
	if runner.runs != 0 {
		t.Fatal("台账不可用时不得执行任何命令")
	}
}

// fail-closed 之二：台账目录不可写时构造即失败——助手会启动即失败，
// 而不是带着一本写不进去的台账跑起来。
func TestLedgerOpenFailsOnUnwritableDirectory(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("以 root 运行时权限位不生效，跳过")
	}
	parent := t.TempDir()
	if err := os.Chmod(parent, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(parent, 0o700) })

	if _, err := NewFileReplayGuard(ReplayGuardOptions{Dir: filepath.Join(parent, "ledger")}); err == nil {
		t.Fatal("目录不可写时必须构造失败")
	}
}

// 台账目录 0700、日志 0600：非特权进程不能读、更不能改自己的消费记录。
func TestLedgerFilesAreRootOnly(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "ledger")
	guard, err := NewFileReplayGuard(ReplayGuardOptions{Dir: dir})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = guard.Close() })

	dirInfo, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if mode := dirInfo.Mode().Perm(); mode != 0o700 {
		t.Fatalf("台账目录应为 0700，实际 %#o", mode)
	}
	fileInfo, err := os.Stat(filepath.Join(dir, ledgerFileName))
	if err != nil {
		t.Fatal(err)
	}
	if mode := fileInfo.Mode().Perm(); mode&0o077 != 0 {
		t.Fatalf("台账日志不得对 root 以外可读写，实际 %#o", mode)
	}
}

// 并发下同一张令牌只有一次能成功：查重与落盘在同一把锁内完成。
func TestConcurrentReplayAllowsExactlyOneExecution(t *testing.T) {
	fixture := newApprovalFixture(t)
	runner := &countingRunner{}
	server := fixture.serverWithLedger(runner, fixture.ledger(t, t.TempDir()))
	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)

	const workers = 32
	var wg sync.WaitGroup
	var mu sync.Mutex
	accepted := 0
	start := make(chan struct{})
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if got := server.Handle(context.Background(), request); got.Error == "" {
				mu.Lock()
				accepted++
				mu.Unlock()
			}
		}()
	}
	close(start)
	wg.Wait()

	if accepted != 1 {
		t.Fatalf("同一张令牌并发提交只应有 1 次成功，实际 %d", accepted)
	}
	if got := runner.count(); got != 1 {
		t.Fatalf("只应执行 1 次，实际 %d", got)
	}
}

// 台账层面的并发：直接打 Consume，避开助手其余各道校验。
func TestConcurrentConsumeIsSerialized(t *testing.T) {
	now := time.Unix(1_800_000_000, 0).UTC()
	guard, err := NewFileReplayGuard(ReplayGuardOptions{Dir: t.TempDir(), Now: func() time.Time { return now }})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = guard.Close() })

	const workers = 64
	var wg sync.WaitGroup
	var mu sync.Mutex
	accepted := 0
	start := make(chan struct{})
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			ok, err := guard.Consume("same-fingerprint", now.Add(15*time.Minute), now)
			if err != nil {
				t.Errorf("并发消费不应报错：%v", err)
				return
			}
			if ok {
				mu.Lock()
				accepted++
				mu.Unlock()
			}
		}()
	}
	close(start)
	wg.Wait()

	if accepted != 1 {
		t.Fatalf("同一指纹并发消费只应有 1 次成功，实际 %d", accepted)
	}
}

// 台账被外部塞入无法解析的内容时，既不能崩，也不能因此放过已消费的令牌。
func TestCorruptLedgerLinesAreIgnoredWithoutLosingValidRecords(t *testing.T) {
	fixture := newApprovalFixture(t)
	dir := t.TempDir()
	request := fixture.signedRequest("kylin.service.restart", `{"service":"nginx"}`)

	guard := fixture.ledger(t, dir)
	if got := fixture.serverWithLedger(&recordingRunner{}, guard).Handle(t.Context(), request); got.Error != "" {
		t.Fatalf("首次使用不应失败：%s", got.Error)
	}
	if err := guard.Close(); err != nil {
		t.Fatal(err)
	}

	logPath := filepath.Join(dir, ledgerFileName)
	original, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(logPath, append([]byte("not json at all\n"), original...), 0o600); err != nil {
		t.Fatal(err)
	}

	runner := &recordingRunner{}
	server := fixture.serverWithLedger(runner, fixture.ledger(t, dir))
	if got := server.Handle(t.Context(), request); got.Error == "" {
		t.Fatal("台账含垃圾行时，已消费令牌仍必须被拒")
	}
	if runner.runs != 0 {
		t.Fatal("不得执行")
	}
}

func fingerprintOf(i int) string {
	const digits = "0123456789abcdef"
	buf := make([]byte, 8)
	for pos := range buf {
		buf[pos] = digits[(i>>(4*pos))&0xf]
	}
	return string(buf)
}
