package com.superskidder.kylinops.backend.agentruntime.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillLoadTool;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillProgressiveDisclosure;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillSearchTool;
import com.superskidder.kylinops.backend.agentruntime.skill.SkillService;
import com.superskidder.kylinops.backend.agentruntime.vfs.SkillVfsResolver;
import com.superskidder.kylinops.backend.agentruntime.vfs.VfsReadTool;
import com.superskidder.kylinops.backend.agentruntime.vfs.VfsService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 三个新增只读 RUNTIME 工具在 ToolRegistry 里是"可被模型看见"的：
 * 能按 toolId 查到、算进只读集合、并且在只读 grant 的目录条目与 LlmTool 清单里出现。
 * AgentRoundExecutor 正是用 {@code allTools() → grantAllows → llmTool()} 组装模型可用工具的。
 */
class RuntimeReadOnlyToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolRegistry registry() {
        SkillService skillService = Mockito.mock(SkillService.class);
        SkillProgressiveDisclosure disclosure = new SkillProgressiveDisclosure(skillService);
        VfsService vfsService = new VfsService(List.of(
                new SkillVfsResolver((skillId, path) -> Optional.empty())));
        return new ToolRegistry(List.of(
                new ServiceStatusTool(MAPPER),
                new SkillSearchTool(disclosure, skillService, MAPPER),
                new SkillLoadTool(disclosure, skillService, MAPPER),
                new VfsReadTool(vfsService, MAPPER)));
    }

    @Test
    void newRuntimeToolsAreLookupableAndReadOnly() {
        ToolRegistry registry = registry();

        for (String toolId : List.of(SkillSearchTool.TOOL_ID, SkillLoadTool.TOOL_ID, VfsReadTool.TOOL_ID)) {
            NodeTool tool = registry.require(toolId);
            assertThat(tool.readOnly()).as("%s readOnly", toolId).isTrue();
            assertThat(tool.riskFloor()).as("%s riskFloor", toolId).isEqualTo("R0");
            assertThat(tool.llmTool()).as("%s llmTool", toolId).isNotNull();
        }
        assertThat(registry.readOnlyTools().stream().map(NodeTool::toolId))
                .contains(SkillSearchTool.TOOL_ID, SkillLoadTool.TOOL_ID, VfsReadTool.TOOL_ID);
        assertThat(registry.writableTools()).isEmpty();
        assertThat(registry.toolsRequiringApproval()).isEmpty();
    }

    @Test
    void readOnlyGrantStillExposesThemToTheModel() {
        ToolRegistry registry = registry();

        List<String> exposed = registry.catalogEntriesForGrant(Map.of("readOnly", true)).stream()
                .map(entry -> String.valueOf(entry.get("name")))
                .toList();

        assertThat(exposed).contains(SkillSearchTool.TOOL_ID, SkillLoadTool.TOOL_ID, VfsReadTool.TOOL_ID);
    }

    @Test
    void grantToolAllowlistStillNarrowsThem() {
        ToolRegistry registry = registry();

        List<String> exposed = registry.catalogEntriesForGrant(
                        Map.of("readOnly", true, "tools", List.of(SkillSearchTool.TOOL_ID))).stream()
                .map(entry -> String.valueOf(entry.get("name")))
                .toList();

        assertThat(exposed).containsExactly(SkillSearchTool.TOOL_ID);
    }
}
