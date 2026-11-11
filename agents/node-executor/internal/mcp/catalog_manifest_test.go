package mcp

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

type signedManifestFile struct {
	PackageID string               `json:"packageId"`
	Version   string               `json:"version"`
	Tools     []signedManifestTool `json:"tools"`
}

type signedManifestTool struct {
	ID                    string         `json:"id"`
	Executor              string         `json:"executor"`
	RiskFloor             string         `json:"riskFloor"`
	ReadOnly              bool           `json:"readOnly"`
	AlwaysRequireApproval bool           `json:"alwaysRequireApproval"`
	InputSchema           map[string]any `json:"inputSchema"`
	Implementation        struct {
		Entrypoint string `json:"entrypoint"`
	} `json:"implementation"`
}

func TestDefaultCatalogIsCoveredBySignedManifest(t *testing.T) {
	payload, err := os.ReadFile(signedManifestPath(t))
	if err != nil {
		t.Fatal(err)
	}
	var manifest signedManifestFile
	if err := json.Unmarshal(payload, &manifest); err != nil {
		t.Fatal(err)
	}
	if manifest.PackageID != "kylin.core.node" || manifest.Version != "1.1.0" {
		t.Fatalf("unexpected signed package %s@%s", manifest.PackageID, manifest.Version)
	}

	byID := map[string]signedManifestTool{}
	for _, item := range manifest.Tools {
		if _, exists := byID[item.ID]; exists {
			t.Fatalf("duplicate signed tool id %s", item.ID)
		}
		byID[item.ID] = item
	}

	catalog := defaultCatalog()
	for _, tool := range catalog.Tools() {
		signed, ok := byID[tool.Name]
		if !ok {
			t.Errorf("signed manifest missing catalog tool %s", tool.Name)
			continue
		}
		if signed.RiskFloor != tool.RiskCode {
			t.Errorf("%s riskFloor: signed %s catalog %s", tool.Name, signed.RiskFloor, tool.RiskCode)
		}
		if signed.ReadOnly != tool.ReadOnly {
			t.Errorf("%s readOnly: signed %v catalog %v", tool.Name, signed.ReadOnly, tool.ReadOnly)
		}
		if signed.AlwaysRequireApproval != tool.RequiresApproval {
			t.Errorf("%s alwaysRequireApproval: signed %v catalog %v", tool.Name, signed.AlwaysRequireApproval, tool.RequiresApproval)
		}
		if signed.Implementation.Entrypoint != tool.InternalName {
			t.Errorf("%s entrypoint: signed %s catalog %s", tool.Name, signed.Implementation.Entrypoint, tool.InternalName)
		}
		if signed.Executor == "WORKSPACE" && !strings.HasPrefix(tool.InternalName, "workspace_") {
			t.Errorf("%s executor WORKSPACE but internal name %s", tool.Name, tool.InternalName)
		}
		if signed.Executor == "NODE" && strings.HasPrefix(tool.InternalName, "workspace_") {
			t.Errorf("%s executor NODE but workspace internal name", tool.Name)
		}
		got := propertyNames(tool.InputSchema)
		want := propertyNames(signed.InputSchema)
		if !sameStringSet(got, want) {
			t.Errorf("%s input properties: catalog %v signed %v", tool.Name, got, want)
		}
		delete(byID, tool.Name)
	}
	for id := range byID {
		t.Errorf("signed manifest has extra tool not in defaultCatalog: %s", id)
	}
}

func signedManifestPath(t *testing.T) string {
	t.Helper()
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("cannot resolve test file path")
	}
	path := filepath.Join(filepath.Dir(thisFile), "..", "..", "..", "..", "tool-packages", "core-node-tools", "manifest.json")
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("signed manifest.json not found at %s: %v", path, err)
	}
	return path
}

func propertyNames(schema map[string]any) []string {
	raw, _ := schema["properties"].(map[string]any)
	names := make([]string, 0, len(raw))
	for name := range raw {
		names = append(names, name)
	}
	return names
}

func sameStringSet(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	seen := map[string]int{}
	for _, name := range left {
		seen[name]++
	}
	for _, name := range right {
		seen[name]--
		if seen[name] < 0 {
			return false
		}
	}
	for _, count := range seen {
		if count != 0 {
			return false
		}
	}
	return true
}
