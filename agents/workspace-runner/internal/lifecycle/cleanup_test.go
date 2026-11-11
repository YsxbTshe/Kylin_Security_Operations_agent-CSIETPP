package lifecycle

import (
	"context"
	"errors"
	"reflect"
	"testing"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/protocol"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

func testCfg() sandbox.ContainerConfig {
	return sandbox.ContainerConfig{TenantID: "t1", AgentID: "a1", WorkspaceID: "ws-123"}
}

func TestEndWorkspaceStopsAndRemoves(t *testing.T) {
	fake := newFakeClient()
	c := NewCleaner(fake, RetentionPolicy{}, nil)

	if err := c.EndWorkspace(context.Background(), testCfg()); err != nil {
		t.Fatalf("end: %v", err)
	}
	name := testCfg().ContainerName()
	if !reflect.DeepEqual(fake.stopped, []string{name}) {
		t.Fatalf("stopped = %v", fake.stopped)
	}
	if !reflect.DeepEqual(fake.removed, []string{name}) {
		t.Fatalf("removed = %v", fake.removed)
	}
}

func TestEndWorkspaceToleratesStopFailure(t *testing.T) {
	fake := newFakeClient()
	fake.stopErr = errors.New("already stopped")
	c := NewCleaner(fake, RetentionPolicy{}, nil)

	if err := c.EndWorkspace(context.Background(), testCfg()); err != nil {
		t.Fatalf("stop failure must not block removal: %v", err)
	}
	if len(fake.removed) != 1 {
		t.Fatalf("removed = %v", fake.removed)
	}
}

func TestEndWorkspaceRemoveFailure(t *testing.T) {
	fake := newFakeClient()
	fake.removeErr = errors.New("busy")
	c := NewCleaner(fake, RetentionPolicy{}, nil)

	if err := c.EndWorkspace(context.Background(), testCfg()); err == nil {
		t.Fatal("expected error when removal fails")
	}
}

func snapshotAt(repo string, day int) sandbox.ImageInfo {
	return sandbox.ImageInfo{
		Name:      SnapshotImagePrefix + repo + ":" + time.Date(2026, 2, day, 0, 0, 0, 0, time.UTC).Format("20060102-150405"),
		CreatedAt: time.Date(2026, 2, day, 0, 0, 0, 0, time.UTC),
	}
}

func TestPruneSnapshotsKeepsNewestN(t *testing.T) {
	fake := newFakeClient()
	fake.images = []sandbox.ImageInfo{
		snapshotAt("t1-a1-ws1", 1),
		snapshotAt("t1-a1-ws1", 2),
		snapshotAt("t1-a1-ws1", 3),
		snapshotAt("t1-a1-ws1", 4),
	}
	c := NewCleaner(fake, RetentionPolicy{Keep: 2}, nil)

	removed, err := c.PruneSnapshots(context.Background(), time.Date(2026, 2, 5, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 2 {
		t.Fatalf("removed = %d, want 2", removed)
	}
	if len(fake.removedImages) != 2 {
		t.Fatalf("removedImages = %v", fake.removedImages)
	}
	for _, img := range fake.removedImages {
		// 被删除的必须是较旧的两个。
		if img != snapshotAt("t1-a1-ws1", 1).Name && img != snapshotAt("t1-a1-ws1", 2).Name {
			t.Fatalf("unexpected image removed: %s", img)
		}
	}
}

func TestPruneSnapshotsByMaxAge(t *testing.T) {
	fake := newFakeClient()
	fake.images = []sandbox.ImageInfo{
		snapshotAt("t1-a1-ws1", 1),
		snapshotAt("t1-a1-ws1", 4),
	}
	c := NewCleaner(fake, RetentionPolicy{Keep: 10, MaxAge: 48 * time.Hour}, nil)

	removed, err := c.PruneSnapshots(context.Background(), time.Date(2026, 2, 5, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 1 {
		t.Fatalf("removed = %d, want 1", removed)
	}
	if fake.removedImages[0] != snapshotAt("t1-a1-ws1", 1).Name {
		t.Fatalf("removedImages = %v", fake.removedImages)
	}
}

func TestPruneSnapshotsGroupsByWorkspace(t *testing.T) {
	fake := newFakeClient()
	fake.images = []sandbox.ImageInfo{
		snapshotAt("t1-a1-ws1", 1),
		snapshotAt("t1-a1-ws1", 2),
		snapshotAt("t1-a1-ws2", 1),
		snapshotAt("t1-a1-ws2", 2),
	}
	c := NewCleaner(fake, RetentionPolicy{Keep: 1}, nil)

	removed, err := c.PruneSnapshots(context.Background(), time.Date(2026, 2, 5, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	// 每个工作区各删一个旧快照，而不是全局只留一个。
	if removed != 2 {
		t.Fatalf("removed = %d, want 2", removed)
	}
}

func TestPruneSnapshotsIgnoresForeignImages(t *testing.T) {
	fake := newFakeClient()
	fake.images = []sandbox.ImageInfo{
		{Name: "docker.io/library/alpine:latest", CreatedAt: time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)},
	}
	c := NewCleaner(fake, RetentionPolicy{Keep: 1, MaxAge: time.Hour}, nil)

	removed, err := c.PruneSnapshots(context.Background(), time.Date(2026, 2, 5, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("prune: %v", err)
	}
	if removed != 0 || len(fake.removedImages) != 0 {
		t.Fatalf("foreign images must not be touched: removed=%d imgs=%v", removed, fake.removedImages)
	}
}

func TestShutdownRemovesAllContainers(t *testing.T) {
	fake := newFakeClient()
	fake.states["kylin-ws-t-a-one"] = sandbox.StateRunning
	fake.states["kylin-ws-t-a-two"] = sandbox.StateStopped
	c := NewCleaner(fake, RetentionPolicy{}, nil)

	cleaned, err := c.Shutdown(context.Background())
	if err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	if cleaned != 2 || len(fake.removed) != 2 {
		t.Fatalf("cleaned = %d, removed = %v", cleaned, fake.removed)
	}
}

func TestReaperDueDetectsIdleContainers(t *testing.T) {
	fake := newFakeClient()
	r := NewReaper(NewCleaner(fake, RetentionPolicy{}, nil), 30*time.Minute, time.Minute, nil)

	now := time.Date(2026, 2, 10, 12, 0, 0, 0, time.UTC)
	r.mu.Lock()
	r.lastActive["kylin-ws-t-a-idle"] = now.Add(-31 * time.Minute)
	r.lastActive["kylin-ws-t-a-active"] = now.Add(-5 * time.Minute)
	r.mu.Unlock()

	due := r.due(now)
	if !reflect.DeepEqual(due, []string{"kylin-ws-t-a-idle"}) {
		t.Fatalf("due = %v", due)
	}
}

func TestActivityTrackerTouchesAndDelegates(t *testing.T) {
	fake := newFakeClient()
	r := NewReaper(NewCleaner(fake, RetentionPolicy{}, nil), 30*time.Minute, time.Minute, nil)

	called := false
	stub := protocol.HandlerFunc(func(_ context.Context, req protocol.Request) protocol.Response {
		called = true
		return protocol.Response{Output: "ok"}
	})
	tracker := NewActivityTracker(stub, r)

	req := protocol.Request{TenantID: "t1", AgentID: "a1", WorkspaceID: "ws-123", Action: protocol.ActionExec}
	resp := tracker.Handle(context.Background(), req)
	if !called || resp.Output != "ok" {
		t.Fatalf("inner handler not delegated: called=%v resp=%+v", called, resp)
	}
	name := sandbox.ContainerConfig{TenantID: "t1", AgentID: "a1", WorkspaceID: "ws-123"}.ContainerName()
	r.mu.Lock()
	_, ok := r.lastActive[name]
	r.mu.Unlock()
	if !ok {
		t.Fatalf("container %s not touched", name)
	}
}
