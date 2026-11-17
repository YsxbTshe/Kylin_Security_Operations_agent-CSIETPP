package com.superskidder.kylinops.backend.agentruntime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 6.6 Plan 服务：版本递增与 SM3 摘要、内容校验、AMEND 乐观检查、版本不可变、
 * launch 回填 source_plan_version_id 且 scope/grant/budget 只能收窄。
 */
class PlanServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> SOURCE_SCOPE = Map.of(
            "nodeId", "node-1", "service", "sshd", "ports", List.of(22, 80));
    private static final Map<String, Object> SOURCE_GRANT = Map.of(
            "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);

    private JdbcTemplate jdbc;
    private AgentRuntimeStore store;
    private PlanService planService;
    private String runId;

    @BeforeEach
    void setUp() {
        jdbc = PlanTestDatabase.create();
        store = new AgentRuntimeStore(jdbc, MAPPER, new AgentRunEventNotifier(),
                jdbc.getDataSource(), "worker-test", 30);
        planService = new PlanService(jdbc, MAPPER, store);
        runId = store.createWriteRun(PlanTestDatabase.TENANT_ID, PlanTestDatabase.USER_ID,
                "harden sshd configuration", "CAUTIOUS", SOURCE_SCOPE, SOURCE_GRANT, null, null).runId();
    }

    private void assertAppException(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AppException.class)
                .satisfies(exception -> assertThat(((AppException) exception).getStatus()).isEqualTo(status));
    }

    @Test
    void createAssignsIncrementingVersionsWithSm3Digest() {
        PlanService.PlanVersionView v1 = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# Plan\nstep 1", PlanTestDatabase.USER_ID);
        PlanService.PlanVersionView v2 = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# Plan\nstep 1\nstep 2", PlanTestDatabase.USER_ID);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v1.contentDigest()).isEqualTo(Sm3.hex("# Plan\nstep 1"));
        assertThat(v2.contentDigest()).isEqualTo(Sm3.hex("# Plan\nstep 1\nstep 2"));
        assertThat(v1.createdBy()).isEqualTo(PlanTestDatabase.USER_ID);

        List<PlanService.PlanVersionView> versions = planService.listVersions(PlanTestDatabase.TENANT_ID, runId);
        assertThat(versions).extracting(PlanService.PlanVersionView::version).containsExactly(1, 2);

        PlanService.PlanVersionDetail detail = planService.getVersion(PlanTestDatabase.TENANT_ID, runId, 1);
        assertThat(detail.contentMarkdown()).isEqualTo("# Plan\nstep 1");
        assertThat(detail.id()).isEqualTo(v1.id());

        List<String> events = jdbc.queryForList(
                "select event_type from agent_run_event where run_id = ? order by sequence_no",
                String.class, runId);
        assertThat(events).contains("RUN_CREATED");
        assertThat(events.stream().filter("PLAN_VERSION_CREATED"::equals).count()).isEqualTo(2);
    }

    @Test
    void createRejectsBlankOversizedOrNullByteContent() {
        assertAppException(() -> planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "   ", PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);
        assertAppException(() -> planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "bad\u0000content", PlanTestDatabase.USER_ID),
                HttpStatus.BAD_REQUEST);
        String oversized = "a".repeat(PlanService.MAX_CONTENT_BYTES + 1);
        assertAppException(() -> planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, oversized, PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);

        String atLimit = "a".repeat(PlanService.MAX_CONTENT_BYTES);
        PlanService.PlanVersionView view = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, atLimit, PlanTestDatabase.USER_ID);
        assertThat(view.version()).isEqualTo(1);
    }

    @Test
    void createRejectsUnknownOrCrossTenantRun() {
        assertAppException(() -> planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, "run-missing", "# Plan", PlanTestDatabase.USER_ID),
                HttpStatus.NOT_FOUND);
        assertAppException(() -> planService.createPlanVersion(
                PlanTestDatabase.OTHER_TENANT_ID, runId, "# Plan", PlanTestDatabase.OTHER_USER_ID),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void amendOnLatestVersionSucceedsAndRecordsBaseVersion() {
        PlanService.PlanVersionView v1 = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# v1", PlanTestDatabase.USER_ID);
        PlanService.PlanVersionView v2 = planService.amendPlan(
                PlanTestDatabase.TENANT_ID, runId, v1.version(), "# v2", PlanTestDatabase.USER_ID);

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.contentDigest()).isEqualTo(Sm3.hex("# v2"));
        assertThat(planService.getVersion(PlanTestDatabase.TENANT_ID, runId, 2).contentMarkdown()).isEqualTo("# v2");
        List<String> events = jdbc.queryForList(
                "select event_type from agent_run_event where run_id = ? order by sequence_no",
                String.class, runId);
        assertThat(events).contains("PLAN_VERSION_AMENDED");
    }

    @Test
    void amendOnStaleVersionIsRejectedForConcurrencyProtection() {
        PlanService.PlanVersionView v1 = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# v1", PlanTestDatabase.USER_ID);
        planService.amendPlan(PlanTestDatabase.TENANT_ID, runId, v1.version(), "# v2", PlanTestDatabase.USER_ID);

        assertAppException(() -> planService.amendPlan(
                PlanTestDatabase.TENANT_ID, runId, 1, "# v2 conflict", PlanTestDatabase.USER_ID),
                HttpStatus.CONFLICT);
        assertAppException(() -> planService.amendPlan(
                PlanTestDatabase.TENANT_ID, runId, 99, "# missing base", PlanTestDatabase.USER_ID),
                HttpStatus.NOT_FOUND);
        assertThat(planService.listVersions(PlanTestDatabase.TENANT_ID, runId))
                .extracting(PlanService.PlanVersionView::version).containsExactly(1, 2);
    }

    @Test
    void versionsAreImmutableAcrossAmendments() {
        String original = "# v1\noriginal content";
        PlanService.PlanVersionView v1 = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, original, PlanTestDatabase.USER_ID);
        planService.amendPlan(PlanTestDatabase.TENANT_ID, runId, 1, "# v2 rewritten", PlanTestDatabase.USER_ID);
        planService.amendPlan(PlanTestDatabase.TENANT_ID, runId, 2, "# v3 rewritten", PlanTestDatabase.USER_ID);

        PlanService.PlanVersionDetail reread = planService.getVersion(PlanTestDatabase.TENANT_ID, runId, 1);
        assertThat(reread.contentMarkdown()).isEqualTo(original);
        assertThat(reread.contentDigest()).isEqualTo(Sm3.hex(original));
        assertThat(reread.id()).isEqualTo(v1.id());
        Integer v1Rows = jdbc.queryForObject(
                "select count(*) from plan_version where run_id = ? and version = 1", Integer.class, runId);
        assertThat(v1Rows).isEqualTo(1);
    }

    @Test
    void launchCreatesQueuedRunBackfilledWithSourcePlanVersionId() {
        PlanService.PlanVersionView plan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# launch plan", PlanTestDatabase.USER_ID);

        AgentRuntimeStore.CreatedRun launched = planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("execute the plan", "CAUTIOUS",
                        Map.of("nodeId", "node-1", "service", "sshd", "ports", List.of(22)),
                        null, null, null, null),
                PlanTestDatabase.USER_ID);

        Map<String, Object> row = jdbc.queryForMap("""
                select source_plan_version_id, objective, status, execution_mode, target_scope_json,
                       grant_snapshot_json, budget_json
                from agent_run where id = ?
                """, launched.runId());
        assertThat(row.get("source_plan_version_id")).isEqualTo(plan.id());
        assertThat(row.get("objective")).isEqualTo("execute the plan");
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("execution_mode")).isEqualTo("CAUTIOUS");
        assertThat(store.findRun(PlanTestDatabase.TENANT_ID, launched.runId())).isPresent();
        List<String> events = jdbc.queryForList(
                "select payload_json from agent_run_event where run_id = ? and event_type = 'RUN_CREATED'",
                String.class, launched.runId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).contains(plan.id());
    }

    @Test
    void launchInheritsSourceScopeGrantBudgetWhenUnspecified() {
        PlanService.PlanVersionView plan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# inherit", PlanTestDatabase.USER_ID);

        AgentRuntimeStore.CreatedRun launched = planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("inherit everything", null, null, null, null, null, null),
                PlanTestDatabase.USER_ID);

        Map<String, Object> row = jdbc.queryForMap("""
                select execution_mode, target_scope_json, grant_snapshot_json, budget_json
                from agent_run where id = ?
                """, launched.runId());
        AgentRuntimeStore.RunSecuritySnapshot source = store.findRunSecuritySnapshot(runId).orElseThrow();
        assertThat(row.get("execution_mode")).isEqualTo(source.executionMode());
        assertThat(row.get("target_scope_json")).isEqualTo(source.targetScopeJson());
        assertThat(row.get("grant_snapshot_json")).isEqualTo(source.grantSnapshotJson());
        assertThat(row.get("budget_json")).isEqualTo(source.budgetJson());
    }

    @Test
    void launchAllowsScopeNarrowingButRejectsExpansion() {
        PlanService.PlanVersionView plan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# scope", PlanTestDatabase.USER_ID);

        Map<String, Object> narrowed = Map.of(
                "nodeId", "node-1", "service", "sshd", "ports", List.of(22), "unit", "sshd.service");
        AgentRuntimeStore.CreatedRun launched = planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("narrowed", null, narrowed, null, null, null, null),
                PlanTestDatabase.USER_ID);
        assertThat(launched.runId()).isNotBlank();

        Map<String, Object> droppedConstraint = Map.of("nodeId", "node-1", "ports", List.of(22, 80));
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("dropped service", null, droppedConstraint, null, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);

        Map<String, Object> changedValue = Map.of("nodeId", "node-2", "service", "sshd", "ports", List.of(22, 80));
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("other node", null, changedValue, null, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);

        Map<String, Object> widenedList = Map.of("nodeId", "node-1", "service", "sshd", "ports", List.of(22, 80, 443));
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("extra port", null, widenedList, null, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);
    }

    @Test
    void launchRejectsGrantExpansionAndAllowsSubset() {
        PlanService.PlanVersionView plan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# grant", PlanTestDatabase.USER_ID);

        Map<String, Object> narrowedGrant = Map.of(
                "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", true);
        AgentRuntimeStore.CreatedRun launched = planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("readOnly grant", null, null, narrowedGrant, null, null, null),
                PlanTestDatabase.USER_ID);
        assertThat(launched.runId()).isNotBlank();

        Map<String, Object> relaxedReadOnly = Map.of(
                "tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", false);
        String readOnlyRunId = store.createWriteRun(PlanTestDatabase.TENANT_ID, PlanTestDatabase.USER_ID,
                "read only source", "PLAN", SOURCE_SCOPE,
                Map.of("tools", List.of("kylin.service.status"), "nodes", List.of("node-1"), "readOnly", true),
                null, null).runId();
        PlanService.PlanVersionView readOnlyPlan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, readOnlyRunId, "# readOnly", PlanTestDatabase.USER_ID);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, readOnlyPlan.id(),
                new PlanService.LaunchSpec("relax readOnly", null, null, relaxedReadOnly, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);

        Map<String, Object> extraTool = Map.of(
                "tools", List.of("kylin.service.status", "kylin.node.exec"), "nodes", List.of("node-1"),
                "readOnly", false);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("extra tool", null, null, extraTool, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);

        Map<String, Object> droppedDimension = Map.of("readOnly", false);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("dropped tools", null, null, droppedDimension, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);
    }

    @Test
    void launchRejectsBudgetExpansion() {
        PlanService.PlanVersionView plan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# budget", PlanTestDatabase.USER_ID);

        Map<String, Object> narrowedBudget = Map.of("maxToolInvocations", 5, "maxWallClockSeconds", 60);
        AgentRuntimeStore.CreatedRun launched = planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("narrowed budget", null, null, null, narrowedBudget, null, null),
                PlanTestDatabase.USER_ID);
        assertThat(launched.runId()).isNotBlank();

        Map<String, Object> expanded = Map.of("maxToolInvocations", 50);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("expanded budget", null, null, null, expanded, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);

        Map<String, Object> unknownDimension = Map.of("maxZombies", 1);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("unknown dimension", null, null, null, unknownDimension, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);
    }

    @Test
    void launchRequiresObjectiveAndExistingPlanVersion() {
        PlanService.PlanVersionView plan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# objective", PlanTestDatabase.USER_ID);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("  ", null, null, null, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.BAD_REQUEST);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.TENANT_ID, "plan-missing",
                new PlanService.LaunchSpec("objective", null, null, null, null, null, null),
                PlanTestDatabase.USER_ID), HttpStatus.NOT_FOUND);
    }

    @Test
    void launchRejectsCrossTenantPlanVersion() {
        PlanService.PlanVersionView plan = planService.createPlanVersion(
                PlanTestDatabase.TENANT_ID, runId, "# tenant isolation", PlanTestDatabase.USER_ID);
        assertAppException(() -> planService.launchRunFromPlan(PlanTestDatabase.OTHER_TENANT_ID, plan.id(),
                new PlanService.LaunchSpec("steal plan", null, null, null, null, null, null),
                PlanTestDatabase.OTHER_USER_ID), HttpStatus.NOT_FOUND);
    }
}
