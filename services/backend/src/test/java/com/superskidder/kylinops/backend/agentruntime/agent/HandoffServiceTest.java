package com.superskidder.kylinops.backend.agentruntime.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 7 迭代 7.1 验收基线 18.3（正确性与恢复）——SubAgent 契约切片：
 * 权限衰减（子 grant ⊆ 父 grant）、预算衰减、以及「输出必须通过 outputSchema」
 * 的 handoff 结构级校验（缺必填字段/类型失配一律 FAILED，不落脏数据）。
 */
class HandoffServiceTest {

    private static final Map<String, Object> PARENT_GRANT = Map.of(
            "tools", List.of("kylin.service.status", "kylin.workspace.read"),
            "nodes", List.of("node-1"),
            "readOnly", false
    );
    private static final Map<String, Object> PARENT_BUDGET = Map.of(
            "maxModelInvocations", 10,
            "maxToolInvocations", 20,
            "maxInputTokens", 10_000,
            "maxOutputTokens", 5_000,
            "maxWallClockSeconds", 600
    );
    private static final Map<String, Object> PARENT_USAGE = Map.of(
            "modelInvocations", 4,
            "toolInvocations", 8,
            "inputTokens", 2_000,
            "outputTokens", 1_000
    );
    private static final Map<String, Object> OUTPUT_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("summary"),
            "properties", Map.of(
                    "summary", Map.of("type", "string"),
                    "exitCode", Map.of("type", "integer")
            )
    );

    private JdbcTemplate jdbc;
    private HandoffService service;
    private String runId;
    private String parentInstanceId;
    private String childInstanceId;
    private LeasedAgentRun parentRun;

    @BeforeEach
    void setUp() {
        jdbc = AgentTestDatabase.create();
        service = new HandoffService(jdbc, AgentTestFixtures.MAPPER, 0.5);
        runId = AgentTestFixtures.insertRun(jdbc, "RUNNING", PARENT_GRANT, PARENT_BUDGET, PARENT_USAGE);
        parentInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, null, 0, "RUNNING");
        childInstanceId = AgentTestFixtures.insertInstance(jdbc, runId, parentInstanceId, 1, "QUEUED");
        parentRun = AgentTestFixtures.leasedRun(runId, parentInstanceId, PARENT_GRANT, PARENT_BUDGET);
    }

    // --- 权限衰减 ---

    @Test
    void grantSubsetRequestIsAccepted() {
        Map<String, Object> child = service.attenuateGrant(PARENT_GRANT,
                Map.of("tools", List.of("kylin.service.status"), "readOnly", false));
        assertThat(child.get("tools")).isEqualTo(List.of("kylin.service.status"));
        assertThat(child.get("nodes")).isEqualTo(List.of("node-1"));
        assertThat(child.get("readOnly")).isEqualTo(false);
    }

    @Test
    void grantToolBeyondParentIsRejected() {
        assertThatThrownBy(() -> service.attenuateGrant(PARENT_GRANT,
                Map.of("tools", List.of("kylin.workspace.bash"))))
                .isInstanceOf(SubAgentContractException.class)
                .hasMessageContaining("kylin.workspace.bash");
    }

    @Test
    void grantNodeBeyondParentIsRejected() {
        assertThatThrownBy(() -> service.attenuateGrant(PARENT_GRANT,
                Map.of("nodes", List.of("node-2"))))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_GRANT_NOT_SUBSET");
    }

    @Test
    void readOnlyParentCannotGrantWrite() {
        Map<String, Object> readOnlyGrant = Map.of(
                "tools", List.of(), "nodes", List.of("node-1"), "readOnly", true);
        assertThatThrownBy(() -> service.attenuateGrant(readOnlyGrant, Map.of("readOnly", false)))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_GRANT_NOT_SUBSET");
    }

    @Test
    void absentRequestInheritsParentGrant() {
        Map<String, Object> child = service.attenuateGrant(PARENT_GRANT, null);
        assertThat(child).containsAllEntriesOf(PARENT_GRANT);
    }

    @Test
    void unrestrictedParentToolsAllowAnyRequest() {
        Map<String, Object> openGrant = Map.of("tools", List.of(), "nodes", List.of(), "readOnly", false);
        Map<String, Object> child = service.attenuateGrant(openGrant,
                Map.of("tools", List.of("kylin.workspace.bash")));
        assertThat(child.get("tools")).isEqualTo(List.of("kylin.workspace.bash"));
    }

    // --- 预算衰减 ---

    @Test
    void budgetWithinCapIsAccepted() {
        Map<String, Object> child = service.attenuateBudget(PARENT_BUDGET, PARENT_USAGE,
                Map.of("maxModelInvocations", 3));
        // remaining: 6/12/8000/4000/600，ratio 0.5 → 3/6/4000/2000/300
        assertThat(child).containsEntry("maxModelInvocations", 3L);
        assertThat(child).containsEntry("maxToolInvocations", 6L);
        assertThat(child).containsEntry("maxInputTokens", 4_000L);
        assertThat(child).containsEntry("maxOutputTokens", 2_000L);
        assertThat(child).containsEntry("maxWallClockSeconds", 300L);
    }

    @Test
    void budgetBeyondCapIsRejected() {
        assertThatThrownBy(() -> service.attenuateBudget(PARENT_BUDGET, PARENT_USAGE,
                Map.of("maxModelInvocations", 4)))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_BUDGET_EXCEEDED");
    }

    @Test
    void absentRequestGetsFullCap() {
        Map<String, Object> child = service.attenuateBudget(PARENT_BUDGET, PARENT_USAGE, null);
        assertThat(child).containsEntry("maxModelInvocations", 3L);
        assertThat(child).containsEntry("maxToolInvocations", 6L);
    }

    @Test
    void configurableRatioScalesCap() {
        HandoffService quarter = new HandoffService(jdbc, AgentTestFixtures.MAPPER, 0.25);
        Map<String, Object> child = quarter.attenuateBudget(PARENT_BUDGET, PARENT_USAGE, null);
        assertThat(child).containsEntry("maxModelInvocations", 1L);
        assertThat(child).containsEntry("maxToolInvocations", 3L);
    }

    @Test
    void unknownBudgetDimensionIsRejected() {
        assertThatThrownBy(() -> service.attenuateBudget(PARENT_BUDGET, PARENT_USAGE,
                Map.of("maxGpuHours", 1)))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_BUDGET_EXCEEDED");
    }

    // --- createHandoff 落库 ---

    @Test
    void createHandoffPersistsOpenContract() {
        HandoffService.Handoff handoff = service.createHandoff(parentRun, parentInstanceId, childInstanceId,
                "collect diagnostics", AgentSpecSnapshot.dynamic("instructions", List.of(), Map.of()),
                Map.of("tools", List.of("kylin.service.status")), Map.of("maxToolInvocations", 2), OUTPUT_SCHEMA);

        assertThat(handoff.status()).isEqualTo(HandoffService.STATUS_OPEN);
        assertThat(handoff.childGrant().get("tools")).isEqualTo(List.of("kylin.service.status"));
        assertThat(handoff.childBudget()).containsEntry("maxToolInvocations", 2L);

        Map<String, Object> row = jdbc.queryForMap(
                "select status, objective from handoff where id = ?", handoff.id());
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("objective")).isEqualTo("collect diagnostics");
    }

    @Test
    void createHandoffRejectsExcessiveBudget() {
        assertThatThrownBy(() -> service.createHandoff(parentRun, parentInstanceId, childInstanceId,
                "goal", AgentSpecSnapshot.dynamic("instructions", List.of(), Map.of()),
                null, Map.of("maxToolInvocations", 99), OUTPUT_SCHEMA))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_BUDGET_EXCEEDED");
        Integer count = jdbc.queryForObject("select count(*) from handoff", Integer.class);
        assertThat(count).isZero();
    }

    // --- 结果 schema 校验与状态流转 ---

    @Test
    void completeHandoffAcceptsConformingResult() {
        HandoffService.Handoff handoff = openHandoff();
        Map<String, Object> result = service.completeHandoff(handoff.id(),
                "{\"summary\":\"disk triage done\",\"exitCode\":0}");
        assertThat(result).containsEntry("summary", "disk triage done");
        assertThat(handoffStatus(handoff.id())).isEqualTo("COMPLETED");
    }

    @Test
    void completeHandoffRejectsMissingRequiredField() {
        HandoffService.Handoff handoff = openHandoff();
        assertThatThrownBy(() -> service.completeHandoff(handoff.id(), "{\"exitCode\":0}"))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_OUTPUT_SCHEMA_VIOLATION");
        assertThat(handoffStatus(handoff.id())).isEqualTo("FAILED");
    }

    @Test
    void completeHandoffRejectsWrongType() {
        HandoffService.Handoff handoff = openHandoff();
        assertThatThrownBy(() -> service.completeHandoff(handoff.id(), "{\"summary\":123}"))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_OUTPUT_SCHEMA_VIOLATION");
        assertThat(handoffStatus(handoff.id())).isEqualTo("FAILED");
    }

    @Test
    void completedHandoffCannotBeCompletedAgain() {
        HandoffService.Handoff handoff = openHandoff();
        service.completeHandoff(handoff.id(), "{\"summary\":\"done\"}");
        assertThatThrownBy(() -> service.completeHandoff(handoff.id(), "{\"summary\":\"again\"}"))
                .isInstanceOf(SubAgentContractException.class)
                .extracting(e -> ((SubAgentContractException) e).errorCode())
                .isEqualTo("SUB_AGENT_HANDOFF_NOT_OPEN");
    }

    @Test
    void cancelHandoffMovesOpenToCancelled() {
        HandoffService.Handoff handoff = openHandoff();
        service.cancelHandoff(handoff.id());
        assertThat(handoffStatus(handoff.id())).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.completeHandoff(handoff.id(), "{\"summary\":\"late\"}"))
                .isInstanceOf(SubAgentContractException.class);
    }

    @Test
    void validateResultIsStructuralOnly() {
        assertThat(service.validateResult(
                AgentTestFixtures.MAPPER.valueToTree(OUTPUT_SCHEMA),
                AgentTestFixtures.MAPPER.valueToTree(Map.of("summary", "ok", "extra", "ignored"))))
                .isEmpty();
    }

    private HandoffService.Handoff openHandoff() {
        return service.createHandoff(parentRun, parentInstanceId, childInstanceId,
                "goal", AgentSpecSnapshot.dynamic("instructions", List.of(), Map.of()),
                null, null, OUTPUT_SCHEMA);
    }

    private String handoffStatus(String handoffId) {
        return jdbc.queryForObject("select status from handoff where id = ?", String.class, handoffId);
    }
}
