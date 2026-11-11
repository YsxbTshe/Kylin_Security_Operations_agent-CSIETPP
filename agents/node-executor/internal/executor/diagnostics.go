package executor

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type largeFileItem struct {
	Path    string `json:"path"`
	Size    int64  `json:"size"`
	ModTime string `json:"mod_time"`
}

func (s Service) diskLargeFiles(arguments map[string]string) (string, error) {
	targetDir, err := s.requireAllowedAbsolutePath("disk_large_files", firstArg(arguments, "targetDir", "target_dir", "path"))
	if err != nil {
		return "", err
	}
	maxFiles := normalizePositiveInt(firstArg(arguments, "maxFiles", "max_files", "limit"), 20, 100)
	items := []largeFileItem{}
	err = filepath.WalkDir(targetDir, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			return err
		}
		items = append(items, largeFileItem{
			Path:    path,
			Size:    info.Size(),
			ModTime: info.ModTime().UTC().Format(time.RFC3339),
		})
		return nil
	})
	if err != nil {
		return "", err
	}
	sort.Slice(items, func(i, j int) bool {
		return items[i].Size > items[j].Size
	})
	if len(items) > maxFiles {
		items = items[:maxFiles]
	}
	return jsonResult(map[string]any{
		"tool":       "kylin.disk.large_files",
		"target_dir": targetDir,
		"count":      len(items),
		"files":      items,
	})
}

func (s Service) ioSnapshot(arguments map[string]string) (string, error) {
	limit := normalizePositiveInt(firstArg(arguments, "limit"), 20, 100)
	content, err := os.ReadFile("/proc/diskstats")
	if err != nil {
		return "", err
	}
	items := []map[string]any{}
	for _, line := range strings.Split(string(content), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 14 {
			continue
		}
		name := fields[2]
		if strings.HasPrefix(name, "loop") || strings.HasPrefix(name, "ram") {
			continue
		}
		readSectors, _ := strconv.ParseUint(fields[5], 10, 64)
		writeSectors, _ := strconv.ParseUint(fields[9], 10, 64)
		ioMillis, _ := strconv.ParseUint(fields[12], 10, 64)
		items = append(items, map[string]any{
			"device":        name,
			"read_sectors":  readSectors,
			"write_sectors": writeSectors,
			"io_millis":     ioMillis,
		})
		if len(items) >= limit {
			break
		}
	}
	return jsonResult(map[string]any{
		"tool":  "kylin.io.snapshot",
		"count": len(items),
		"items": items,
	})
}
