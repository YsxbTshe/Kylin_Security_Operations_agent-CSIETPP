package workspace

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"strings"
)

// workspaceToolPrefix 是 workspace 工具的统一前缀。
const workspaceToolPrefix = "kylin.workspace."

// 封闭工具名 → workspace action 映射。不在表内的工具不走 workspace 路径。
var toolToAction = map[string]string{
	"kylin.workspace.bash":   "exec",
	"kylin.workspace.python": "exec",
	"kylin.workspace.read":   "read_file",
	"kylin.workspace.write":  "write_file",
	"kylin.workspace.edit":   "edit_file",
	"kylin.workspace.glob":   "glob",
}

// IsWorkspaceTool 判断工具名是否是 workspace 工具。
func IsWorkspaceTool(toolName string) bool {
	_, ok := toolToAction[toolName]
	return ok
}

// ActionFor 返回工具对应的 workspace action。
func ActionFor(toolName string) (string, bool) {
	action, ok := toolToAction[toolName]
	return action, ok
}

// BuildArguments 将 SafeToolCall 的 ArgumentsJSON 转换为 workspace Request 的 Arguments。
// 对 bash/python 工具，将 script 包装为 command 数组；对文件操作工具，直接透传参数。
func BuildArguments(toolName string, argumentsJSON string) (map[string]any, error) {
	var raw map[string]any
	if strings.TrimSpace(argumentsJSON) != "" {
		if err := json.Unmarshal([]byte(argumentsJSON), &raw); err != nil {
			return nil, fmt.Errorf("参数不是合法 JSON：%w", err)
		}
	}
	if raw == nil {
		raw = map[string]any{}
	}

	switch toolName {
	case "kylin.workspace.bash":
		return buildExecArgs(raw, "bash")
	case "kylin.workspace.python":
		return buildExecArgs(raw, "python3")
	case "kylin.workspace.read":
		return buildFileReadArgs(raw)
	case "kylin.workspace.write":
		return buildFileWriteArgs(raw)
	case "kylin.workspace.edit":
		return buildFileEditArgs(raw)
	case "kylin.workspace.glob":
		return buildGlobArgs(raw)
	default:
		return nil, fmt.Errorf("未知 workspace 工具：%s", toolName)
	}
}

func buildExecArgs(raw map[string]any, interpreter string) (map[string]any, error) {
	script, _ := raw["script"].(string)
	if strings.TrimSpace(script) == "" {
		return nil, fmt.Errorf("缺少 script 参数")
	}
	if len(script) > 100000 {
		return nil, fmt.Errorf("script 超过 100000 字符上限")
	}
	return map[string]any{
		"command": []any{interpreter, "-c", script},
	}, nil
}

func buildFileReadArgs(raw map[string]any) (map[string]any, error) {
	path, _ := raw["path"].(string)
	if err := validatePath(path); err != nil {
		return nil, err
	}
	return map[string]any{"path": path}, nil
}

func buildFileWriteArgs(raw map[string]any) (map[string]any, error) {
	path, _ := raw["path"].(string)
	if err := validatePath(path); err != nil {
		return nil, err
	}
	content, _ := raw["content"].(string)
	return map[string]any{"path": path, "content": content}, nil
}

func buildFileEditArgs(raw map[string]any) (map[string]any, error) {
	path, _ := raw["path"].(string)
	if err := validatePath(path); err != nil {
		return nil, err
	}
	oldText, _ := raw["oldText"].(string)
	newText, _ := raw["newText"].(string)
	return map[string]any{"path": path, "oldText": oldText, "newText": newText}, nil
}

func buildGlobArgs(raw map[string]any) (map[string]any, error) {
	pattern, _ := raw["pattern"].(string)
	if strings.TrimSpace(pattern) == "" {
		return nil, fmt.Errorf("缺少 pattern 参数")
	}
	if len(pattern) > 1024 {
		return nil, fmt.Errorf("pattern 超过 1024 字符上限")
	}
	return map[string]any{"pattern": pattern}, nil
}

// validatePath 校验 workspace 内文件路径。防止路径穿越到沙箱外。
func validatePath(path string) error {
	if strings.TrimSpace(path) == "" {
		return fmt.Errorf("缺少 path 参数")
	}
	if strings.Contains(path, "\x00") {
		return fmt.Errorf("路径包含非法空字节")
	}
	if filepath.IsAbs(path) {
		return fmt.Errorf("路径不能是绝对路径")
	}
	cleaned := filepath.Clean(path)
	if strings.HasPrefix(cleaned, "..") {
		return fmt.Errorf("路径不能包含 ..")
	}
	depth := strings.Count(cleaned, string(filepath.Separator)) + 1
	if depth > 16 {
		return fmt.Errorf("路径深度超过 16 层")
	}
	for _, segment := range strings.Split(cleaned, string(filepath.Separator)) {
		if len(segment) > 255 {
			return fmt.Errorf("文件名超过 255 字符")
		}
	}
	return nil
}
