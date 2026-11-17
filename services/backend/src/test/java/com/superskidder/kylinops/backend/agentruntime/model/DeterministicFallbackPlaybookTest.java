package com.superskidder.kylinops.backend.agentruntime.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.tool.NodeTool;
import com.superskidder.kylinops.backend.agentruntime.tool.PerceptionCatalog;
import com.superskidder.kylinops.backend.agentruntime.tool.ServiceStatusTool;
import com.superskidder.kylinops.backend.policy.PolicyToolCatalog;
import com.superskidder.kylinops.backend.policy.ToolDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 确定性兜底剧本的选链与**只读硬约束**守护。
 *
 * <p>{@link #playbookNeverDeclaresAWriteTool()} 是本次改动的红线守护：兜底路径永远不得
 * 触发写操作。它拿**生产工具目录**（节点感知目录 + 策略引擎目录）逐条校验剧本声明的工具，
 * 任何一天有人往剧本里塞进一个写工具，构建立刻失败。
 */
class DeterministicFallbackPlaybookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void playbookNeverDeclaresAWriteTool() {
        Map<String, NodeTool> nodeCatalog = new LinkedHashMap<>();
        for (NodeTool tool : new PerceptionCatalog(MAPPER).tools()) {
            nodeCatalog.put(tool.toolId(), tool);
        }
        ServiceStatusTool serviceStatus = new ServiceStatusTool(MAPPER);
        nodeCatalog.put(serviceStatus.toolId(), serviceStatus);
        PolicyToolCatalog policyCatalog = new PolicyToolCatalog(null, MAPPER);

        List<String> declared = DeterministicFallbackPlaybook.declaredToolIds();
        assertThat(declared).isNotEmpty();
        for (String toolId : declared) {
            NodeTool tool = nodeCatalog.get(toolId);
            assertThat(tool).as("剧本工具必须存在于节点工具目录：%s", toolId).isNotNull();
            assertThat(tool.readOnly()).as("剧本工具必须只读：%s", toolId).isTrue();

            Optional<ToolDefinition> definition = policyCatalog.describeTools(List.of(toolId)).stream().findFirst();
            assertThat(definition).isPresent();
            assertThat(definition.get().readOnly()).as("策略目录同样必须判定为只读：%s", toolId).isTrue();
            assertThat(definition.get().riskCode()).as("剧本工具风险必须为 R0：%s", toolId).isEqualTo("R0");
        }
    }

    @Test
    void matchesTheDiskChainForDiskPressureObjectives() {
        DeterministicFallbackPlaybook.Chain chain =
                DeterministicFallbackPlaybook.match("磁盘占用过高，请分析并给出清理方案", null).orElseThrow();

        assertThat(chain.id()).isEqualTo("disk-cleanup-diagnosis");
        assertThat(toolIds(chain)).containsExactly(
                "kylin.disk.usage", "kylin.system.mounts", "kylin.disk.large_files", "kylin.cleanup.plan");
    }

    @Test
    void matchesTheSecurityChainForSecurityObjectives() {
        DeterministicFallbackPlaybook.Chain chain =
                DeterministicFallbackPlaybook.match("排查这台机器的安全加固状态与审计是否正常", null).orElseThrow();

        assertThat(chain.id()).isEqualTo("security-posture");
        assertThat(toolIds(chain)).contains("kylin.security.kysec_status", "kylin.security.auditd_status");
    }

    @Test
    void matchesTheProcessChainForResourceObjectives() {
        DeterministicFallbackPlaybook.Chain chain =
                DeterministicFallbackPlaybook.match("机器很卡，看看是哪个进程吃满了 CPU", null).orElseThrow();

        assertThat(chain.id()).isEqualTo("process-and-resource");
    }

    /** 服务名来自 run 的 targetScope：有就带上，没有就略过需要它的步骤（不编服务名）。 */
    @Test
    void serviceScopedStepsAreOnlyAddedWhenTheServiceIsKnown() {
        DeterministicFallbackPlaybook.Chain withService =
                DeterministicFallbackPlaybook.match("nginx 服务起不来，看下日志", "nginx").orElseThrow();
        DeterministicFallbackPlaybook.Chain withoutService =
                DeterministicFallbackPlaybook.match("nginx 服务起不来，看下日志", null).orElseThrow();

        assertThat(withService.id()).isEqualTo("service-and-log");
        assertThat(toolIds(withService)).contains("kylin.service.status", "kylin.log.journal_query");
        assertThat(withService.steps()).anySatisfy(step -> {
            if ("kylin.service.status".equals(step.toolId())) {
                assertThat(step.arguments()).containsEntry("service", "nginx");
            }
        });
        assertThat(withService.steps()).anySatisfy(step -> {
            if ("kylin.log.journal_query".equals(step.toolId())) {
                assertThat(step.arguments()).containsEntry("unit", "nginx");
            }
        });
        assertThat(toolIds(withoutService))
                .doesNotContain("kylin.service.status", "kylin.log.journal_query");
    }

    /** 匹配不到任何链就返回空——由调用方诚实失败，不瞎跑一条无关的链。 */
    @Test
    void unrelatedObjectiveMatchesNoChain() {
        assertThat(DeterministicFallbackPlaybook.match("帮我给财务部同事写一封邮件", null)).isEmpty();
        assertThat(DeterministicFallbackPlaybook.match("", null)).isEmpty();
        assertThat(DeterministicFallbackPlaybook.match(null, null)).isEmpty();
    }

    private List<String> toolIds(DeterministicFallbackPlaybook.Chain chain) {
        List<String> ids = new ArrayList<>();
        chain.steps().forEach(step -> ids.add(step.toolId()));
        return ids;
    }
}
