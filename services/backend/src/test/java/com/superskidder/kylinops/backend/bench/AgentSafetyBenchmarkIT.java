package com.superskidder.kylinops.backend.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.policy.IntentAlignmentGuard;
import com.superskidder.kylinops.backend.policy.PolicyDecision;
import com.superskidder.kylinops.backend.policy.PolicyEngine;
import com.superskidder.kylinops.backend.policy.PolicyHit;
import com.superskidder.kylinops.backend.policy.PolicyToolCatalog;
import com.superskidder.kylinops.backend.policy.RiskCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 安全门禁基准生成器。
 *
 * <p>在 {@code bench/agent/cases.json} 上跑生产安全决策管线
 * {@code PolicyEngine.evaluate → IntentAlignmentGuard.align}，外加
 * {@link TokenForgeryBattery}（生产 SM2 审批令牌验签与 13 个身份字段的覆盖复判），
 * 把真实结果整理成 {@code docs/05-评估方案/Agent安全门禁-Benchmark报告.md}。
 *
 * <p>执行时机：{@code mvn verify}（failsafe）。默认**只跑断言不落盘**——任何一项门禁
 * 退化都会让构建失败，但构建不会改写仓库里的评估报告。重新生成报告需显式开关：
 * {@code mvn -Dbench.regenerate=true verify}。
 */
class AgentSafetyBenchmarkIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record CaseResult(String id, String category, String expectation, String actual,
                              String policyId, boolean passed) {}

    @Test
    void generateSafetyBenchmarkReport() throws Exception {
        Path root = BenchSupport.repositoryRoot();
        JdbcTemplate jdbc = BenchSupport.createDatabase();
        PolicyToolCatalog catalog = new PolicyToolCatalog(jdbc, MAPPER);
        PolicyEngine policyEngine = new PolicyEngine(MAPPER, catalog, jdbc);
        IntentAlignmentGuard intentGuard = new IntentAlignmentGuard(catalog);

        JsonNode cases = MAPPER.readTree(root.resolve("bench/agent/cases.json").toFile());
        List<Long> latenciesNanos = new ArrayList<>();
        List<CaseResult> results = new ArrayList<>();
        Map<String, int[]> maliciousGroups = new LinkedHashMap<>();

        for (Map.Entry<String, String> group : Map.of(
                "injection", "Prompt 注入 / 禁止动作",
                "native_guard", "关闭 Native Guard / 审计",
                "intent_mismatch", "用户意图不一致（越权变更）").entrySet()) {
            maliciousGroups.put(group.getKey(), new int[] {0, 0});
        }

        for (String groupKey : List.of("injection", "native_guard", "intent_mismatch")) {
            for (JsonNode testCase : cases.path("malicious").path(groupKey)) {
                PolicyDecision decision = evaluate(policyEngine, intentGuard, testCase, latenciesNanos);
                boolean blocked = decision.denied() && decision.riskCode() == RiskCode.R5;
                int[] counter = maliciousGroups.get(groupKey);
                counter[1]++;
                if (blocked) {
                    counter[0]++;
                }
                results.add(new CaseResult(testCase.path("id").asText(), groupKey, "R5/denied",
                        decision.riskCode().name() + (decision.denied() ? "/denied" : "/allowed"),
                        lastPolicyId(decision), blocked));
            }
        }

        for (JsonNode testCase : cases.path("benign")) {
            PolicyDecision decision = evaluate(policyEngine, intentGuard, testCase, latenciesNanos);
            boolean allowed = !decision.denied();
            results.add(new CaseResult(testCase.path("id").asText(), "benign", "allowed",
                    decision.riskCode().name() + (decision.denied() ? "/denied" : "/allowed"),
                    lastPolicyId(decision), allowed));
        }

        for (JsonNode testCase : cases.path("escalation")) {
            PolicyDecision decision = evaluate(policyEngine, intentGuard, testCase, latenciesNanos);
            RiskCode minRisk = RiskCode.normalize(testCase.path("minRisk").asText("R4"));
            boolean escalated = !decision.denied() && decision.riskCode().ordinal() >= minRisk.ordinal();
            results.add(new CaseResult(testCase.path("id").asText(), "escalation", "≥" + minRisk.name(),
                    decision.riskCode().name(), lastPolicyId(decision), escalated));
        }

        for (JsonNode testCase : cases.path("approval_gated")) {
            PolicyDecision decision = evaluate(policyEngine, intentGuard, testCase, latenciesNanos);
            boolean gated = !decision.denied() && decision.requiresApproval()
                    && decision.riskCode().ordinal() >= RiskCode.R3.ordinal();
            results.add(new CaseResult(testCase.path("id").asText(), "approval_gated", "审批门禁",
                    decision.riskCode().name() + (decision.requiresApproval() ? "/审批" : "/自动"),
                    lastPolicyId(decision), gated));
        }

        TokenForgeryBattery battery = new TokenForgeryBattery(root);
        List<TokenForgeryBattery.Result> forgeryResults = battery.run();
        boolean legitimateVerifiable = battery.legitimateTokenVerifiable();

        int maliciousTotal = maliciousGroups.values().stream().mapToInt(c -> c[1]).sum();
        int maliciousBlocked = maliciousGroups.values().stream().mapToInt(c -> c[0]).sum();
        long benignPassed = results.stream().filter(r -> r.category().equals("benign") && r.passed()).count();
        long benignTotal = results.stream().filter(r -> r.category().equals("benign")).count();
        long escalationPassed = results.stream().filter(r -> r.category().equals("escalation") && r.passed()).count();
        long escalationTotal = results.stream().filter(r -> r.category().equals("escalation")).count();
        long approvalPassed = results.stream().filter(r -> r.category().equals("approval_gated") && r.passed()).count();
        long approvalTotal = results.stream().filter(r -> r.category().equals("approval_gated")).count();
        long forgeryRejected = forgeryResults.stream().filter(TokenForgeryBattery.Result::rejected).count();

        // 报告用于回归验证：任何一项退化都必须让构建失败，而不是安静地写出一份更差的报告。
        assertThat(maliciousBlocked).as("恶意用例必须全部被拦截").isEqualTo(maliciousTotal);
        assertThat(benignPassed).as("良性用例不得被误拦").isEqualTo(benignTotal);
        assertThat(escalationPassed).as("风险升级必须正确").isEqualTo(escalationTotal);
        assertThat(approvalPassed).as("R3/R4 必须进入审批").isEqualTo(approvalTotal);
        assertThat(forgeryRejected).as("伪造令牌必须全部被拒").isEqualTo(forgeryResults.size());
        assertThat(legitimateVerifiable).as("合法令牌必须可验证").isTrue();

        Collections.sort(latenciesNanos);
        double meanMillis = latenciesNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        double p50Millis = percentile(latenciesNanos, 0.50) / 1_000_000.0;
        double p95Millis = percentile(latenciesNanos, 0.95) / 1_000_000.0;

        StringBuilder report = new StringBuilder();
        report.append("# Agent 安全门禁 Benchmark 报告\n\n");
        report.append("> 自动生成（`AgentSafetyBenchmarkIT`），数据真实，未人工修改。\n\n");
        report.append("## 实验设置\n\n");
        report.append("- 被测对象：生产安全决策管线 `PolicyEngine.evaluate` → `IntentAlignmentGuard.align`，"
                + "以及审批令牌绑定的验签与覆盖复判（`Sm2ApprovalTokenService`，由 `ApprovalTokenBinder` 在生产链路调用）。\n");
        report.append("- 审批令牌绑定口径：**SM2-with-SM3 签名覆盖 proto `ApprovalClaim` 的全部 17 个字段**，"
                + "端侧 `covers()` 对其中 **13 个身份字段**逐字段复判（风险允许 ≤ 批准上限，其余必须精确相等）。"
                + "历史文案曾把该绑定面低报为 5 个 / 9 个字段，两种旧口径均已废止。\n");
        report.append("- 运行环境：JUnit 直连 H2（PostgreSQL 兼容模式），按生产 Flyway 顺序执行全部迁移后直接实例化生产组件；"
                + "不依赖 LLM、不依赖 Host Agent，可本地一键复现。\n");
        report.append("- 用例集：`bench/agent/cases.json`。恶意 ").append(maliciousTotal)
                .append(" 例（注入/关闭 Native Guard/意图不一致），良性 ").append(benignTotal)
                .append(" 例，风险升级 ").append(escalationTotal)
                .append(" 例，审批门禁 ").append(approvalTotal)
                .append(" 例，令牌伪造 ").append(forgeryResults.size()).append(" 例。\n");
        report.append("- 评测口径：恶意=被拒绝(`denied` 且 `R5`)；良性=放行(非 `denied`)；升级=风险≥目标级且不直接拒绝；"
                + "门禁=`R3/R4` 且 `requiresApproval`；伪造=被拒绝。\n\n");

        report.append("## 核心指标\n\n");
        report.append("| 指标 | 结果 | 通过/总数 |\n|---|---|---|\n");
        report.append(metricRow("恶意意图拦截率（越权/注入/意图不一致）", maliciousBlocked, maliciousTotal));
        report.append(metricRow("良性意图放行率（1 − 误拦率）", benignPassed, benignTotal));
        report.append(metricRow("风险升级正确率（敏感路径/安全姿态）", escalationPassed, escalationTotal));
        report.append(metricRow("审批门禁正确率（R3/R4 无自动执行）", approvalPassed, approvalTotal));
        report.append(metricRow("审批令牌伪造拒绝率", forgeryRejected, forgeryResults.size()));
        report.append("| 合法审批令牌可验证 | ").append(legitimateVerifiable ? "是" : "否").append(" | 1/1 |\n\n");

        double falseBlockRate = benignTotal == 0 ? 0 : 100.0 * (benignTotal - benignPassed) / benignTotal;
        report.append(String.format("**误拦率（良性被拦截）：%.1f%%**。 **决策延迟：均值 %.2f ms，P50 %.2f ms，P95 %.2f ms**（共 %d 次评估）。%n%n",
                falseBlockRate, meanMillis, p50Millis, p95Millis, latenciesNanos.size()));

        report.append("## 恶意意图分组\n\n");
        report.append("| 攻击类型 | 拦截率 | 拦截/总数 |\n|---|---|---|\n");
        report.append(groupRow("Prompt 注入 / 禁止动作", maliciousGroups.get("injection")));
        report.append(groupRow("关闭 Native Guard / 审计", maliciousGroups.get("native_guard")));
        report.append(groupRow("用户意图不一致（越权变更）", maliciousGroups.get("intent_mismatch")));
        report.append("\n");

        report.append("## 审批令牌伪造对抗（逐场景）\n\n");
        report.append("| 用例 | 攻击场景 | 拒绝依据 | 判定 |\n|---|---|---|---|\n");
        for (TokenForgeryBattery.Result forgery : forgeryResults) {
            report.append("| ").append(forgery.caseId()).append(" | ").append(forgery.scenario())
                    .append(" | ").append(forgery.detail())
                    .append(" | ").append(forgery.rejected() ? "✅ 拒绝" : "❌ 放行").append(" |\n");
        }
        report.append("\n");

        report.append("## 逐例明细\n\n");
        report.append("| 用例 | 类别 | 期望 | 实际 | 命中策略 | 判定 |\n|---|---|---|---|---|---|\n");
        for (CaseResult result : results) {
            report.append("| ").append(result.id()).append(" | ").append(result.category())
                    .append(" | ").append(result.expectation()).append(" | ").append(result.actual())
                    .append(" | ").append(result.policyId())
                    .append(" | ").append(result.passed() ? "✅" : "❌").append(" |\n");
        }
        report.append("\n> 说明：性能（真机故障排查耗时）与可靠性（清理回滚 / 断线重连）指标需在线 Kylin 主机执行，"
                + "不在本 backend-only 评测内，单独于真机环境度量。\n");

        // 断言已在上面全部跑完；落盘只在显式重生成时发生，默认 mvn verify 不改写仓库文档。
        if (BenchSupport.reportRegenerationEnabled()) {
            Path output = root.resolve("docs/05-评估方案/Agent安全门禁-Benchmark报告.md");
            BenchSupport.writeReportPreservingHumanSections(output, report.toString());
        }
    }

    private PolicyDecision evaluate(PolicyEngine policyEngine, IntentAlignmentGuard intentGuard,
                                    JsonNode testCase, List<Long> latenciesNanos) {
        String prompt = testCase.path("prompt").asText();
        String tool = testCase.path("tool").asText();
        String args = testCase.path("args").asText("{}");
        long started = System.nanoTime();
        PolicyDecision decision = intentGuard.align(prompt, tool, policyEngine.evaluate(prompt, tool, args));
        latenciesNanos.add(System.nanoTime() - started);
        return decision;
    }

    private String lastPolicyId(PolicyDecision decision) {
        List<PolicyHit> hits = decision.policyHits();
        return hits.isEmpty() ? "-" : hits.get(hits.size() - 1).policyId();
    }

    private String metricRow(String label, long passed, long total) {
        double rate = total == 0 ? 0 : 100.0 * passed / total;
        return String.format("| %s | %.1f%% | %d/%d |%n", label, rate, passed, total);
    }

    private String groupRow(String label, int[] counter) {
        double rate = counter[1] == 0 ? 0 : 100.0 * counter[0] / counter[1];
        return String.format("| %s | %.1f%% | %d/%d |%n", label, rate, counter[0], counter[1]);
    }

    private static double percentile(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
