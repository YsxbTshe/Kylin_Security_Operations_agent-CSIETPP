package com.superskidder.kylinops.backend.incident;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 遏制状态巡检：把 CONTAINING 的事件按其关联处置任务（Agent run）的真实终态推进到 CONTAINED。
 *
 * <p>此前 {@code CONTAINED} 无任何可达路径 —— {@code markContained} 有实现有单测但没有调用方，
 * {@code contained_at} 永远为空，大屏「遏制均时」恒空。这里补上唯一缺失的那一环。
 *
 * <p>为什么是巡检而不是执行内核回调：事件闭环不应把自己塞进 Agent 执行内核
 * （{@code AgentRoundExecutor} 是安全关键路径，为一个统计字段给它加依赖不划算）。
 * 巡检读的是 {@code agent_run} 已落库的终态，判据同样真实，只是有一个轮询周期的观测延迟；
 * 而 {@code contained_at} 取 run 自己的 {@code completed_at}，
 * 因此<b>度量本身不含巡检延迟</b>——大屏上的遏制均时是处置真正完成的时刻，不是被发现的时刻。
 *
 * <p>只推进「关联 run 成功完成」的事件。失败/取消的 run 不推进：处置没成功就不叫遏制，
 * 事件留在 CONTAINING 等人接手，宁可诚实地空着也不造一个好看的数字。
 */
@Component
public class IncidentContainmentReconciler {

    private static final Logger log = LoggerFactory.getLogger(IncidentContainmentReconciler.class);
    private static final int SWEEP_BATCH_LIMIT = 200;

    private final JdbcTemplate jdbcTemplate;
    private final IncidentService incidentService;
    private final boolean enabled;

    public IncidentContainmentReconciler(
            JdbcTemplate jdbcTemplate,
            IncidentService incidentService,
            @Value("${app.incident.containment-sweep-enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.incidentService = incidentService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.incident.containment-sweep-delay-ms:30000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            List<String> advanced = reconcile();
            if (!advanced.isEmpty()) {
                log.info("遏制巡检推进 {} 起事件到 CONTAINED: {}", advanced.size(), advanced);
            }
        } catch (RuntimeException exception) {
            log.error("遏制巡检失败", exception);
        }
    }

    /**
     * 一次巡检：CONTAINING 且关联 run 已成功完成的事件 → CONTAINED。
     *
     * @return 本次推进的事件 id
     */
    public List<String> reconcile() {
        record Candidate(String organizationId, String incidentId, LocalDateTime completedAt) {}
        List<Candidate> candidates = jdbcTemplate.query("""
                select i.organization_id, i.id as incident_id, r.completed_at
                from app_incident i
                join agent_run r on r.id = i.task_id and r.tenant_id = i.organization_id
                where i.status = 'CONTAINING'
                  and r.status = 'COMPLETED' and r.outcome = 'SUCCEEDED' and r.completed_at is not null
                order by r.completed_at asc
                limit %d
                """.formatted(SWEEP_BATCH_LIMIT), (rs, rowNum) -> {
            Timestamp completedAt = rs.getTimestamp("completed_at");
            return new Candidate(rs.getString("organization_id"), rs.getString("incident_id"),
                    completedAt == null ? null : completedAt.toLocalDateTime());
        });

        List<String> advanced = new ArrayList<>();
        for (Candidate candidate : candidates) {
            try {
                incidentService.markContained(candidate.organizationId(), candidate.incidentId(),
                        candidate.completedAt());
                advanced.add(candidate.incidentId());
            } catch (RuntimeException exception) {
                log.error("事件 {} 推进 CONTAINED 失败", candidate.incidentId(), exception);
            }
        }
        return List.copyOf(advanced);
    }
}
