package com.superskidder.kylinops.backend.agentruntime.plan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 计划 Markdown → 阶段/步骤 的解析约定。 */
class PlanStructureParserTest {

    @Test
    void parsesPhasesStepsToolsRiskNodeAndApproval() {
        String md = """
                # 磁盘清理计划

                ## 诊断
                - [ ] 查看磁盘占用 `kylin.disk.usage` @node-kylin-01 (R0)
                - 找出大文件 `kylin.disk.large_files`

                ## 处置
                > depends: 诊断
                - [ ] 清理过期日志 `kylin.cleanup.execute` @node-kylin-01 (R3) [需审批]
                """;

        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse(md);

        assertThat(parsed.phases()).hasSize(2);
        assertThat(parsed.stepCount()).isEqualTo(3);

        PlanStructure.PlanPhaseView diag = parsed.phases().get(0);
        assertThat(diag.title()).isEqualTo("诊断");
        assertThat(diag.dependsOn()).isEmpty();
        assertThat(diag.steps()).hasSize(2);

        PlanStructure.PlanStepView first = diag.steps().get(0);
        assertThat(first.title()).isEqualTo("查看磁盘占用");
        assertThat(first.toolId()).isEqualTo("kylin.disk.usage");
        assertThat(first.nodeId()).isEqualTo("node-kylin-01");
        assertThat(first.riskCode()).isEqualTo("R0");
        assertThat(first.requiresApproval()).isFalse();
        // 未对账前一律 PENDING，绝不从计划文本里猜状态。
        assertThat(first.status()).isEqualTo(PlanStructure.NodeStatus.PENDING);
        assertThat(first.toolInvocationId()).isNull();

        PlanStructure.PlanPhaseView act = parsed.phases().get(1);
        assertThat(act.dependsOn()).containsExactly(diag.id());
        assertThat(act.steps().get(0).requiresApproval()).isTrue();
        assertThat(act.steps().get(0).riskCode()).isEqualTo("R3");
    }

    /** `- [x]` 只是作者笔记，不得被当成「已完成」——状态只能来自执行记录。 */
    @Test
    void checkedBoxDoesNotImplyDoneStatus() {
        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse("""
                ## 阶段
                - [x] 作者自己勾了 `kylin.disk.usage`
                """);

        assertThat(parsed.phases().get(0).steps().get(0).status())
                .isEqualTo(PlanStructure.NodeStatus.PENDING);
    }

    /** 围栏代码块内的示例命令不能被解析成步骤，否则示例会污染 Canvas。 */
    @Test
    void ignoresFencedCodeBlocks() {
        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse("""
                ## 阶段
                - 真步骤 `kylin.disk.usage`

                ```bash
                - 这是代码块里的示例，不是步骤
                ## 这也不是阶段
                ```
                """);

        assertThat(parsed.phases()).hasSize(1);
        assertThat(parsed.phases().get(0).steps()).hasSize(1);
        assertThat(parsed.phases().get(0).steps().get(0).title()).isEqualTo("真步骤");
    }

    /** 没有 ## 标题时不能得到空 Canvas，步骤归入隐式阶段。 */
    @Test
    void stepsWithoutHeadingFallIntoImplicitPhase() {
        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse("""
                - 第一步 `kylin.disk.usage`
                - 第二步 `kylin.process.list`
                """);

        assertThat(parsed.phases()).hasSize(1);
        assertThat(parsed.phases().get(0).title()).isEqualTo("计划");
        assertThat(parsed.phases().get(0).steps()).hasSize(2);
    }

    /** 指向不存在阶段的依赖必须丢弃，不能在画布上留一条指向空气的连线。 */
    @Test
    void dropsDanglingDependencies() {
        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse("""
                ## 处置
                > depends: 不存在的阶段
                - 步骤 `kylin.disk.usage`
                """);

        assertThat(parsed.phases().get(0).dependsOn()).isEmpty();
    }

    /** 标识符里的下划线不能被当成 Markdown 强调剥掉，否则会把标题改写成错的词。 */
    @Test
    void keepsUnderscoresInsideIdentifiers() {
        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse("""
                ## 回退
                - 按 rollback_id 恢复 `kylin.cleanup.rollback`
                """);

        assertThat(parsed.phases().get(0).steps().get(0).title()).isEqualTo("按 rollback_id 恢复");
    }

    /** 普通说明文字如实计数，供前端提示「有多少内容没进 Canvas」。 */
    @Test
    void countsUnparsedProseLines() {
        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse("""
                ## 阶段
                这一段是说明文字，不是步骤。
                这也是。
                - 唯一的步骤 `kylin.disk.usage`
                """);

        assertThat(parsed.unparsedLines()).isEqualTo(2);
        assertThat(parsed.stepCount()).isEqualTo(1);
    }
}
