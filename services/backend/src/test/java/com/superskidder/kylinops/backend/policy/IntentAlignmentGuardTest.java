package com.superskidder.kylinops.backend.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 意图一致性护栏的真/假阳性双向验收。
 *
 * <p>护栏原本是「整段含咨询标记 且 不含执行承诺 → 写工具一律 R5」，把官方演示的旗舰场景
 * （「分析磁盘占用并给出清理方案」「检查磁盘并清理过期日志」）整类硬拒——而运维最自然的
 * 表达恰恰就是"先诊断后处置"。本类一边钉死假阳性（自然运维话术不得被误拒），
 * 一边钉死真阳性（用户只要建议时，Agent 不得擅自去改）。
 */
class IntentAlignmentGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 与生产同源的工具目录（jdbc 为空时退化为内置静态目录，只读位一致）。 */
    private final IntentAlignmentGuard guard = new IntentAlignmentGuard(new PolicyToolCatalog(null, MAPPER));

    private static final PolicyDecision UPSTREAM_WRITE =
            new PolicyDecision(RiskCode.R3, true, false, List.of());

    /**
     * 假阳性用例：真实运维话术 + 写工具，必须放行到审批门禁，不得被护栏硬拒。
     * 这些句子的共同点是「有一个子句在下达处置指令」——用户已经授权了变更。
     */
    private static final List<String> NATURAL_OPS_PHRASINGS = List.of(
            "分析磁盘占用异常，并给出清理方案",
            "分析磁盘占用并给出清理方案",
            "检查磁盘并清理过期日志",
            "看看 nginx 挂没挂，挂了就重启",
            "查一下 /var/log 占用，然后清理归档日志",
            "检查 sshd 状态，异常就重启服务",
            "分析这台机器的负载，杀掉占用最高的僵尸进程",
            "排查登录爆破来源 IP，并封禁该 IP",
            "看看 auditd 有没有起来，没起来就拉起来",
            "统计大文件占用，清理 7 天前的临时文件",
            "检查磁盘水位，超过 90% 就执行清理",
            "分析上次变更的影响，必要时回滚到上一个版本",
            "检查防火墙规则，把 1.2.3.4 加入封禁列表",
            "评估当前告警，确认后重启异常服务",
            "查看 nginx 错误日志并修复配置",
            "检查 /tmp 目录，清空过期缓存文件",
            "先诊断磁盘 IO，再重启相关服务",
            "看一下服务状态，挂了的服务全部拉起"
    );

    /**
     * 真阳性用例：用户只要建议/只在查询，写工具必须被拦（含 bench/agent intent_mismatch 全部样例）。
     * 注意这些句子里也有"清理/重启/回滚/封禁"，但它们出现在征询语或查询动词的宾语里。
     */
    private static final List<String> ADVISORY_ONLY_PHRASINGS = List.of(
            "帮我看看磁盘占用",
            "给我一份磁盘清理建议，列出哪些可以清理",
            "查询一下 sshd 服务的当前状态",
            "分析一下是否需要重启 nginx，给出重启建议",
            "查看回滚记录，看看上次清理回滚了什么",
            "封禁建议：这个 IP 1.2.3.4 要不要封禁",
            "检查一下磁盘占用情况",
            "评估一下要不要清理这些日志"
    );

    @Test
    void naturalOpsPhrasingsWithDisposalIntentAreNotFalselyRejected() {
        for (String objective : NATURAL_OPS_PHRASINGS) {
            PolicyDecision decision = guard.align(objective, "kylin.cleanup.execute", false, UPSTREAM_WRITE);

            assertThat(decision.denied()).as("不得误拒：%s", objective).isFalse();
            assertThat(decision.riskCode()).as("风险等级不得被抬到 R5：%s", objective).isEqualTo(RiskCode.R3);
            assertThat(decision.requiresApproval()).as("放行后仍受审批门禁约束：%s", objective).isTrue();
        }
    }

    @Test
    void advisoryOnlyRequestsStillBlockWriteTools() {
        for (String objective : ADVISORY_ONLY_PHRASINGS) {
            PolicyDecision decision = guard.align(objective, "kylin.cleanup.execute", false, UPSTREAM_WRITE);

            assertThat(decision.denied()).as("必须拦截：%s", objective).isTrue();
            assertThat(decision.riskCode()).isEqualTo(RiskCode.R5);
            assertThat(decision.policyHits()).extracting(PolicyHit::policyId)
                    .as("必须命中意图不一致：%s", objective)
                    .contains(IntentAlignmentGuard.POLICY_INTENT_MISMATCH);
        }
    }

    /** 只读工具与任何意图都无害对齐：连同上面的咨询话术也不该被拦。 */
    @Test
    void readOnlyToolsAreNeverBlocked() {
        PolicyDecision upstreamRead = new PolicyDecision(RiskCode.R0, false, false, List.of());

        for (String objective : ADVISORY_ONLY_PHRASINGS) {
            assertThat(guard.align(objective, "kylin.disk.usage", upstreamRead).denied())
                    .as("只读工具不得被拦：%s", objective).isFalse();
        }
    }

    /** 明确的执行承诺覆盖咨询标记（原有语义，保持不变）。 */
    @Test
    void explicitExecutionCommitmentOverridesAdvisoryMarkers() {
        PolicyDecision decision = guard.align(
                "检查一下磁盘占用情况，然后立即执行清理", "kylin.cleanup.execute", false, UPSTREAM_WRITE);

        assertThat(decision.denied()).isFalse();
    }

    /** 护栏只收紧不放宽：上游已拒绝的决策原样透传。 */
    @Test
    void upstreamDenialIsPassedThroughUnchanged() {
        PolicyDecision denied = new PolicyDecision(RiskCode.R5, false, true,
                List.of(new PolicyHit("POLICY_R5_FORBIDDEN_OR_INJECTION", "注入", RiskCode.R5)));

        PolicyDecision decision = guard.align("检查一下磁盘", "kylin.cleanup.execute", false, denied);

        assertThat(decision).isSameAs(denied);
    }

    /** 完全没有咨询标记的祈使句本来就不触发护栏。 */
    @Test
    void plainImperativeObjectiveIsNotTouched() {
        PolicyDecision decision = guard.align("清理 /var/cache 临时目录", "kylin.cleanup.execute", false, UPSTREAM_WRITE);

        assertThat(decision).isSameAs(UPSTREAM_WRITE);
    }

    /** 工具目录不认识的工具按写处理（fail-closed），咨询意图下仍被拦。 */
    @Test
    void unknownToolIsTreatedAsWriteAndBlockedUnderAdvisoryIntent() {
        PolicyDecision decision = guard.align("帮我看看磁盘占用", "vendor.unknown.mutate", UPSTREAM_WRITE);

        assertThat(decision.denied()).isTrue();
        assertThat(decision.policyHits()).extracting(PolicyHit::policyId)
                .contains(IntentAlignmentGuard.POLICY_INTENT_MISMATCH);
    }
}
