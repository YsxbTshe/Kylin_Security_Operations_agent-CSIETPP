package com.superskidder.kylinops.backend.agentruntime.retention;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运行时数据留存清理（Phase 6.2）：已终态且超过保留期的 run 只清过程数据
 * （agent_run_event、context_frame MESSAGE 行、agent_run_checkpoint）。
 * agent_run 主记录与审计类表（agent_invocation/tool_invocation/execution_attempt/approval 等）保留。
 */
@Component
public class RuntimeRetentionService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRetentionService.class);
    private static final String TERMINAL_RUNS_SUBQUERY = """
            select id from agent_run
            where status in ('COMPLETED', 'FAILED', 'CANCELLED')
              and completed_at is not null and completed_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final long retentionDays;

    public RuntimeRetentionService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.runtime.retention-enabled:true}") boolean enabled,
            @Value("${app.runtime.retention-days:90}") long retentionDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
    }

    public record CleanupResult(int eventsDeleted, int messageFramesDeleted, int checkpointsDeleted) {}

    @Scheduled(fixedDelayString = "${app.runtime.retention-sweep-delay-ms:86400000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            CleanupResult result = cleanup(Instant.now());
            log.info("Runtime retention cleanup ({} days): {} event(s), {} message frame(s), {} checkpoint(s)",
                    retentionDays, result.eventsDeleted(), result.messageFramesDeleted(),
                    result.checkpointsDeleted());
        } catch (RuntimeException exception) {
            log.error("Runtime retention cleanup failed", exception);
        }
    }

    /** 清理终态且 completed_at 早于保留期截点的 run 的过程数据；主记录与审计不动。 */
    public CleanupResult cleanup(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(retentionDays)));
        int events = jdbcTemplate.update(
                "delete from agent_run_event where run_id in (" + TERMINAL_RUNS_SUBQUERY + ")", cutoff);
        int messages = jdbcTemplate.update(
                "delete from context_frame where frame_kind = 'MESSAGE' and run_id in ("
                        + TERMINAL_RUNS_SUBQUERY + ")", cutoff);
        int checkpoints = jdbcTemplate.update(
                "delete from agent_run_checkpoint where run_id in (" + TERMINAL_RUNS_SUBQUERY + ")", cutoff);
        return new CleanupResult(events, messages, checkpoints);
    }
}
