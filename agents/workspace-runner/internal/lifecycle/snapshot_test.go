package lifecycle

import (
	"context"
	"testing"
	"time"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

func TestSnapshotImageName(t *testing.T) {
	at := time.Date(2026, 2, 10, 15, 4, 5, 0, time.UTC)
	name := SnapshotImageName("org-alpha", "agent-01", "ws-abc123def456", at)
	want := "kylin-ws-snap-org-alpha-agent-01-ws-abc123def:20260210-150405"
	if name != want {
		t.Fatalf("name = %q, want %q", name, want)
	}
}

func TestSnapshotImageNameSanitizes(t *testing.T) {
	at := time.Date(2026, 2, 10, 15, 4, 5, 0, time.FixedZone("", 8*3600))
	name := SnapshotImageName("ORG/ALPHA", "agent@01", "ws123", at)
	// 仓库名部分只允许小写字母数字与连字符；标签是 UTC 时间戳。
	want := "kylin-ws-snap-org-alpha-agent-01-ws123:20260210-070405"
	if name != want {
		t.Fatalf("name = %q, want %q", name, want)
	}
}

func TestSnapshotterTakeCommitsContainer(t *testing.T) {
	fake := newFakeClient()
	s := NewSnapshotter(fake, nil)
	at := time.Date(2026, 2, 10, 15, 4, 5, 0, time.UTC)
	cfg := sandbox.ContainerConfig{TenantID: "t1", AgentID: "a1", WorkspaceID: "ws-abcdef123456"}

	image, err := s.Take(context.Background(), cfg, at)
	if err != nil {
		t.Fatalf("take: %v", err)
	}
	wantImage := "kylin-ws-snap-t1-a1-ws-abcdef123:20260210-150405"
	if image != wantImage {
		t.Fatalf("image = %q, want %q", image, wantImage)
	}
	wantContainer := sandbox.ContainerConfig{TenantID: "t1", AgentID: "a1", WorkspaceID: "ws-abcdef123456"}.ContainerName()
	if len(fake.commitCalls) != 1 || fake.commitCalls[0] != [2]string{wantContainer, wantImage} {
		t.Fatalf("commit calls = %v", fake.commitCalls)
	}
}

func TestSnapshotterListFiltersAndSorts(t *testing.T) {
	fake := newFakeClient()
	t1 := time.Date(2026, 2, 10, 1, 0, 0, 0, time.UTC)
	t2 := time.Date(2026, 2, 10, 2, 0, 0, 0, time.UTC)
	fake.images = []sandbox.ImageInfo{
		{Name: SnapshotImagePrefix + "t1-a1-ws1:20260210-020000", CreatedAt: t2},
		{Name: "docker.io/library/alpine:latest", CreatedAt: t1},
		{Name: SnapshotImagePrefix + "t1-a1-ws1:20260210-010000", CreatedAt: t1},
	}

	got, err := NewSnapshotter(fake, nil).List(context.Background())
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(got) != 2 {
		t.Fatalf("expected 2 snapshots, got %d: %+v", len(got), got)
	}
	// 必须按创建时间从旧到新排序，供保留策略从旧到新删除。
	if !got[0].CreatedAt.Before(got[1].CreatedAt) {
		t.Fatalf("snapshots not sorted oldest first: %+v", got)
	}
}

func TestSnapshotterRemove(t *testing.T) {
	fake := newFakeClient()
	s := NewSnapshotter(fake, nil)
	if err := s.Remove(context.Background(), SnapshotImagePrefix+"t1-a1-ws1:20260210-010000"); err != nil {
		t.Fatalf("remove: %v", err)
	}
	if len(fake.removedImages) != 1 {
		t.Fatalf("removedImages = %v", fake.removedImages)
	}
}
