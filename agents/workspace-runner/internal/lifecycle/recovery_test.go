package lifecycle

import (
	"context"
	"errors"
	"reflect"
	"sort"
	"testing"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner/internal/sandbox"
)

// fakeClient 是 PodmanClient 的内存实现，记录所有变更调用。
type fakeClient struct {
	states map[string]sandbox.ContainerState
	images []sandbox.ImageInfo

	started        []string
	stopped        []string
	removed        []string
	removedImages  []string
	commitCalls    [][2]string // [container, image]
	listErr        error
	inspectErr     error
	startErr       error
	stopErr        error
	removeErr      error
	removeImageErr error
}

func newFakeClient() *fakeClient {
	return &fakeClient{states: map[string]sandbox.ContainerState{}}
}

func (f *fakeClient) ListContainers(_ context.Context) ([]string, error) {
	if f.listErr != nil {
		return nil, f.listErr
	}
	names := make([]string, 0, len(f.states))
	for name := range f.states {
		names = append(names, name)
	}
	sort.Strings(names)
	return names, nil
}

func (f *fakeClient) Inspect(_ context.Context, name string) (sandbox.ContainerState, error) {
	if f.inspectErr != nil {
		return sandbox.StateUnknown, f.inspectErr
	}
	state, ok := f.states[name]
	if !ok {
		return sandbox.StateNone, nil
	}
	return state, nil
}

func (f *fakeClient) Start(_ context.Context, name string) error {
	if f.startErr != nil {
		return f.startErr
	}
	f.started = append(f.started, name)
	f.states[name] = sandbox.StateRunning
	return nil
}

func (f *fakeClient) Stop(_ context.Context, name string) error {
	if f.stopErr != nil {
		return f.stopErr
	}
	f.stopped = append(f.stopped, name)
	return nil
}

func (f *fakeClient) Remove(_ context.Context, name string, _ bool) error {
	if f.removeErr != nil {
		return f.removeErr
	}
	f.removed = append(f.removed, name)
	delete(f.states, name)
	return nil
}

func (f *fakeClient) Commit(_ context.Context, container, image string) error {
	f.commitCalls = append(f.commitCalls, [2]string{container, image})
	return nil
}

func (f *fakeClient) ListImages(_ context.Context) ([]sandbox.ImageInfo, error) {
	return f.images, nil
}

func (f *fakeClient) RemoveImage(_ context.Context, name string, _ bool) error {
	if f.removeImageErr != nil {
		return f.removeImageErr
	}
	f.removedImages = append(f.removedImages, name)
	return nil
}

func TestRecoveryCleanupRemovesAbnormalContainers(t *testing.T) {
	fake := newFakeClient()
	fake.states["kylin-ws-t-a-run"] = sandbox.StateRunning
	fake.states["kylin-ws-t-a-exited"] = sandbox.StateStopped
	fake.states["kylin-ws-t-a-unknown"] = sandbox.StateUnknown

	out, err := NewRecovery(fake, RecoveryOptions{Mode: ModeCleanup}).Run(context.Background())
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if out.Inspected != 3 || out.Kept != 1 || out.Removed != 2 || out.Restarted != 0 || out.Failed != 0 {
		t.Fatalf("unexpected outcome: %+v", out)
	}
	want := []string{"kylin-ws-t-a-exited", "kylin-ws-t-a-unknown"}
	if !reflect.DeepEqual(fake.removed, want) {
		t.Fatalf("removed = %v, want %v", fake.removed, want)
	}
	if len(fake.started) != 0 {
		t.Fatalf("cleanup mode must not restart containers: %v", fake.started)
	}
}

func TestRecoveryRecreateRestartsStoppedContainers(t *testing.T) {
	fake := newFakeClient()
	fake.states["kylin-ws-t-a-exited"] = sandbox.StateStopped

	out, err := NewRecovery(fake, RecoveryOptions{Mode: ModeRecreate}).Run(context.Background())
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if out.Restarted != 1 || out.Removed != 0 {
		t.Fatalf("unexpected outcome: %+v", out)
	}
	if !reflect.DeepEqual(fake.started, []string{"kylin-ws-t-a-exited"}) {
		t.Fatalf("started = %v", fake.started)
	}
}

func TestRecoveryRecreateFallsBackToRemove(t *testing.T) {
	fake := newFakeClient()
	fake.states["kylin-ws-t-a-exited"] = sandbox.StateStopped
	fake.startErr = errors.New("start refused")

	out, err := NewRecovery(fake, RecoveryOptions{Mode: ModeRecreate}).Run(context.Background())
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if out.Restarted != 0 || out.Removed != 1 {
		t.Fatalf("unexpected outcome: %+v", out)
	}
	if !reflect.DeepEqual(fake.removed, []string{"kylin-ws-t-a-exited"}) {
		t.Fatalf("removed = %v", fake.removed)
	}
}

func TestRecoveryKeepModeLeavesContainers(t *testing.T) {
	fake := newFakeClient()
	fake.states["kylin-ws-t-a-exited"] = sandbox.StateStopped

	out, err := NewRecovery(fake, RecoveryOptions{Mode: ModeKeep}).Run(context.Background())
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if out.Kept != 1 || out.Removed != 0 || out.Restarted != 0 {
		t.Fatalf("unexpected outcome: %+v", out)
	}
	if len(fake.removed) != 0 || len(fake.started) != 0 {
		t.Fatal("keep mode must not mutate containers")
	}
}

func TestRecoveryInvalidModeDefaultsToCleanup(t *testing.T) {
	fake := newFakeClient()
	fake.states["kylin-ws-t-a-exited"] = sandbox.StateStopped

	out, err := NewRecovery(fake, RecoveryOptions{Mode: RecoveryMode("bogus")}).Run(context.Background())
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if out.Removed != 1 {
		t.Fatalf("expected cleanup default, got %+v", out)
	}
}

func TestRecoveryInspectFailureCountsFailed(t *testing.T) {
	fake := newFakeClient()
	fake.states["kylin-ws-t-a-x"] = sandbox.StateRunning
	fake.inspectErr = errors.New("inspect broken")

	out, err := NewRecovery(fake, RecoveryOptions{Mode: ModeCleanup}).Run(context.Background())
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if out.Failed != 1 || out.Removed != 0 {
		t.Fatalf("unexpected outcome: %+v", out)
	}
}

func TestRecoveryListError(t *testing.T) {
	fake := newFakeClient()
	fake.listErr = errors.New("podman down")

	_, err := NewRecovery(fake, RecoveryOptions{Mode: ModeCleanup}).Run(context.Background())
	if err == nil {
		t.Fatal("expected error when listing fails")
	}
}
