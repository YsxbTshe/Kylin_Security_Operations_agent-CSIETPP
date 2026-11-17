package com.superskidder.kylinops.backend.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.observability.ObservabilityTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PolicyEngine {

    private static final String BUILTIN_POLICY_VERSION = "builtin-v1";

    private final ObjectMapper objectMapper;
    private final ToolDefinitionProvider toolDefinitionProvider;
    private final JdbcTemplate jdbcTemplate;
    private final ObservabilityTracer observabilityTracer;

    public PolicyEngine(ObjectMapper objectMapper, ToolDefinitionProvider toolDefinitionProvider) {
        this(objectMapper, toolDefinitionProvider, null, new ObservabilityTracer());
    }

    public PolicyEngine(ObjectMapper objectMapper, ToolDefinitionProvider toolDefinitionProvider, JdbcTemplate jdbcTemplate) {
        this(objectMapper, toolDefinitionProvider, jdbcTemplate, new ObservabilityTracer());
    }

    @Autowired
    public PolicyEngine(ObjectMapper objectMapper, ToolDefinitionProvider toolDefinitionProvider, JdbcTemplate jdbcTemplate, ObservabilityTracer observabilityTracer) {
        this.objectMapper = objectMapper;
        this.toolDefinitionProvider = toolDefinitionProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.observabilityTracer = observabilityTracer;
    }

    public PolicyDecision evaluate(String userPrompt, String toolName, String argumentsJson) {
        return observabilityTracer.inSpan("safeops.policy.evaluate", Map.of(
                "safeops.tool_name", toolName == null ? "" : toolName,
                "safeops.arguments_bytes", argumentsJson == null ? 0 : argumentsJson.length()
        ), () -> evaluateInternal(userPrompt, toolName, argumentsJson));
    }

    /**
     * 仅软规则档：风险分级 + 敏感路径/安全姿态升级 + 审批门禁，跳过内置硬底线与可配置 DSL。
     * 用于架构消融基准度量"仅按工具风险分级、无注入/Native Guard 内容审查"这一档（{@link AgentArchitectureBenchmarkIT}）；
     * 生产决策仍走 {@link #evaluate} 的完整管线，本方法不改变其行为。
     */
    public PolicyDecision evaluateRiskClassificationOnly(String userPrompt, String toolName, String argumentsJson) {
        String prompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
        String arguments = argumentsJson == null ? "" : argumentsJson.toLowerCase(Locale.ROOT);
        return evaluateSoftRules(activePolicyCatalog(), prompt, arguments, toolName, argumentsJson);
    }

    private PolicyDecision evaluateInternal(String userPrompt, String toolName, String argumentsJson) {
        PolicyCatalog policyCatalog = activePolicyCatalog();
        String prompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
        String arguments = argumentsJson == null ? "" : argumentsJson.toLowerCase(Locale.ROOT);

        // 1) 内置硬底线规则：Prompt Injection / 禁止动作、关闭 Native Guard、manifest 路径越界。
        //    这些规则先于任何可配置 Policy DSL 求值，且不可被 DSL 降级或绕过——
        //    保证一条宽松 DSL 规则无法让注入指令或越权路径通过。
        boolean readOnlyTool = isReadOnlyTool(toolName);
        Optional<PolicyDecision> hardDeny = evaluateHardDenyRules(
                policyCatalog, prompt, arguments, toolName, argumentsJson, readOnlyTool);
        if (hardDeny.isPresent()) {
            return hardDeny.get();
        }

        // 2) 可配置 Policy DSL：只能在硬底线之上调整风险/审批（硬底线已先行拦截，DSL 无法降到其下）。
        Optional<PolicyDecision> dslDecision = evaluateDslRules(policyCatalog, prompt, toolName, arguments);
        if (dslDecision.isPresent()) {
            return dslDecision.get();
        }

        // 3) 软规则：风险分级与按主机安全姿态升级。
        return evaluateSoftRules(policyCatalog, prompt, arguments, toolName, argumentsJson);
    }

    /** 软规则：风险分级 + 敏感路径/安全姿态升级 + 审批门禁。{@code prompt}/{@code arguments} 已小写归一。 */
    private PolicyDecision evaluateSoftRules(PolicyCatalog policyCatalog, String prompt, String arguments, String toolName, String argumentsJson) {
        List<PolicyHit> hits = new ArrayList<>();
        RiskCode riskCode = manifestRisk(toolName);
        if (riskCode == null) {
            riskCode = switch (toolName) {
                case "kylin.service.restart" -> RiskCode.R3;
                case "kylin.cleanup.execute", "kylin.cleanup.rollback" -> RiskCode.R3;
                default -> RiskCode.R0;
            };
        }

        if (containsSensitivePath(argumentsJson)) {
            riskCode = max(riskCode, RiskCode.R4);
            hits.add(new PolicyHit("POLICY_SENSITIVE_PATH", "命中敏感路径", RiskCode.R4));
        }

        String fullText = prompt + "\n" + arguments;
        if (!isSecurityStatusTool(toolName) && containsAny(fullText, List.of("auditd 未运行", "auditd 停止", "auditd inactive", "auditd disabled", "审计服务停止", "审计未运行"))) {
            riskCode = max(riskCode, RiskCode.R3);
            hits.add(new PolicyHit("POLICY_AUDITD_DOWN_ESCALATION", "审计服务异常时变更风险提升到 R3", RiskCode.R3));
        }
        if (!isSecurityStatusTool(toolName) && containsAny(fullText, List.of("kysec 关闭", "kysec disabled", "kysec off", "安全模块关闭"))) {
            riskCode = max(riskCode, RiskCode.R4);
            hits.add(new PolicyHit("POLICY_KYSEC_DISABLED_ESCALATION", "KYSEC 关闭时写操作必须强审批", RiskCode.R4));
        }
        if (!isSecurityStatusTool(toolName) && riskCode.ordinal() >= RiskCode.R3.ordinal() && containsAny(fullText, List.of("security-switch off", "安全开关关闭"))) {
            hits.add(new PolicyHit("POLICY_SECURITY_SWITCH_OFF_DENY", "security-switch off 时 R3 以上动作默认拒绝", RiskCode.R5));
            return new PolicyDecision(RiskCode.R5, false, true, hits, policyCatalog.version());
        }

        // 不可恢复动作升险（ADR-001 §2.10.4）：判据来自控制面的补偿动作声明，是确定性规则，
        // 不由分类器裁量。注意只有"不可恢复"升险——"无逆操作但系统仍可用"（如重启）不升险，
        // 否则最常见的运维动作会被推到 R4，等级体系随之失效。
        if (isIrrecoverable(toolName) && riskCode.ordinal() >= RiskCode.R2.ordinal()) {
            riskCode = max(riskCode, RiskCode.R4);
            hits.add(new PolicyHit("POLICY_IRRECOVERABLE_ACTION",
                    "该动作无补偿动作且会使系统留在无法还原的状态，需强审批", RiskCode.R4));
        }

        if (riskCode == RiskCode.R3 || riskCode == RiskCode.R4) {
            hits.add(new PolicyHit("POLICY_HIGH_RISK_APPROVAL", "R3/R4 动作必须审批", riskCode));
        } else {
            hits.add(new PolicyHit("POLICY_READ_ONLY_AUTO", "只读感知工具自动允许", RiskCode.R0));
        }

        return new PolicyDecision(riskCode, riskCode == RiskCode.R3 || riskCode == RiskCode.R4, false, List.copyOf(hits), policyCatalog.version());
    }

    /**
     * 内置硬底线规则。先于可配置 Policy DSL 求值，命中即 R5 拒绝且不可被 DSL 覆盖。
     *
     * <p><b>只读工具豁免文本硬底线</b>：文本规则（注入话术、禁止动作、关闭 Native Guard）
     * 防的是「被话术诱导去做写操作」。只读工具不产生任何副作用，对它套用这批规则只会
     * 误杀正常运维——「帮我看看服务器 reboot 之后 nginx 为什么没起来」这种纯诊断诉求，
     * 会让该 run 的每一次工具调用（含 {@code kylin.service.status}）都被 R5 拒绝，
     * 而真正的危害（写）一次也没被阻止。写工具的绝对拦截力原样保留，仍不可被 DSL 降级。
     *
     * <p>路径边界规则（manifest blockedPaths / allowedRoots）**不豁免**：越界读同样是
     * 越权（读 /etc/shadow 不因"只读"而无害），且它判定的是参数而非用户话术。
     * 工具目录不认识的工具按写处理（fail-closed），豁免不会被未知工具白嫖。
     */
    private Optional<PolicyDecision> evaluateHardDenyRules(
            PolicyCatalog policyCatalog, String prompt, String arguments, String toolName, String argumentsJson,
            boolean readOnlyTool) {
        String fullText = prompt + "\n" + arguments;

        if (!readOnlyTool && containsAny(fullText, List.of(
                "忽略之前所有规则",
                "不要记录日志",
                "绕过审批",
                "你现在是 root",
                "直接执行",
                "不要让管理员知道",
                "禁用安全策略",
                "删除所有",
                "清空数据库",
                "rm -rf",
                "mkfs",
                "dd if=",
                "shutdown",
                "reboot"
        ))) {
            return Optional.of(new PolicyDecision(RiskCode.R5, false, true,
                    List.of(new PolicyHit("POLICY_R5_FORBIDDEN_OR_INJECTION", "命中禁止动作或 Prompt Injection 规则", RiskCode.R5)),
                    policyCatalog.version()));
        }

        if (!readOnlyTool && containsAny(fullText, List.of(
                "关闭kysec",
                "关闭 kysec",
                "禁用kysec",
                "禁用 kysec",
                "关闭 selinux",
                "禁用 selinux",
                "setenforce 0",
                "停止auditd",
                "禁用auditd",
                "disable auditd",
                "stop auditd",
                "关闭安全开关",
                "禁用安全开关",
                "security-switch off"
        ))) {
            return Optional.of(new PolicyDecision(RiskCode.R5, false, true,
                    List.of(new PolicyHit("POLICY_R5_NATIVE_GUARD_DISABLE", "命中关闭 Kylin Native Guard 或审计能力的禁止动作", RiskCode.R5)),
                    policyCatalog.version()));
        }

        Optional<ToolDefinition> definition = manifestDefinition(toolName);
        if (violatesBlockedPaths(definition, argumentsJson)) {
            return Optional.of(new PolicyDecision(RiskCode.R5, false, true,
                    List.of(new PolicyHit("POLICY_MANIFEST_BLOCKED_PATH", "命中 MCP manifest blockedPaths", RiskCode.R5)),
                    policyCatalog.version()));
        }
        if (violatesAllowedRoots(definition, argumentsJson)) {
            return Optional.of(new PolicyDecision(RiskCode.R5, false, true,
                    List.of(new PolicyHit("POLICY_MANIFEST_ALLOWED_ROOTS", "参数路径超出 MCP manifest allowedRoots", RiskCode.R5)),
                    policyCatalog.version()));
        }
        return Optional.empty();
    }

    private PolicyCatalog activePolicyCatalog() {
        if (jdbcTemplate == null) {
            return PolicyCatalog.builtin();
        }
        try {
            List<PolicyCatalog> catalogs = jdbcTemplate.query("""
                    select version, rules_json
                    from safeops_policy_version
                    where enabled = true
                    order by activated_at desc nulls last, created_at desc, id desc
                    limit 1
                    """, (rs, rowNum) -> new PolicyCatalog(
                    normalizeBlank(rs.getString("version"), BUILTIN_POLICY_VERSION),
                    normalizeBlank(rs.getString("rules_json"), "[]")
            ));
            return catalogs.isEmpty() ? PolicyCatalog.builtin() : catalogs.get(0);
        } catch (Exception ignored) {
            return PolicyCatalog.builtin();
        }
    }

    private Optional<PolicyDecision> evaluateDslRules(PolicyCatalog policyCatalog, String prompt, String toolName, String arguments) {
        if (policyCatalog.rulesJson().isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode rules = objectMapper.readTree(policyCatalog.rulesJson());
            if (!rules.isArray()) {
                return Optional.empty();
            }
            String tool = toolName == null ? "" : toolName.trim();
            String fullText = prompt + "\n" + arguments;
            for (JsonNode rule : rules) {
                if (!rule.path("enabled").asBoolean(true)) {
                    continue;
                }
                if (!matchesTool(rule, tool) || !matchesText(rule, fullText)) {
                    continue;
                }
                RiskCode riskCode = RiskCode.normalize(rule.path("riskCode").asText("R0"));
                boolean denied = rule.path("deny").asBoolean(riskCode == RiskCode.R5);
                boolean requiresApproval = !denied && rule.path("requiresApproval").asBoolean(riskCode == RiskCode.R3 || riskCode == RiskCode.R4);
                String policyId = normalizeBlank(rule.path("policyId").asText(""), "POLICY_DSL_RULE");
                String message = normalizeBlank(rule.path("message").asText(""), "命中可配置 Policy DSL 规则");
                return Optional.of(new PolicyDecision(
                        riskCode,
                        requiresApproval,
                        denied,
                        List.of(new PolicyHit(policyId, message, riskCode)),
                        policyCatalog.version()
                ));
            }
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean matchesTool(JsonNode rule, String toolName) {
        JsonNode tools = rule.path("tools");
        if (!tools.isArray() || tools.size() == 0) {
            return true;
        }
        for (JsonNode tool : tools) {
            String pattern = tool.asText("").trim();
            if (pattern.equals("*") || pattern.equals(toolName) || (pattern.endsWith("*") && toolName.startsWith(pattern.substring(0, pattern.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesText(JsonNode rule, String fullText) {
        JsonNode containsAny = rule.path("containsAny");
        if (!containsAny.isArray() || containsAny.size() == 0) {
            return true;
        }
        for (JsonNode needle : containsAny) {
            String text = needle.asText("").trim().toLowerCase(Locale.ROOT);
            if (!text.isBlank() && fullText.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private Optional<ToolDefinition> manifestDefinition(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        try {
            List<ToolDefinition> definitions = toolDefinitionProvider.describeTools(List.of(toolName));
            if (definitions.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(definitions.get(0));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * 该工具是否被控制面声明为只读。目录不认识的工具（含端侧上报的插件工具）
     * 一律按写处理——只读豁免必须建立在控制面权威声明之上，不接受端侧自述。
     */
    private boolean isReadOnlyTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        try {
            return manifestDefinition(toolName).map(ToolDefinition::readOnly).orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 该工具是否被控制面声明为不可恢复（执行后系统留在无法还原的状态）。 */
    private boolean isIrrecoverable(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        try {
            return manifestDefinition(toolName)
                    .map(definition -> definition.compensation() != null && definition.compensation().irrecoverable())
                    .orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private RiskCode manifestRisk(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        try {
            Optional<ToolDefinition> manifestDefinition = manifestDefinition(toolName);
            if (manifestDefinition.isEmpty()) {
                return null;
            }
            ToolDefinition definition = manifestDefinition.get();
            if (definition.riskCode() != null && !definition.riskCode().isBlank()) {
                return RiskCode.normalize(definition.riskCode());
            }
            if (definition.requiresApproval()) {
                return RiskCode.R3;
            }
            return definition.readOnly() ? RiskCode.R0 : RiskCode.R2;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean violatesBlockedPaths(Optional<ToolDefinition> definition, String argumentsJson) {
        if (definition.isEmpty() || definition.get().blockedPaths().isEmpty()) {
            return false;
        }
        List<String> paths = argumentPaths(argumentsJson);
        for (String path : paths) {
            for (String blocked : definition.get().blockedPaths()) {
                // "/" only blocks the bare root itself; prefix matching it would reject every
                // absolute path (allowedRoots already constrains where writes may land).
                if (path.equals(blocked)) {
                    return true;
                }
                if (!blocked.equals("/") && path.startsWith(blocked.endsWith("/") ? blocked : blocked + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean violatesAllowedRoots(Optional<ToolDefinition> definition, String argumentsJson) {
        if (definition.isEmpty() || definition.get().allowedRoots().isEmpty()) {
            return false;
        }
        List<String> paths = argumentPaths(argumentsJson);
        for (String path : paths) {
            boolean allowed = false;
            for (String root : definition.get().allowedRoots()) {
                if (path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/")) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return true;
            }
        }
        return false;
    }

    private List<String> argumentPaths(String argumentsJson) {
        List<String> paths = new ArrayList<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return paths;
        }
        try {
            collectPaths(objectMapper.readTree(argumentsJson), paths);
        } catch (Exception ignored) {
            // Ignore invalid JSON here; parser errors are handled by the tool call path.
        }
        return paths;
    }

    private void collectPaths(JsonNode node, List<String> paths) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            String value = node.asText("").trim();
            if (value.startsWith("/")) {
                paths.add(value);
            }
            return;
        }
        if (node.isArray() || node.isObject()) {
            for (JsonNode child : node) {
                collectPaths(child, paths);
            }
        }
    }

    private boolean containsSensitivePath(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            return containsSensitivePath(node);
        } catch (Exception ignored) {
            return containsAny(argumentsJson.toLowerCase(Locale.ROOT), List.of("/etc", "/boot", "/usr", "/var/lib/mysql", "/var/lib/postgresql", "/var/log/audit"));
        }
    }

    private boolean isSecurityStatusTool(String toolName) {
        return toolName != null && toolName.startsWith("kylin.security.") && toolName.endsWith("_status");
    }

    private boolean containsSensitivePath(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            String value = node.asText().toLowerCase(Locale.ROOT);
            return value.equals("/") || value.startsWith("/etc") || value.startsWith("/boot") || value.startsWith("/usr")
                    || value.startsWith("/var/lib/mysql") || value.startsWith("/var/lib/postgresql") || value.startsWith("/var/log/audit");
        }
        if (node.isArray() || node.isObject()) {
            for (JsonNode child : node) {
                if (containsSensitivePath(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAny(String value, List<String> needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private RiskCode max(RiskCode left, RiskCode right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private String normalizeBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record PolicyCatalog(String version, String rulesJson) {
        static PolicyCatalog builtin() {
            return new PolicyCatalog(BUILTIN_POLICY_VERSION, "[]");
        }
    }
}
