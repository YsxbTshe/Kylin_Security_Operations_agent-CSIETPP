package workspace

import (
	"testing"
)

func TestIsWorkspaceTool(t *testing.T) {
	cases := []struct {
		name  string
		match bool
	}{
		{"kylin.workspace.bash", true},
		{"kylin.workspace.python", true},
		{"kylin.workspace.read", true},
		{"kylin.workspace.write", true},
		{"kylin.workspace.edit", true},
		{"kylin.workspace.glob", true},
		{"kylin.workspace.unknown", false},
		{"kylin.service.status", false},
		{"kylin.workspace.", false},
		{"", false},
	}
	for _, tc := range cases {
		if got := IsWorkspaceTool(tc.name); got != tc.match {
			t.Errorf("IsWorkspaceTool(%q) = %v, want %v", tc.name, got, tc.match)
		}
	}
}

func TestActionFor(t *testing.T) {
	action, ok := ActionFor("kylin.workspace.bash")
	if !ok || action != "exec" {
		t.Fatalf("expected exec, got %q ok=%v", action, ok)
	}
	_, ok = ActionFor("kylin.service.status")
	if ok {
		t.Fatal("non-workspace tool should not have action")
	}
}

func TestBuildArgumentsBash(t *testing.T) {
	args, err := BuildArguments("kylin.workspace.bash", `{"script":"echo hello"}`)
	if err != nil {
		t.Fatal(err)
	}
	cmd, ok := args["command"].([]any)
	if !ok || len(cmd) != 3 {
		t.Fatalf("expected [bash -c script], got %v", args["command"])
	}
	if cmd[0] != "bash" || cmd[1] != "-c" || cmd[2] != "echo hello" {
		t.Fatalf("unexpected command: %v", cmd)
	}
}

func TestBuildArgumentsPython(t *testing.T) {
	args, err := BuildArguments("kylin.workspace.python", `{"script":"print(1)"}`)
	if err != nil {
		t.Fatal(err)
	}
	cmd := args["command"].([]any)
	if cmd[0] != "python3" {
		t.Fatalf("expected python3, got %v", cmd[0])
	}
}

func TestBuildArgumentsBashEmptyScript(t *testing.T) {
	_, err := BuildArguments("kylin.workspace.bash", `{"script":""}`)
	if err == nil {
		t.Fatal("expected error for empty script")
	}
}

func TestBuildArgumentsBashOversizeScript(t *testing.T) {
	big := make([]byte, 100001)
	for i := range big {
		big[i] = 'a'
	}
	_, err := BuildArguments("kylin.workspace.bash", `{"script":"`+string(big)+`"}`)
	if err == nil {
		t.Fatal("expected error for oversized script")
	}
}

func TestBuildArgumentsRead(t *testing.T) {
	args, err := BuildArguments("kylin.workspace.read", `{"path":"src/main.go"}`)
	if err != nil {
		t.Fatal(err)
	}
	if args["path"] != "src/main.go" {
		t.Fatalf("unexpected path: %v", args["path"])
	}
}

func TestBuildArgumentsWrite(t *testing.T) {
	args, err := BuildArguments("kylin.workspace.write", `{"path":"out.txt","content":"hello"}`)
	if err != nil {
		t.Fatal(err)
	}
	if args["path"] != "out.txt" || args["content"] != "hello" {
		t.Fatalf("unexpected args: %v", args)
	}
}

func TestBuildArgumentsEdit(t *testing.T) {
	args, err := BuildArguments("kylin.workspace.edit", `{"path":"f.txt","oldText":"a","newText":"b"}`)
	if err != nil {
		t.Fatal(err)
	}
	if args["oldText"] != "a" || args["newText"] != "b" {
		t.Fatalf("unexpected args: %v", args)
	}
}

func TestBuildArgumentsGlob(t *testing.T) {
	args, err := BuildArguments("kylin.workspace.glob", `{"pattern":"**/*.go"}`)
	if err != nil {
		t.Fatal(err)
	}
	if args["pattern"] != "**/*.go" {
		t.Fatalf("unexpected pattern: %v", args["pattern"])
	}
}

func TestBuildArgumentsGlobEmptyPattern(t *testing.T) {
	_, err := BuildArguments("kylin.workspace.glob", `{"pattern":""}`)
	if err == nil {
		t.Fatal("expected error for empty pattern")
	}
}

func TestValidatePathTraversal(t *testing.T) {
	cases := []struct {
		path    string
		wantErr bool
	}{
		{"file.txt", false},
		{"src/main.go", false},
		{"a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p", false},
		{"../etc/passwd", true},
		{"foo/../../etc/passwd", true},
		{"/etc/passwd", true},
		{"file\x00.txt", true},
		{"a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q", true},
	}
	for _, tc := range cases {
		err := validatePath(tc.path)
		if (err != nil) != tc.wantErr {
			t.Errorf("validatePath(%q) error=%v, wantErr=%v", tc.path, err, tc.wantErr)
		}
	}
}

func TestValidatePathLongFileName(t *testing.T) {
	long := ""
	for i := 0; i < 260; i++ {
		long += "a"
	}
	if err := validatePath(long); err == nil {
		t.Fatal("expected error for filename > 255 chars")
	}
}

func TestBuildArgumentsUnknownTool(t *testing.T) {
	_, err := BuildArguments("kylin.unknown", `{}`)
	if err == nil {
		t.Fatal("expected error for unknown tool")
	}
}
