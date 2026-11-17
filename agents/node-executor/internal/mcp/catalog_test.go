package mcp

import (
	"slices"
	"testing"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/cleanupcore"
)

// 上报的 allowedRoots 必须跟着 root 持有的 cleanup.conf 走。
//
// 控制面的 PolicyEngine 按节点上报的 allowedRoots 判 R5，这里一旦写死，
// 部署方在 cleanup.conf 里加的目录就会在控制面被拦下——配置看起来毫无作用，
// 而现场只能看到一句「参数路径超出 allowedRoots」，极难归因。
func TestCleanupToolsReportConfiguredAllowedRoots(t *testing.T) {
	old := cleanupcore.AllowedDirs
	cleanupcore.AllowedDirs = []string{"/opt/myapp/logs", "/var/log/aiops-demo"}
	t.Cleanup(func() { cleanupcore.AllowedDirs = old })

	catalog := NewCatalog()
	for _, name := range []string{"kylin.cleanup.plan", "kylin.cleanup.execute", "kylin.cleanup.rollback"} {
		tool, ok := catalog.Lookup(name)
		if !ok {
			t.Fatalf("目录里缺少 %s", name)
		}
		if !slices.Equal(tool.AllowedRoots, cleanupcore.AllowedDirs) {
			t.Fatalf("%s 上报的 allowedRoots 应跟随配置 %v，实际 %v",
				name, cleanupcore.AllowedDirs, tool.AllowedRoots)
		}
	}
}
