package com.superskidder.kylinops.backend.agentruntime.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 模型全部不可用时的**确定性只读剧本**。
 *
 * <p>背景：外部模型不可用时执行循环唯一的出路是把 run 判失败，而文档仍宣称
 * "LLM planner 默认可回退确定性规划"——原实现类已随旧执行链路删除。本类是那份承诺的
 * 最小、诚实的兑现：<b>不是"更笨的 Agent"，而是一段固定的只读诊断流程</b>。
 *
 * <p>三条硬约束，缺一不可：
 * <ol>
 *   <li><b>只读</b>：剧本里只允许出现只读工具，永远不触发任何写操作。真实执行前
 *       {@code AgentRoundExecutor} 还会按工具目录的 readOnly 位再筛一次（双保险），
 *       并照常过 DispatchGate（ABAC/grant/风险门禁一个都不少）；</li>
 *   <li><b>确定性</b>：关键词 → 固定工具链，没有任何模型参与，可完全复现；</li>
 *   <li><b>诚实</b>：匹配不到任何链就返回空，由调用方按原样失败——宁可诚实失败，
 *       也不瞎跑一条无关的链假装还在工作。降级本身由专门的审计事件与结果标记披露，
 *       不得伪装成模型输出。</li>
 * </ol>
 */
public final class DeterministicFallbackPlaybook {

    /** 剧本的一步：工具 + 固定参数。参数只用工具 inputSchema 允许的字段。 */
    public record Step(String toolId, Map<String, Object> arguments) {
        public Step {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 一条确定性只读链。 */
    public record Chain(String id, String title, List<Step> steps) {
        public Chain {
            steps = List.copyOf(steps);
        }
    }

    private record Definition(String id, String title, List<String> keywords, List<Step> steps,
                              List<Step> serviceSteps) {}

    /**
     * 四条链覆盖现场最常见的四类诉求。顺序即匹配优先级：命中关键词最多者胜出，
     * 同分取先声明者（确定性，不依赖 Map 迭代顺序）。
     */
    private static final List<Definition> CHAINS = List.of(
            new Definition("disk-cleanup-diagnosis", "磁盘与清理诊断（只读）",
                    List.of("磁盘", "空间", "容量", "占用", "清理", "日志文件", "满了", "水位",
                            "disk", "space", "cleanup", "usage"),
                    List.of(new Step("kylin.disk.usage", Map.of()),
                            new Step("kylin.system.mounts", Map.of()),
                            new Step("kylin.disk.large_files", Map.of()),
                            new Step("kylin.cleanup.plan", Map.of())),
                    List.of()),
            new Definition("security-posture", "安全姿态巡检（只读）",
                    List.of("安全", "加固", "合规", "审计", "入侵", "攻击", "封禁", "防火墙", "爆破",
                            "kysec", "selinux", "auditd", "security", "firewall", "intrusion"),
                    List.of(new Step("kylin.security.kysec_status", Map.of()),
                            new Step("kylin.security.selinux_status", Map.of()),
                            new Step("kylin.security.auditd_status", Map.of()),
                            new Step("kylin.security.security_switch_status", Map.of()),
                            new Step("kylin.firewall.state", Map.of())),
                    List.of()),
            new Definition("service-and-log", "服务与日志诊断（只读）",
                    List.of("服务", "重启", "启动", "挂了", "起不来", "没起来", "日志", "报错", "异常",
                            "service", "restart", "log", "journal", "nginx", "sshd", "mysql"),
                    List.of(new Step("kylin.net.listening_ports", Map.of())),
                    // 需要 targetScope.service 才有意义的步骤；服务名缺失时整体略过，不猜。
                    List.of(new Step("kylin.service.status", Map.of()),
                            new Step("kylin.log.journal_query", Map.of()))),
            new Definition("process-and-resource", "进程与资源诊断（只读）",
                    List.of("进程", "cpu", "内存", "负载", "资源", "卡", "慢", "性能", "top",
                            "process", "memory", "load", "performance"),
                    List.of(new Step("kylin.process.top_cpu", Map.of()),
                            new Step("kylin.process.top_memory", Map.of()),
                            new Step("kylin.io.snapshot", Map.of()),
                            new Step("kylin.metrics.node_exporter_snapshot", Map.of())),
                    List.of()));

    private DeterministicFallbackPlaybook() {
    }

    /**
     * 按 objective 关键词选链。{@code service} 来自 run 的 targetScope，缺失时需要服务名的
     * 步骤被略过（宁可少查也不编一个服务名）。匹配不到任何关键词返回 {@link Optional#empty()}。
     */
    public static Optional<Chain> match(String objective, String service) {
        String text = objective == null ? "" : objective.toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return Optional.empty();
        }
        Definition best = null;
        int bestScore = 0;
        for (Definition definition : CHAINS) {
            int score = 0;
            for (String keyword : definition.keywords()) {
                if (text.contains(keyword)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = definition;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(new Chain(best.id(), best.title(), materialize(best, service)));
    }

    /** 剧本声明过的全部工具 id（守护测试用：断言它们在工具目录里全是只读）。 */
    public static List<String> declaredToolIds() {
        List<String> toolIds = new ArrayList<>();
        for (Definition definition : CHAINS) {
            definition.steps().forEach(step -> toolIds.add(step.toolId()));
            definition.serviceSteps().forEach(step -> toolIds.add(step.toolId()));
        }
        return List.copyOf(toolIds);
    }

    private static List<Step> materialize(Definition definition, String service) {
        List<Step> steps = new ArrayList<>(definition.steps());
        if (service == null || service.isBlank()) {
            return List.copyOf(steps);
        }
        for (Step template : definition.serviceSteps()) {
            Map<String, Object> arguments = new LinkedHashMap<>(template.arguments());
            // 两个工具对"服务/单元"的参数名不同，但都只接受这一个服务标识。
            arguments.put("kylin.log.journal_query".equals(template.toolId()) ? "unit" : "service", service.trim());
            steps.add(new Step(template.toolId(), arguments));
        }
        return List.copyOf(steps);
    }
}
