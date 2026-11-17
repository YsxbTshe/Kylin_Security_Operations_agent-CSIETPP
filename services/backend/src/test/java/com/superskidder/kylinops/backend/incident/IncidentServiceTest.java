package com.superskidder.kylinops.backend.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.common.AppException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 事件响应闭环编排单测：幂等去重、研判话术、状态流转、误报拒绝、时效度量。 */
class IncidentServiceTest {

    private JdbcTemplate jdbcTemplate;
    private IncidentService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:incident;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        service = new IncidentService(jdbcTemplate);
    }

    @Test
    void intakeCreatesNewIncidentThenMergesDuplicate() {
        IncidentIntakeResult first = service.intake("org-alpha", intake("203.0.113.66"));
        assertThat(first.merged()).isFalse();
        assertThat(first.incident().status()).isEqualTo("NEW");
        assertThat(first.incident().occurrenceCount()).isEqualTo(1);

        // 相同 dedupe 的重复信号 → 合并计数，不产生新事件（重复事件幂等）。
        IncidentIntakeResult second = service.intake("org-alpha", intake("203.0.113.66"));
        assertThat(second.merged()).isTrue();
        assertThat(second.incident().id()).isEqualTo(first.incident().id());
        assertThat(second.incident().occurrenceCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(1) from app_incident", Long.class)).isEqualTo(1L);
    }

    @Test
    void intakeAfterTerminalCreatesFreshIncident() {
        IncidentIntakeResult first = service.intake("org-alpha", intake("203.0.113.66"));
        service.resolve("org-alpha", first.incident().id());

        // 命中的既有事件已终态 → 作为新事件，不复活已闭环事件。
        IncidentIntakeResult again = service.intake("org-alpha", intake("203.0.113.66"));
        assertThat(again.merged()).isFalse();
        assertThat(again.incident().id()).isNotEqualTo(first.incident().id());
        assertThat(jdbcTemplate.queryForObject("select count(1) from app_incident", Long.class)).isEqualTo(2L);
    }

    @Test
    void beginTriageSetsTriagedAndBuildsForensicsPrompt() {
        String id = service.intake("org-alpha", intake("203.0.113.66")).incident().id();

        IncidentTriagePlan plan = service.beginTriage("org-alpha", id);
        assertThat(plan.targetNodeId()).isEqualTo("node-kylin-01");
        assertThat(plan.prompt()).contains("203.0.113.66").contains("阻断").contains("认证异常");

        IncidentDetail detail = service.get("org-alpha", id);
        assertThat(detail.summary().status()).isEqualTo("TRIAGED");
        assertThat(detail.acknowledgedAt()).isNotNull();
    }

    @Test
    void linkTaskMovesToContainingAndFalsePositiveThenRejected() {
        String id = service.intake("org-alpha", intake("203.0.113.66")).incident().id();
        service.beginTriage("org-alpha", id);
        service.linkTask("org-alpha", id, "host-task-abc");

        IncidentDetail detail = service.get("org-alpha", id);
        assertThat(detail.summary().status()).isEqualTo("CONTAINING");
        assertThat(detail.summary().taskId()).isEqualTo("host-task-abc");

        // 已发起处置的事件不可再标记误报。
        assertThatThrownBy(() -> service.markFalsePositive("org-alpha", id, "不是误报"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void falsePositiveClosesUntriagedIncident() {
        String id = service.intake("org-alpha", intake("198.51.100.7")).incident().id();
        IncidentDetail detail = service.markFalsePositive("org-alpha", id, "扫描器误报");
        assertThat(detail.summary().status()).isEqualTo("FALSE_POSITIVE");
        assertThat(detail.resolvedAt()).isNotNull();
    }

    @Test
    void beginTriageOnTerminalIncidentThrows() {
        String id = service.intake("org-alpha", intake("203.0.113.66")).incident().id();
        service.resolve("org-alpha", id);
        assertThatThrownBy(() -> service.beginTriage("org-alpha", id)).isInstanceOf(AppException.class);
    }

    @Test
    void metricsComputeResolveSecondsOverResolvedIncidents() {
        // 直接插入一条时间已知的已解决事件：detected 09:00, acknowledged 09:02, contained 09:06, resolved 09:20。
        jdbcTemplate.update("""
                insert into app_incident (id, organization_id, title, severity, category, source_ip, target_node_id,
                    data_mode, status, dedupe_key, occurrence_count, detected_at, acknowledged_at, contained_at,
                    resolved_at, created_at, updated_at)
                values ('inc-x', 'org-alpha', 't', 'MEDIUM', 'AUTH_ANOMALY', '10.0.0.1', 'node-kylin-01',
                    'SYNTHETIC', 'RESOLVED', 'AUTH_ANOMALY|10.0.0.1|node-kylin-01', 1,
                    ?, ?, ?, ?, ?, ?)
                """,
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 8, 9, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 8, 9, 2, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 8, 9, 6, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 8, 9, 20, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 8, 9, 0, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 8, 9, 20, 0)));

        IncidentMetrics metrics = service.metrics("org-alpha");
        assertThat(metrics.resolvedCount()).isEqualTo(1L);
        assertThat(metrics.avgAcknowledgeSeconds()).isEqualTo(120L);   // 2 分钟
        assertThat(metrics.avgContainSeconds()).isEqualTo(360L);       // 6 分钟
        assertThat(metrics.avgResolveSeconds()).isEqualTo(1200L);      // 20 分钟（MTTR）
        assertThat(metrics.byStatus().get("RESOLVED")).isEqualTo(1L);
    }

    @Test
    void intakeFromAlertMapsAndDedupes() {
        jdbcTemplate.update("""
                insert into app_alert_event (id, organization_id, level, type, host, status, suggestion)
                values (7, 'org-alpha', 'HIGH', 'SSH 暴力破解', 'node-kylin-01', 'OPEN',
                    '疑似来自 203.0.113.66 的口令爆破，建议临时封禁')
                """);
        IncidentIntakeResult first = service.intakeFromAlert("org-alpha", 7);
        assertThat(first.merged()).isFalse();
        assertThat(first.incident().category()).isEqualTo("AUTH_ANOMALY");
        assertThat(first.incident().severity()).isEqualTo("HIGH");
        assertThat(first.incident().targetNodeId()).isEqualTo("node-kylin-01");
        assertThat(first.incident().sourceIp()).isEqualTo("203.0.113.66");

        // 同一告警反复转事件 → 幂等合并，不重复开单。
        IncidentIntakeResult again = service.intakeFromAlert("org-alpha", 7);
        assertThat(again.merged()).isTrue();
        assertThat(again.incident().id()).isEqualTo(first.incident().id());
    }

    @Test
    void detailRecommendsSopFromRagKnowledge() {
        jdbcTemplate.update("""
                insert into app_rag_document (id, organization_id, title, source_type, content, created_at)
                values ('doc-1', 'org-alpha', 'SSH 认证异常处置手册', 'RUNBOOK', '认证失败排查与封禁', ?)
                """, Timestamp.valueOf(LocalDateTime.now()));
        jdbcTemplate.update("""
                insert into app_rag_document (id, organization_id, title, source_type, content, created_at)
                values ('doc-2', 'org-alpha', '磁盘清理指南', 'DOC', '磁盘容量与清理', ?)
                """, Timestamp.valueOf(LocalDateTime.now()));
        String id = service.intake("org-alpha", intake("203.0.113.66")).incident().id();

        IncidentDetail detail = service.get("org-alpha", id);
        assertThat(detail.recommendedSop()).isNotEmpty();
        assertThat(detail.recommendedSop().get(0).title()).contains("认证");
    }

    private IncidentIntakeRequest intake(String sourceIp) {
        return new IncidentIntakeRequest(
                "sshd 认证异常：来自 " + sourceIp + " 的高频失败登录",
                "HIGH", "AUTH_ANOMALY", sourceIp, "node-kylin-01", "SYNTHETIC",
                "疑似口令爆破", "{\"failedAttempts\":42}", null, "rb-auth-anomaly-100");
    }

    private void createSchema() {
        jdbcTemplate.execute("drop table if exists app_incident");
        jdbcTemplate.execute("drop table if exists app_incident_runbook");
        jdbcTemplate.execute("drop table if exists app_alert_event");
        jdbcTemplate.execute("drop table if exists app_rag_document");
        jdbcTemplate.execute("""
                create table app_alert_event (
                    id bigint primary key,
                    organization_id varchar(64) not null,
                    level varchar(32) not null,
                    type varchar(64) not null,
                    host varchar(128) not null,
                    status varchar(32) not null,
                    suggestion varchar(255) not null
                )
                """);
        jdbcTemplate.execute("""
                create table app_rag_document (
                    id varchar(64) primary key,
                    organization_id varchar(64) not null,
                    title varchar(255) not null,
                    source_type varchar(64) not null,
                    content text not null,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table app_incident (
                    id varchar(64) primary key,
                    organization_id varchar(64) not null,
                    title varchar(255) not null,
                    severity varchar(16) not null,
                    category varchar(64) not null,
                    source_ip varchar(64),
                    target_node_id varchar(128),
                    data_mode varchar(16) not null,
                    status varchar(32) not null,
                    dedupe_key varchar(255) not null,
                    source_alert_id bigint,
                    task_id varchar(64),
                    runbook_id varchar(64),
                    occurrence_count integer not null,
                    summary varchar(1024),
                    evidence_json clob,
                    detected_at timestamp not null,
                    acknowledged_at timestamp,
                    contained_at timestamp,
                    resolved_at timestamp,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        jdbcTemplate.execute("""
                create table app_incident_runbook (
                    id varchar(64) primary key,
                    organization_id varchar(64) not null,
                    name varchar(128) not null,
                    version varchar(32) not null,
                    category varchar(64) not null,
                    summary varchar(512),
                    steps_json clob not null,
                    created_at timestamp not null
                )
                """);
        jdbcTemplate.update("""
                insert into app_incident_runbook (id, organization_id, name, version, category, summary, steps_json, created_at)
                values ('rb-auth-anomaly-100', 'org-alpha', '认证异常受控响应', '1.0.0', 'AUTH_ANOMALY', 's', '[]', ?)
                """, Timestamp.valueOf(LocalDateTime.now()));
    }
}
