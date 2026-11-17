package mcp

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

type fakeRuntime struct{}

func (fakeRuntime) ExecuteTool(string, string, string) (string, error) { return "ok", nil }

func TestInitialize(t *testing.T) {
	server := NewServer("node-1", fakeRuntime{})
	response := server.Handle(context.Background(), Request{ID: 1, Method: "initialize"})
	if response.Error != nil || response.Result == nil {
		t.Fatalf("unexpected initialize response: %+v", response)
	}
	encoded, _ := json.Marshal(response.Result)
	if !strings.Contains(string(encoded), `"listChanged":true`) {
		t.Fatalf("expected initialize to advertise tools listChanged, got %s", encoded)
	}
	for _, removed := range []string{"resources", "prompts", "sampling", "elicitation", "roots"} {
		if strings.Contains(string(encoded), `"`+removed+`"`) {
			t.Fatalf("expected initialize to stop advertising removed capability %s, got %s", removed, encoded)
		}
	}
}

func TestToolsList(t *testing.T) {
	server := NewServer("node-1", fakeRuntime{})
	response := server.Handle(context.Background(), Request{ID: 1, Method: "tools/list"})
	encoded, _ := json.Marshal(response.Result)
	if !json.Valid(encoded) || len(encoded) == 0 {
		t.Fatalf("invalid tools/list result")
	}
	if !strings.Contains(string(encoded), "outputSchema") {
		t.Fatalf("expected tools/list to expose outputSchema, got %s", encoded)
	}
}

func TestToolsCall(t *testing.T) {
	server := NewServer("node-1", fakeRuntime{})
	params := json.RawMessage(`{"name":"kylin.disk.usage","arguments":{}}`)
	response := server.Handle(context.Background(), Request{ID: 1, Method: "tools/call", Params: params})
	if response.Error != nil {
		t.Fatalf("unexpected error: %+v", response.Error)
	}
	result := response.Result.(CallResult)
	if result.IsError {
		t.Fatalf("tools/call returned error result")
	}
}

func TestToolsCallRejectsMissingRequiredArgument(t *testing.T) {
	server := NewServer("node-1", fakeRuntime{})
	params := json.RawMessage(`{"name":"kylin.service.status","arguments":{}}`)
	response := server.Handle(context.Background(), Request{ID: 1, Method: "tools/call", Params: params})
	if response.Error != nil {
		t.Fatalf("unexpected JSON-RPC error: %+v", response.Error)
	}
	result := response.Result.(CallResult)
	if !result.IsError {
		t.Fatalf("expected MCP error result")
	}
}

func TestRemovedMethodsReturnMethodNotFound(t *testing.T) {
	server := NewServer("node-1", fakeRuntime{})
	for _, method := range []string{"resources/list", "resources/read", "prompts/list", "prompts/get"} {
		response := server.Handle(context.Background(), Request{ID: 1, Method: method})
		if response.Error == nil || response.Error.Code != -32601 {
			t.Fatalf("expected method not found for %s, got %+v", method, response)
		}
	}
}

func TestRejectsInvalidJSONRPCVersion(t *testing.T) {
	server := NewServer("node-1", fakeRuntime{})
	response := server.Handle(context.Background(), Request{JSONRPC: "1.0", ID: 1, Method: "tools/list"})
	if response.Error == nil || response.Error.Code != -32600 {
		t.Fatalf("expected invalid JSON-RPC version error, got %+v", response)
	}
}
