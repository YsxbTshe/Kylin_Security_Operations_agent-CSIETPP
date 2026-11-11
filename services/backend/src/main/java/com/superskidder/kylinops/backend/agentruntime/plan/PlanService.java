package com.superskidder.kylinops.backend.agentruntime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.crypto.Sm3;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan 服务（Phase 6 迭代 6.6）：Plan 是绑定 run 的 Markdown 计划文档，版本不可变。
 * AMEND 只能基于最新版本并产生新版本；从某个 plan_version 可启动新 run，
 * 新 run 的 scope/grant/budget 相对源 run 只能收窄不能扩大（设计红线）。
 */
@Component
public class PlanService {

    public static final int MAX_CONTENT_BYTES = 64 * 1024;
    public static final int MAX_OBJECTIVE_LENGTH = 4_000;

    private static final List<String> GRANT_LIST_KEYS = List.of("tools", "nodes");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeStore store;

    public PlanService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AgentRuntimeStore store) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.store = store;
    }

    public record PlanVersionView(
            String id, String runId, int version, String contentDigest, String createdBy, Instant createdAt
    ) {}

    public record PlanVersionDetail(
            String id, String runId, int version, String contentMarkdown,
            String contentDigest, String createdBy, Instant createdAt
    ) {}

    public record LaunchSpec(
            String objective,
            String executionMode,
            Map<String, Object> targetScope,
            Map<String, Object> grant,
            Map<String, Object> budget,
            String modelProfileId,
            String reasoningLevel
    ) {}

    public List<PlanVersionView> listVersions(String tenantId, String runId) {
        requireRun(tenantId, runId);
        return jdbcTemplate.query("""
                select id, run_id, version, content_digest, created_by, created_at
                from plan_version where tenant_id = ? and run_id = ?
                order by version asc
                """, (rs, rowNum) -> new PlanVersionView(rs.getString("id"), rs.getString("run_id"),
                rs.getInt("version"), rs.getString("content_digest"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant()), tenantId, runId);
    }

    public PlanVersionDetail getVersion(String tenantId, String runId, int version) {
        requireRun(tenantId, runId);
        List<PlanVersionDetail> rows = jdbcTemplate.query("""
                select id, run_id, version, content_markdown, content_digest, created_by, created_at
                from plan_version where tenant_id = ? and run_id = ? and version = ?
                """, (rs, rowNum) -> new PlanVersionDetail(rs.getString("id"), rs.getString("run_id"),
                rs.getInt("version"), rs.getString("content_markdown"), rs.getString("content_digest"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()), tenantId, runId, version);
        return rows.stream().findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Plan 版本不存在: v" + version));
    }

    /** 创建新 plan 版本：版本号 = 现有最大 + 1；内容校验后连同 SM3 摘要落库。 */
    @Transactional
    public PlanVersionView createPlanVersion(String tenantId, String runId, String contentMarkdown, String principalUserId) {
        requireRun(tenantId, runId);
        String content = validateContent(contentMarkdown);
        int version = maxVersion(runId) + 1;
        PlanVersionView view = insertVersion(tenantId, runId, version, content, principalUserId);
        store.appendEvent(runId, tenantId, "PLAN_VERSION_CREATED", "HUMAN", principalUserId,
                Map.of("planVersionId", view.id(), "version", version, "contentDigest", view.contentDigest()));
        return view;
    }

    /** AMEND：baseVersion 必须存在且为最新版本（防止并发改写旧版本），产生新版本。 */
    @Transactional
    public PlanVersionView amendPlan(String tenantId, String runId, int baseVersion, String newContent, String principalUserId) {
        requireRun(tenantId, runId);
        String content = validateContent(newContent);
        PlanVersionDetail base = getVersion(tenantId, runId, baseVersion);
        int latest = maxVersion(runId);
        if (base.version() != latest) {
            throw new AppException(HttpStatus.CONFLICT,
                    "AMEND 必须基于最新版本: base=v" + baseVersion + " latest=v" + latest);
        }
        PlanVersionView view = insertVersion(tenantId, runId, latest + 1, content, principalUserId);
        store.appendEvent(runId, tenantId, "PLAN_VERSION_AMENDED", "HUMAN", principalUserId,
                Map.of("planVersionId", view.id(), "version", view.version(),
                        "baseVersion", baseVersion, "contentDigest", view.contentDigest()));
        return view;
    }

    /** 从 plan_version 启动新 run：scope/grant/budget 相对源 run 只能收窄，回填 source_plan_version_id。 */
    @Transactional
    public AgentRuntimeStore.CreatedRun launchRunFromPlan(String tenantId, String planVersionId, LaunchSpec spec, String principalUserId) {
        PlanVersionView plan = jdbcTemplate.query("""
                select id, run_id, version, content_digest, created_by, created_at
                from plan_version where tenant_id = ? and id = ?
                """, (rs, rowNum) -> new PlanVersionView(rs.getString("id"), rs.getString("run_id"),
                rs.getInt("version"), rs.getString("content_digest"), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant()), tenantId, planVersionId)
                .stream().findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Plan 版本不存在: " + planVersionId));
        AgentRuntimeStore.RunSecuritySnapshot source = store.findRunSecuritySnapshot(plan.runId())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Plan 源 run 不存在: " + plan.runId()));
        if (!Objects.equals(source.tenantId(), tenantId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "Plan 版本不存在: " + planVersionId);
        }
        if (spec == null || spec.objective() == null || spec.objective().isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "objective 不能为空");
        }
        if (spec.objective().length() > MAX_OBJECTIVE_LENGTH) {
            throw new AppException(HttpStatus.BAD_REQUEST, "objective 不能超过 " + MAX_OBJECTIVE_LENGTH + " 字符");
        }

        Map<String, Object> sourceScope = readMap(source.targetScopeJson());
        Map<String, Object> sourceGrant = readMap(source.grantSnapshotJson());
        Map<String, Object> sourceBudget = readMap(source.budgetJson());

        Map<String, Object> targetScope = spec.targetScope() != null ? spec.targetScope() : sourceScope;
        requireScopeNarrowed(sourceScope, targetScope);
        Map<String, Object> grant = spec.grant() != null ? spec.grant() : sourceGrant;
        requireGrantNarrowed(sourceGrant, grant);
        Map<String, Object> budget = spec.budget() != null ? spec.budget() : sourceBudget;
        requireBudgetNarrowed(sourceBudget, budget);

        String mode = spec.executionMode() != null && !spec.executionMode().isBlank()
                ? spec.executionMode() : source.executionMode();
        return store.createRunFromPlan(tenantId, principalUserId, plan.id(), spec.objective(), mode,
                targetScope, grant, budget, spec.modelProfileId(), spec.reasoningLevel());
    }

    /**
     * 结构化计划（Plan Canvas 数据源）：取最新版本正文，解析成阶段/步骤，
     * 再与该 run 的真实 {@code tool_invocation} 对账。
     *
     * <p><b>对账规则</b>：按 {@code queued_at} 顺序遍历真实调用，每条认领**第一个尚未认领、
     * 且 toolId 相同**的步骤。同一工具在计划里出现多次时，第 n 次调用对应第 n 个步骤。
     * 认领不到就说明这一步还没执行（PENDING），多出来的调用不影响计划——
     * 计划是计划，执行是执行，两边都不篡改对方。
     */
    public PlanStructure.PlanCanvasView getCanvas(String tenantId, String runId) {
        requireRun(tenantId, runId);
        int latest = maxVersion(runId);
        if (latest == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "该 run 还没有计划版本");
        }
        PlanVersionDetail detail = getVersion(tenantId, runId, latest);
        PlanStructureParser.ParsedPlan parsed = PlanStructureParser.parse(detail.contentMarkdown());

        List<Invocation> invocations = jdbcTemplate.query("""
                select id, tool_id, status, rejection_code
                from tool_invocation
                where tenant_id = ? and run_id = ?
                order by queued_at asc, id asc
                """, (rs, rowNum) -> new Invocation(rs.getString("id"), rs.getString("tool_id"),
                rs.getString("status"), rs.getString("rejection_code")), tenantId, runId);

        Set<String> claimed = new HashSet<>();
        List<PlanStructure.PlanPhaseView> phases = new ArrayList<>();
        int matched = 0;
        for (PlanStructure.PlanPhaseView phase : parsed.phases()) {
            List<PlanStructure.PlanStepView> steps = new ArrayList<>();
            for (PlanStructure.PlanStepView step : phase.steps()) {
                Invocation hit = step.toolId() == null ? null : invocations.stream()
                        .filter(inv -> !claimed.contains(inv.id()) && step.toolId().equalsIgnoreCase(inv.toolId()))
                        .findFirst().orElse(null);
                if (hit == null) {
                    steps.add(step);
                    continue;
                }
                claimed.add(hit.id());
                matched++;
                steps.add(new PlanStructure.PlanStepView(step.id(), step.title(), step.toolId(), step.nodeId(),
                        step.riskCode(), step.requiresApproval(), mapStatus(hit.status()), hit.id(),
                        hit.rejectionCode()));
            }
            phases.add(new PlanStructure.PlanPhaseView(phase.id(), phase.title(), phase.order(),
                    phase.dependsOn(), rollUp(steps), List.copyOf(steps)));
        }

        Integer baseVersion = latest > 1 ? latest - 1 : null;
        return new PlanStructure.PlanCanvasView(detail.id(), runId, latest, baseVersion, detail.contentDigest(),
                detail.createdAt(), detail.createdBy(), List.copyOf(phases),
                parsed.stepCount(), matched, parsed.unparsedLines());
    }

    private record Invocation(String id, String toolId, String status, String rejectionCode) {}

    /** tool_invocation.status → Canvas 状态。未知状态按 PENDING 处理，不猜。 */
    private static PlanStructure.NodeStatus mapStatus(String status) {
        if (status == null) {
            return PlanStructure.NodeStatus.PENDING;
        }
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCEEDED" -> PlanStructure.NodeStatus.DONE;
            case "RUNNING" -> PlanStructure.NodeStatus.RUNNING;
            case "FAILED" -> PlanStructure.NodeStatus.FAILED;
            case "REJECTED", "CANCELLED" -> PlanStructure.NodeStatus.SKIPPED;
            default -> PlanStructure.NodeStatus.PENDING;
        };
    }

    /**
     * 阶段状态由步骤汇总：有失败即失败；有在跑即在跑；全部落地（非 PENDING）即完成；
     * 部分落地也算在跑；一个都没落地才是 PENDING。空阶段按 PENDING。
     */
    private static PlanStructure.NodeStatus rollUp(List<PlanStructure.PlanStepView> steps) {
        if (steps.isEmpty()) {
            return PlanStructure.NodeStatus.PENDING;
        }
        boolean anyFailed = steps.stream().anyMatch(s -> s.status() == PlanStructure.NodeStatus.FAILED);
        if (anyFailed) {
            return PlanStructure.NodeStatus.FAILED;
        }
        boolean anyRunning = steps.stream().anyMatch(s -> s.status() == PlanStructure.NodeStatus.RUNNING);
        long settled = steps.stream().filter(s -> s.status() != PlanStructure.NodeStatus.PENDING).count();
        if (anyRunning || (settled > 0 && settled < steps.size())) {
            return PlanStructure.NodeStatus.RUNNING;
        }
        if (settled == steps.size()) {
            boolean allSkipped = steps.stream().allMatch(s -> s.status() == PlanStructure.NodeStatus.SKIPPED);
            return allSkipped ? PlanStructure.NodeStatus.SKIPPED : PlanStructure.NodeStatus.DONE;
        }
        return PlanStructure.NodeStatus.PENDING;
    }

    private PlanVersionView insertVersion(String tenantId, String runId, int version, String content, String principalUserId) {
        String id = "plan-" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        String digest = Sm3.hex(content);
        try {
            jdbcTemplate.update("""
                    insert into plan_version (
                        id, run_id, tenant_id, version, content_markdown, content_digest, created_by, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, runId, tenantId, version, content, digest, principalUserId, Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw new AppException(HttpStatus.CONFLICT, "Plan 版本并发冲突，请基于最新版本重试");
        }
        return new PlanVersionView(id, runId, version, digest, principalUserId, now);
    }

    private void requireRun(String tenantId, String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from agent_run where id = ? and tenant_id = ?", Integer.class, runId, tenantId);
        if (count == null || count == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "AgentRun 不存在: " + runId);
        }
    }

    private int maxVersion(String runId) {
        Integer max = jdbcTemplate.queryForObject(
                "select coalesce(max(version), 0) from plan_version where run_id = ?", Integer.class, runId);
        return max == null ? 0 : max;
    }

    private String validateContent(String contentMarkdown) {
        if (contentMarkdown == null || contentMarkdown.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "计划内容不能为空");
        }
        if (contentMarkdown.indexOf('\u0000') >= 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "计划内容不允许包含 null 字节");
        }
        if (contentMarkdown.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new AppException(HttpStatus.BAD_REQUEST, "计划内容不能超过 " + MAX_CONTENT_BYTES + " 字节");
        }
        return contentMarkdown;
    }

    /** scope 只允许收窄：源约束不得丢弃、列表值只允许子集、其余值必须相等；新增键视为附加约束。 */
    private void requireScopeNarrowed(Map<String, Object> source, Map<String, Object> requested) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (!requested.containsKey(key)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "target scope 不能放宽: 缺少源约束 " + key);
            }
            Object sourceValue = entry.getValue();
            Object requestedValue = requested.get(key);
            if (Objects.deepEquals(sourceValue, requestedValue)) {
                continue;
            }
            if (sourceValue instanceof List<?> sourceList && requestedValue instanceof List<?> requestedList
                    && sourceList.containsAll(requestedList)) {
                continue;
            }
            throw new AppException(HttpStatus.BAD_REQUEST, "target scope 不能放宽: " + key + " 超出源 run 范围");
        }
    }

    /** grant 只允许收窄：tools/nodes 子集、readOnly 只能收紧、源维度不得丢弃、未知维度拒绝。 */
    private void requireGrantNarrowed(Map<String, Object> source, Map<String, Object> requested) {
        for (String key : source.keySet()) {
            if (!requested.containsKey(key)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "grant 不能放宽: 缺少源维度 " + key);
            }
        }
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            String key = entry.getKey();
            Object requestedValue = entry.getValue();
            Object sourceValue = source.get(key);
            if (GRANT_LIST_KEYS.contains(key)) {
                List<?> requestedList = asList(requestedValue, "grant." + key);
                if (sourceValue == null) {
                    continue;
                }
                List<?> sourceList = asList(sourceValue, "source grant." + key);
                if (!sourceList.containsAll(requestedList)) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "grant 不能放宽: " + key + " 超出源 run 授权");
                }
            } else if ("readOnly".equals(key)) {
                if (!(requestedValue instanceof Boolean requestedReadOnly)) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "grant.readOnly 必须是布尔值");
                }
                boolean sourceReadOnly = Boolean.TRUE.equals(sourceValue);
                if (sourceReadOnly && !requestedReadOnly) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "grant 不能放宽: readOnly 不可由 true 放宽为 false");
                }
            } else {
                if (sourceValue == null || !Objects.deepEquals(sourceValue, requestedValue)) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "grant 不能放宽: 不支持的维度 " + key);
                }
            }
        }
    }

    /** budget 只允许收窄：每个维度必须存在于源预算且数值 ≤ 源值。 */
    private void requireBudgetNarrowed(Map<String, Object> source, Map<String, Object> requested) {
        for (Map.Entry<String, Object> entry : requested.entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof Number requestedValue)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "budget." + key + " 必须是数值");
            }
            Object sourceValue = source.get(key);
            if (!(sourceValue instanceof Number sourceNumber)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "budget 不能扩大: 源预算无维度 " + key);
            }
            if (requestedValue.longValue() > sourceNumber.longValue()) {
                throw new AppException(HttpStatus.BAD_REQUEST,
                        "budget 不能扩大: " + key + " 超出源预算 " + sourceNumber.longValue());
            }
        }
    }

    private List<?> asList(Object value, String label) {
        if (!(value instanceof List<?> list)) {
            throw new AppException(HttpStatus.BAD_REQUEST, label + " 必须是数组");
        }
        return list;
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "run 快照 JSON 无效");
        }
    }
}
