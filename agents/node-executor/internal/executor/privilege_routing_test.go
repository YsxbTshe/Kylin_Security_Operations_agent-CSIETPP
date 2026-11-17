package executor

import (
	"testing"

	daemonconfig "gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/config"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/mcp"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/privops"
)

// 每个标了 needsRoot 的工具都必须能被路由到特权助手。
//
// 这两处是分开维护的：执行器用 needsRoot 表示「本地不能跑」，助手用 toolToOp 表示
// 「我认识它」。任何一处漏了另一处，工具就会掉进死路——本地拒绝执行，助手也不接，
// 用户看到的是一句「须经特权助手执行」而没有任何后续。
//
// 这个洞真实发生过：journalctl_query 被标成 needsRoot，但按 ADR-002 §3.6 它其实
// 靠 SupplementaryGroups=systemd-journal 解决、并未进助手清单，于是日志查询全废。
// 有了这条断言，那次改动在提交前就会红。
func TestEveryPrivilegedToolIsRoutableToHelper(t *testing.T) {
	service := newTestService()
	catalog := mcp.NewCatalog()

	// 建 internalName → 公开工具名 的反查表：执行器说的是内部名，助手认的是公开名。
	publicName := map[string]string{}
	for _, definition := range catalog.Tools() {
		publicName[definition.InternalName] = definition.Name
	}

	for internalName, handler := range service.handlers {
		if handler.build == nil {
			// 少数工具不经 commandSpec 路径（如清理/回滚自带实现），没有 build。
			continue
		}
		spec, _, err := handler.build(representativeArgs(internalName))
		if err != nil {
			// 参数构造不出来的工具跳过：这条断言只关心「能建出 spec 的工具」的路由一致性。
			continue
		}
		if !spec.needsRoot {
			continue
		}
		public, ok := publicName[internalName]
		if !ok {
			t.Fatalf("工具 %s 标了 needsRoot 却不在 MCP 目录里，无法被路由", internalName)
		}
		if _, routable := privops.OperationFor(public); !routable {
			t.Fatalf("工具 %s（%s）标了 needsRoot，但特权助手不认识它——"+
				"本地拒绝执行、助手也不接，这个工具是死的。"+
				"要么把它加进 privops 的操作表，要么改成不需要特权。", internalName, public)
		}
	}
}

// 反向：助手认识的每个工具，执行器都不该允许它在本地跑。
func TestHelperRoutedToolsAreNeverRunLocally(t *testing.T) {
	service := newTestService()
	catalog := mcp.NewCatalog()

	for _, definition := range catalog.Tools() {
		if _, routable := privops.OperationFor(definition.Name); !routable {
			continue
		}
		handler, ok := service.handlers[definition.InternalName]
		if !ok || handler.build == nil {
			continue
		}
		spec, _, err := handler.build(representativeArgs(definition.InternalName))
		if err != nil {
			continue
		}
		if !spec.needsRoot {
			t.Fatalf("工具 %s 会被路由到特权助手，但执行器允许它在本地以非特权身份运行——"+
				"一旦有调用路径绕过路由，就会跑出一个半截生效的写操作", definition.Name)
		}
	}
}

// representativeArgs 给各工具一组能通过参数校验的代表值。
func representativeArgs(internalName string) map[string]string {
	switch internalName {
	case "block_ip", "unblock_ip":
		return map[string]string{"ip": "203.0.113.7", "scope": "ingress", "duration": "10m"}
	case "lsof_path", "file_stat", "file_sm3":
		return map[string]string{"path": "/var/log/syslog"}
	case "package_info":
		return map[string]string{"package": "bash"}
	default:
		// 服务类工具统一给一个在测试服务允许清单里的服务名。
		return map[string]string{"service": "nginx", "unit": "nginx"}
	}
}

var _ = daemonconfig.Config{}

// 每个 R3/R4（需审批）工具都必须能路由到特权助手。
//
// 这是「daemon 审批门降为形态校验」这一优化的安全前提（ADR-002 §3.4）：daemon 不再
// 权威校验令牌真伪，只有助手在执行前校验。若某个 R3/R4 工具没有路由到助手、而是在本地
// 执行，那形态正确但伪造的令牌就会被放行——审批边界当场失效。
//
// 这条断言把「每个需审批工具都经助手」变成编译期之外的强约束：新增一个 R3/R4 工具
// 却忘了加进 privops 操作表，测试立刻红。
func TestEveryApprovalRequiredToolRoutesToHelper(t *testing.T) {
	catalog := mcp.NewCatalog()
	for _, definition := range catalog.Tools() {
		risk := definition.RiskCode
		if risk != "R3" && risk != "R4" {
			continue
		}
		if _, routable := privops.OperationFor(definition.Name); !routable {
			t.Fatalf("工具 %s 是 %s（需审批）却不能路由到特权助手——"+
				"daemon 审批门只做形态校验，这个工具会以形态正确但可能伪造的令牌在本地执行。"+
				"必须把它加进 privops 的操作表。", definition.Name, risk)
		}
	}
}
