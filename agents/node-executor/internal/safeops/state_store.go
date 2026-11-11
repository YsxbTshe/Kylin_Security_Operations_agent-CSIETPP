package safeops

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
)

type SchedulerState struct {
	Completed   map[string]SafeToolResult `json:"completed"`
	Cancelled   []string                  `json:"cancelled"`
	Paused      []string                  `json:"paused"`
	Running     map[string]SafeToolCall   `json:"running"`
	Queued      map[string]SafeToolCall   `json:"queued"`
	Metrics     SchedulerMetrics          `json:"metrics"`
	MaxParallel int                       `json:"maxParallel"`
}

type SchedulerStateStore interface {
	Load() (SchedulerState, error)
	Save(SchedulerState) error
}

type FileStateStore struct {
	path string
}

func NewFileStateStore(sessionDir string) *FileStateStore {
	sessionDir = strings.TrimSpace(sessionDir)
	if sessionDir == "" {
		return nil
	}
	return &FileStateStore{path: filepath.Join(sessionDir, "scheduler-state.json")}
}

func (s *FileStateStore) Load() (SchedulerState, error) {
	if s == nil || strings.TrimSpace(s.path) == "" {
		return SchedulerState{}, nil
	}
	data, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return SchedulerState{}, nil
		}
		return SchedulerState{}, err
	}
	var state SchedulerState
	if err := json.Unmarshal(data, &state); err != nil {
		return SchedulerState{}, err
	}
	return state, nil
}

func (s *FileStateStore) Save(state SchedulerState) error {
	if s == nil || strings.TrimSpace(s.path) == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
