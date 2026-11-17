package audit

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/protocol"
)

func readLines(t *testing.T, path string) []string {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("open audit file: %v", err)
	}
	defer f.Close()
	var lines []string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("scan: %v", err)
	}
	return lines
}

func TestAuditorWritesNDJSONWithAllFields(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "audit.ndjson")
	a, err := New(path)
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	entry := Entry{
		Time:        "2026-02-10T15:04:05Z",
		Phase:       PhaseEnd,
		RequestID:   "req-1",
		TenantID:    "t1",
		AgentID:     "a1",
		WorkspaceID: "ws-1",
		Action:      protocol.ActionExec,
		ArgsDigest:  `{"command":["ls"]}`,
		ExitCode:    2,
		DurationMS:  42,
		Result:      ResultError,
		Error:       "boom",
	}
	if err := a.Write(entry); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := a.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	lines := readLines(t, path)
	if len(lines) != 1 {
		t.Fatalf("expected 1 line, got %d", len(lines))
	}
	var got Entry
	if err := json.Unmarshal([]byte(lines[0]), &got); err != nil {
		t.Fatalf("line is not valid JSON: %v", err)
	}
	if got != entry {
		t.Fatalf("roundtrip mismatch:\n got %+v\nwant %+v", got, entry)
	}
}

func TestAuditorConcurrentWritesStayLineAtomic(t *testing.T) {
	path := filepath.Join(t.TempDir(), "audit.ndjson")
	a, err := New(path)
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	defer a.Close()

	const goroutines, perG = 8, 25
	var wg sync.WaitGroup
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < perG; i++ {
				_ = a.Write(Entry{Phase: PhaseEnd, RequestID: "req", Action: protocol.ActionExec, Result: ResultOK})
			}
		}(g)
	}
	wg.Wait()

	lines := readLines(t, path)
	if len(lines) != goroutines*perG {
		t.Fatalf("expected %d lines, got %d", goroutines*perG, len(lines))
	}
	for i, line := range lines {
		var e Entry
		if err := json.Unmarshal([]byte(line), &e); err != nil {
			t.Fatalf("line %d corrupted: %v", i, err)
		}
	}
}

func TestDigestArgumentsHashesSensitiveAndLargeValues(t *testing.T) {
	secret := "TOPSECRET-PAYLOAD-DO-NOT-LEAK"
	digest := DigestArguments(map[string]any{
		"path":    "report.md",
		"content": secret,
		"script":  "short",
	})
	if strings.Contains(digest, secret) {
		t.Fatal("large/sensitive value leaked into digest")
	}
	if strings.Contains(digest, "short") {
		t.Fatal("sensitive key must be hashed even when short")
	}
	if !strings.Contains(digest, "sha256:") {
		t.Fatalf("digest missing sha256 marker: %s", digest)
	}
	if !strings.Contains(digest, "report.md") {
		t.Fatalf("small non-sensitive value should stay plaintext: %s", digest)
	}
}

func TestDigestArgumentsHashesLongPlainValues(t *testing.T) {
	long := strings.Repeat("x", maxValueBytes+1)
	digest := DigestArguments(map[string]any{"blob": long})
	if strings.Contains(digest, long) {
		t.Fatal("oversized value leaked into digest")
	}
	if !strings.Contains(digest, "sha256:") {
		t.Fatalf("expected hash marker: %s", digest)
	}
}

func TestDigestArgumentsCapsTotalLength(t *testing.T) {
	args := make(map[string]any)
	for i := 0; i < 64; i++ {
		args[string(rune('a'+i%26))+string(rune('0'+i/26))] = strings.Repeat("v", 200)
	}
	digest := DigestArguments(args)
	if len(digest) > maxDigestBytes+3 {
		t.Fatalf("digest too long: %d", len(digest))
	}
}

func TestDigestArgumentsEmpty(t *testing.T) {
	if got := DigestArguments(nil); got != "" {
		t.Fatalf("expected empty digest, got %q", got)
	}
}

func TestMiddlewareWritesStartAndEndEntries(t *testing.T) {
	path := filepath.Join(t.TempDir(), "audit.ndjson")
	a, err := New(path)
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	defer a.Close()

	stub := protocol.HandlerFunc(func(_ context.Context, _ protocol.Request) protocol.Response {
		return protocol.Response{Output: "done", ExitCode: 3}
	})
	h := NewMiddleware(stub, a)

	resp := h.Handle(context.Background(), protocol.Request{
		RequestID:   "req-9",
		TenantID:    "t1",
		AgentID:     "a1",
		WorkspaceID: "ws-1",
		Action:      protocol.ActionExec,
		Arguments:   map[string]any{"command": []any{"ls", "-la"}},
	})
	if resp.Output != "done" {
		t.Fatalf("middleware must pass response through: %+v", resp)
	}

	lines := readLines(t, path)
	if len(lines) != 2 {
		t.Fatalf("expected 2 entries, got %d", len(lines))
	}
	var start, end Entry
	if err := json.Unmarshal([]byte(lines[0]), &start); err != nil {
		t.Fatalf("start entry invalid: %v", err)
	}
	if err := json.Unmarshal([]byte(lines[1]), &end); err != nil {
		t.Fatalf("end entry invalid: %v", err)
	}
	if start.Phase != PhaseStart || start.ArgsDigest == "" || start.RequestID != "req-9" {
		t.Fatalf("start entry incomplete: %+v", start)
	}
	if end.Phase != PhaseEnd || end.ExitCode != 3 || end.Result != ResultOK || end.DurationMS < 0 {
		t.Fatalf("end entry incomplete: %+v", end)
	}
}

func TestMiddlewareRecordsErrorResult(t *testing.T) {
	path := filepath.Join(t.TempDir(), "audit.ndjson")
	a, err := New(path)
	if err != nil {
		t.Fatalf("new: %v", err)
	}
	defer a.Close()

	stub := protocol.HandlerFunc(func(_ context.Context, _ protocol.Request) protocol.Response {
		return protocol.Response{Error: "denied"}
	})
	_ = NewMiddleware(stub, a).Handle(context.Background(), protocol.Request{Action: protocol.ActionExec})

	lines := readLines(t, path)
	var end Entry
	if err := json.Unmarshal([]byte(lines[len(lines)-1]), &end); err != nil {
		t.Fatalf("end entry invalid: %v", err)
	}
	if end.Result != ResultError || end.Error != "denied" {
		t.Fatalf("expected error result, got %+v", end)
	}
}
