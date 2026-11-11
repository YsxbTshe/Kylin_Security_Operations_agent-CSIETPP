package mcp

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
)

type Runtime interface {
	ExecuteTool(nodeID string, toolName string, argumentsJSON string) (string, error)
}

type Server struct {
	nodeID  string
	runtime Runtime
	catalog Catalog
}

type Request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      any             `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type Response struct {
	JSONRPC string     `json:"jsonrpc"`
	ID      any        `json:"id,omitempty"`
	Result  any        `json:"result,omitempty"`
	Error   *ErrorBody `json:"error,omitempty"`
}

type ErrorBody struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type Tool struct {
	Name         string         `json:"name"`
	Description  string         `json:"description"`
	InputSchema  map[string]any `json:"inputSchema"`
	OutputSchema map[string]any `json:"outputSchema,omitempty"`
}

type CallResult struct {
	StructuredContent any            `json:"structuredContent"`
	Content           []ContentBlock `json:"content"`
	IsError           bool           `json:"isError"`
}

type ContentBlock struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

func NewServer(nodeID string, runtime Runtime, catalogs ...Catalog) Server {
	catalog := defaultCatalog()
	if len(catalogs) > 0 && len(catalogs[0].tools) > 0 {
		catalog = catalogs[0]
	}
	return Server{nodeID: nodeID, runtime: runtime, catalog: catalog}
}

func (s Server) Handle(ctx context.Context, request Request) Response {
	if request.JSONRPC != "" && request.JSONRPC != "2.0" {
		return fail(request.ID, -32600, "invalid JSON-RPC version")
	}
	switch request.Method {
	case "initialize":
		return ok(request.ID, map[string]any{
			"protocolVersion": "2024-11-05",
			"serverInfo": map[string]any{
				"name":    "kylin-node-executor",
				"version": "0.1.0-safeops",
			},
			"capabilities": map[string]any{
				"tools": map[string]any{"listChanged": true},
			},
		})
	case "tools/list":
		return ok(request.ID, map[string]any{"tools": s.catalog.PublicTools()})
	case "tools/call":
		result, err := s.callTool(ctx, request.Params)
		if err != nil {
			return ok(request.ID, errorResult(err.Error()))
		}
		return ok(request.ID, result)
	default:
		return fail(request.ID, -32601, "method not found")
	}
}

func (s Server) callTool(_ context.Context, params json.RawMessage) (CallResult, error) {
	var payload struct {
		Name      string         `json:"name"`
		Arguments map[string]any `json:"arguments"`
	}
	if err := json.Unmarshal(params, &payload); err != nil {
		return CallResult{}, fmt.Errorf("invalid tools/call params: %w", err)
	}
	payload.Name = strings.TrimSpace(payload.Name)
	if payload.Name == "" {
		return CallResult{}, fmt.Errorf("tools/call name is required")
	}
	definition, ok := s.catalog.Lookup(payload.Name)
	if !ok {
		return CallResult{}, fmt.Errorf("unsupported MCP tool: %s", payload.Name)
	}
	if payload.Arguments == nil {
		payload.Arguments = map[string]any{}
	}
	if err := validateArguments(definition, payload.Arguments); err != nil {
		return CallResult{}, err
	}
	arguments, err := json.Marshal(payload.Arguments)
	if err != nil {
		return CallResult{}, fmt.Errorf("marshal tool arguments: %w", err)
	}
	output, err := s.runtime.ExecuteTool(s.nodeID, definition.InternalName, string(arguments))
	if err != nil {
		return CallResult{}, err
	}
	return CallResult{
		StructuredContent: map[string]any{
			"toolName": payload.Name,
			"output":   output,
		},
		Content: []ContentBlock{{Type: "text", Text: output}},
		IsError: false,
	}, nil
}

func SupportedToolNames() []string {
	return defaultCatalog().SupportedToolNames()
}

func IsSupportedTool(name string) bool {
	return defaultCatalog().IsSupportedTool(name)
}

func validateArguments(definition ToolDefinition, arguments map[string]any) error {
	return validateAgainstSchema(definition.InputSchema, arguments)
}

func validateAgainstSchema(schema map[string]any, arguments map[string]any) error {
	if schema == nil {
		return nil
	}
	if schemaType, ok := schema["type"].(string); ok && schemaType != "" && schemaType != "object" {
		return fmt.Errorf("tool input schema root must be object")
	}
	properties := map[string]any{}
	if rawProperties, ok := schema["properties"].(map[string]any); ok {
		properties = rawProperties
	}
	if required, ok := schema["required"].([]any); ok {
		for _, item := range required {
			key := strings.TrimSpace(fmt.Sprint(item))
			if key == "" {
				continue
			}
			value, ok := arguments[key]
			if !ok || strings.TrimSpace(fmt.Sprint(value)) == "" {
				return fmt.Errorf("argument %s is required", key)
			}
		}
	}
	if required, ok := schema["required"].([]string); ok {
		for _, key := range required {
			value, ok := arguments[key]
			if !ok || strings.TrimSpace(fmt.Sprint(value)) == "" {
				return fmt.Errorf("argument %s is required", key)
			}
		}
	}
	if additional, ok := schema["additionalProperties"].(bool); ok && !additional {
		for key := range arguments {
			if _, ok := properties[key]; !ok {
				return fmt.Errorf("argument %s is not allowed", key)
			}
		}
	}
	for key, value := range arguments {
		rawProperty, ok := properties[key]
		if !ok {
			continue
		}
		property, ok := rawProperty.(map[string]any)
		if !ok {
			continue
		}
		if err := validateProperty(key, value, property); err != nil {
			return err
		}
	}
	return nil
}

func validateProperty(key string, value any, schema map[string]any) error {
	schemaType := strings.TrimSpace(fmt.Sprint(schema["type"]))
	switch schemaType {
	case "string":
		text := strings.TrimSpace(fmt.Sprint(value))
		if minimum, ok := number(schema["minLength"]); ok && len(text) < int(minimum) {
			return fmt.Errorf("argument %s is too short", key)
		}
		if rawEnum, ok := schema["enum"].([]any); ok && len(rawEnum) > 0 {
			matched := false
			for _, item := range rawEnum {
				if text == fmt.Sprint(item) {
					matched = true
				}
			}
			if !matched {
				return fmt.Errorf("argument %s is outside enum", key)
			}
		}
		if rawEnum, ok := schema["enum"].([]string); ok && len(rawEnum) > 0 {
			matched := false
			for _, item := range rawEnum {
				if text == item {
					matched = true
				}
			}
			if !matched {
				return fmt.Errorf("argument %s is outside enum", key)
			}
		}
	case "integer":
		integerValue, ok := integer(value)
		if !ok {
			return fmt.Errorf("argument %s must be an integer", key)
		}
		if minimum, ok := number(schema["minimum"]); ok && integerValue < int64(minimum) {
			return fmt.Errorf("argument %s is below minimum", key)
		}
		if maximum, ok := number(schema["maximum"]); ok && integerValue > int64(maximum) {
			return fmt.Errorf("argument %s is above maximum", key)
		}
	}
	return nil
}

func integer(value any) (int64, bool) {
	switch typed := value.(type) {
	case float64:
		if typed != float64(int64(typed)) {
			return 0, false
		}
		return int64(typed), true
	case int:
		return int64(typed), true
	case int64:
		return typed, true
	case string:
		trimmed := strings.TrimSpace(typed)
		if trimmed == "" {
			return 0, false
		}
		var result int64
		for _, ch := range trimmed {
			if ch < '0' || ch > '9' {
				return 0, false
			}
			result = result*10 + int64(ch-'0')
		}
		return result, true
	default:
		return 0, false
	}
}

func number(value any) (float64, bool) {
	switch typed := value.(type) {
	case float64:
		return typed, true
	case int:
		return float64(typed), true
	case int64:
		return float64(typed), true
	default:
		return 0, false
	}
}

func objectSchema(properties map[string]any, required []string) map[string]any {
	schema := map[string]any{
		"type":                 "object",
		"properties":           properties,
		"additionalProperties": false,
	}
	if len(required) > 0 {
		schema["required"] = required
	}
	return schema
}

func stringSchema() map[string]any {
	return map[string]any{"type": "string", "minLength": 1}
}

func integerSchema(minimum int, maximum int) map[string]any {
	return map[string]any{"type": "integer", "minimum": minimum, "maximum": maximum}
}

func enumSchema(values []string) map[string]any {
	return map[string]any{"type": "string", "enum": values}
}

func errorResult(message string) CallResult {
	return CallResult{
		StructuredContent: map[string]any{"error": message},
		Content:           []ContentBlock{{Type: "text", Text: message}},
		IsError:           true,
	}
}

func ok(id any, result any) Response {
	return Response{JSONRPC: "2.0", ID: id, Result: result}
}

func fail(id any, code int, message string) Response {
	return Response{JSONRPC: "2.0", ID: id, Error: &ErrorBody{Code: code, Message: message}}
}
