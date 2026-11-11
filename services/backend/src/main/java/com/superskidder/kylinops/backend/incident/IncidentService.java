package com.superskidder.kylinops.backend.incident;

import com.superskidder.kylinops.backend.common.AppException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 安全事件响应闭环编排（决赛计划 F）。负责事件接入去重、研判→处置任务关联、状态流转与时效度量；
 * 处置执行本身复用既有 SafeOps 链（策略/双人审批/端侧执行/验证/回滚），本服务不重复实现门禁。
 *
 * 设计要点：
 *  · 幂等接入：相同 dedupe（类别|来源IP|节点）的**非终态**事件被合并（occurrence_count++），
 *    不产生重复开放事件；命中终态或无匹配则作为新事件——重复信号不掩盖也不复活已闭环事件。
 *  · 与执行解耦：triage 只产出「目标主机 + 响应话术」，由控制器创建 SafeOps 任务后回填 task_id，
 *    使本服务可脱离 HostTaskService 单测。
 *  · fail-closed 流转：终态事件拒绝再处置。
 */
@Service
public class IncidentService {

    private final JdbcTemplate jdbcTemplate;

    public IncidentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IncidentSummary> list(String organizationId, String statusFilter) {
        StringBuilder sql = new StringBuilder("""
                select id, title, severity, category, source_ip, target_node_id, data_mode, status,
                       occurrence_count, task_id, detected_at, updated_at
                from app_incident
                where organization_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        if (statusFilter != null && !statusFilter.isBlank()) {
            sql.append(" and status = ?");
            args.add(statusFilter.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" order by detected_at desc, id desc");
        return jdbcTemplate.query(sql.toString(), (rs, n) -> mapSummary(rs), args.toArray());
    }

    public IncidentDetail get(String organizationId, String incidentId) {
        List<IncidentDetail> rows = jdbcTemplate.query("""
                select id, title, severity, category, source_ip, target_node_id, data_mode, status,
                       occurrence_count, task_id, detected_at, updated_at, summary, evidence_json,
                       runbook_id, source_alert_id, acknowledged_at, contained_at, resolved_at
                from app_incident
                where organization_id = ? and id = ?
                """, (rs, n) -> {
            IncidentSummary summary = mapSummary(rs);
            Timestamp detected = rs.getTimestamp("detected_at");
            Timestamp acknowledged = rs.getTimestamp("acknowledged_at");
            Timestamp contained = rs.getTimestamp("contained_at");
            Timestamp resolved = rs.getTimestamp("resolved_at");
            Long alertId = rs.getObject("source_alert_id") == null ? null : rs.getLong("source_alert_id");
            return new IncidentDetail(
                    summary,
                    rs.getString("summary"),
                    rs.getString("evidence_json"),
                    rs.getString("runbook_id"),
                    null,
                    List.of(),
                    alertId,
                    iso(acknowledged),
                    iso(contained),
                    iso(resolved),
                    seconds(detected, acknowledged),
                    seconds(detected, contained),
                    seconds(detected, resolved)
            );
        }, organizationId, incidentId);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "事件不存在: " + incidentId);
        }
        IncidentDetail detail = rows.get(0);
        IncidentRunbookSummary runbook = loadRunbook(organizationId, detail.runbookId());
        List<IncidentDetail.SopReference> sop = recommendSop(organizationId, detail.summary().category());
        return new IncidentDetail(detail.summary(), detail.description(), detail.evidenceJson(), detail.runbookId(),
                runbook, sop, detail.sourceAlertId(), detail.acknowledgedAt(), detail.containedAt(), detail.resolvedAt(),
                detail.timeToAcknowledgeSeconds(), detail.timeToContainSeconds(), detail.timeToResolveSeconds());
    }

    /**
     * 从告警创建事件（告警→事件闭环入口）。读 app_alert_event 映射为事件接入请求，复用
     * {@link #intake} 的幂等去重——同一告警反复转事件不会重复开单。
     */
    public IncidentIntakeResult intakeFromAlert(String organizationId, long alertId) {
        List<AlertRow> rows = jdbcTemplate.query("""
                select id, level, type, host, suggestion from app_alert_event
                where organization_id = ? and id = ?
                """, (rs, n) -> new AlertRow(
                rs.getLong("id"), rs.getString("level"), rs.getString("type"),
                rs.getString("host"), rs.getString("suggestion")), organizationId, alertId);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "告警不存在: " + alertId);
        }
        AlertRow alert = rows.get(0);
        String category = categoryFromAlertType(alert.type());
        String runbookId = pickRunbookId(organizationId, category);
        IncidentIntakeRequest request = new IncidentIntakeRequest(
                (alert.type() == null ? "安全告警" : alert.type()) + "：" + (alert.host() == null ? "" : alert.host()),
                severityFromLevel(alert.level()),
                category,
                detectIpFromText(alert.suggestion()),
                alert.host(),
                "SYNTHETIC",
                alert.suggestion(),
                null,
                alertId,
                runbookId);
        return intake(organizationId, request);
    }

    /** 与事件类别相关的官方文档/SOP（只读 RAG 引用）。RAG 表缺失时兜底为空，不影响事件详情。 */
    public List<IncidentDetail.SopReference> recommendSop(String organizationId, String category) {
        try {
            String keyword = sopKeyword(category);
            return jdbcTemplate.query("""
                    select id, title, source_type from app_rag_document
                    where organization_id = ?
                      and (upper(source_type) = 'RUNBOOK' or lower(title) like ? or lower(content) like ?)
                    order by case when upper(source_type) = 'RUNBOOK' then 0 else 1 end, created_at desc
                    """, (rs, n) -> new IncidentDetail.SopReference(
                    rs.getString("id"), rs.getString("title"), rs.getString("source_type")),
                    organizationId, "%" + keyword + "%", "%" + keyword + "%").stream().limit(5).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** 幂等接入：命中非终态同 dedupe 事件则合并计数，否则新建。 */
    public IncidentIntakeResult intake(String organizationId, IncidentIntakeRequest request) {
        String category = normalizeUpper(request.category(), "OTHER");
        String sourceIp = request.sourceIp() == null ? "" : request.sourceIp().trim();
        String node = request.targetNodeId() == null ? "" : request.targetNodeId().trim();
        String dedupeKey = category + "|" + sourceIp + "|" + node;

        List<String> open = jdbcTemplate.queryForList("""
                select id from app_incident
                where organization_id = ? and dedupe_key = ?
                  and status not in ('RESOLVED', 'CLOSED', 'FALSE_POSITIVE')
                order by detected_at desc
                """, String.class, organizationId, dedupeKey);
        if (!open.isEmpty()) {
            String existingId = open.get(0);
            jdbcTemplate.update("""
                    update app_incident set occurrence_count = occurrence_count + 1, updated_at = ?
                    where organization_id = ? and id = ?
                    """, Timestamp.valueOf(LocalDateTime.now()), organizationId, existingId);
            return new IncidentIntakeResult(getSummary(organizationId, existingId), true);
        }

        String id = "inc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                insert into app_incident (id, organization_id, title, severity, category, source_ip, target_node_id,
                    data_mode, status, dedupe_key, source_alert_id, runbook_id, occurrence_count, summary, evidence_json,
                    detected_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'NEW', ?, ?, ?, 1, ?, ?, ?, ?, ?)
                """,
                id, organizationId, request.title(), normalizeUpper(request.severity(), "MEDIUM"), category,
                sourceIp.isEmpty() ? null : sourceIp, node.isEmpty() ? null : node,
                DataMode.normalize(request.dataMode()).name(), dedupeKey, request.sourceAlertId(), request.runbookId(),
                request.summary(), request.evidenceJson(),
                Timestamp.valueOf(now), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return new IncidentIntakeResult(getSummary(organizationId, id), false);
    }

    /**
     * 发起研判：把事件翻译成一条 SafeOps 处置任务的输入。置 TRIAGED 并记录 acknowledged_at，
     * 返回目标主机与事件响应话术（含来源 IP，命中取证-阻断-验证链）。终态事件拒绝再处置。
     */
    public IncidentTriagePlan beginTriage(String organizationId, String incidentId) {
        IncidentRow row = readRow(organizationId, incidentId);
        if (row.status().isTerminal()) {
            throw new AppException(HttpStatus.CONFLICT, "事件已处于终态（" + row.status() + "），不可再发起处置");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                update app_incident
                set status = 'TRIAGED', acknowledged_at = coalesce(acknowledged_at, ?), updated_at = ?
                where organization_id = ? and id = ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), organizationId, incidentId);
        return new IncidentTriagePlan(incidentId, row.targetNodeId(), buildResponsePrompt(row));
    }

    /** 研判后回填处置任务并进入 CONTAINING。事件不存在即 404，终态事件拒绝再处置。 */
    public void linkTask(String organizationId, String incidentId, String taskId) {
        IncidentRow row = readRow(organizationId, incidentId);
        if (row.status().isTerminal()) {
            throw new AppException(HttpStatus.CONFLICT, "事件已处于终态（" + row.status() + "），不可再关联处置任务");
        }
        jdbcTemplate.update("""
                update app_incident set task_id = ?, status = 'CONTAINING', updated_at = ?
                where organization_id = ? and id = ?
                """, taskId, Timestamp.valueOf(LocalDateTime.now()), organizationId, incidentId);
    }

    public void markContained(String organizationId, String incidentId) {
        markContained(organizationId, incidentId, LocalDateTime.now());
    }

    /**
     * 置 CONTAINED 并记录遏制时刻。{@code containedAt} 由调用方给出，使"遏制均时"度量的是
     * <b>处置任务真正完成的时刻</b>，而不是巡检发现它完成的时刻。
     */
    public void markContained(String organizationId, String incidentId, LocalDateTime containedAt) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effective = containedAt == null ? now : containedAt;
        jdbcTemplate.update("""
                update app_incident set status = 'CONTAINED', contained_at = coalesce(contained_at, ?), updated_at = ?
                where organization_id = ? and id = ?
                """, Timestamp.valueOf(effective), Timestamp.valueOf(now), organizationId, incidentId);
    }

    public IncidentDetail resolve(String organizationId, String incidentId) {
        readRow(organizationId, incidentId);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                update app_incident set status = 'RESOLVED', resolved_at = coalesce(resolved_at, ?), updated_at = ?
                where organization_id = ? and id = ?
                """, Timestamp.valueOf(now), Timestamp.valueOf(now), organizationId, incidentId);
        return get(organizationId, incidentId);
    }

    /** 误报关单：置 FALSE_POSITIVE 终态。用于「误报拒绝」验证点——不进入处置。 */
    public IncidentDetail markFalsePositive(String organizationId, String incidentId, String note) {
        IncidentRow row = readRow(organizationId, incidentId);
        if (row.taskId() != null && !row.taskId().isBlank()) {
            throw new AppException(HttpStatus.CONFLICT, "已发起处置的事件不可直接标记误报");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                update app_incident set status = 'FALSE_POSITIVE', resolved_at = coalesce(resolved_at, ?),
                    summary = coalesce(?, summary), updated_at = ?
                where organization_id = ? and id = ?
                """, Timestamp.valueOf(now), note, Timestamp.valueOf(now), organizationId, incidentId);
        return get(organizationId, incidentId);
    }

    public IncidentMetrics metrics(String organizationId) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select status, count(1) as c from app_incident where organization_id = ? group by status
                """, rs -> {
            byStatus.put(rs.getString("status"), rs.getLong("c"));
        }, organizationId);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        List<long[]> spans = jdbcTemplate.query("""
                select detected_at, acknowledged_at, contained_at, resolved_at
                from app_incident
                where organization_id = ? and status = 'RESOLVED' and resolved_at is not null
                """, (rs, n) -> new long[]{
                nullableSeconds(rs.getTimestamp("detected_at"), rs.getTimestamp("acknowledged_at")),
                nullableSeconds(rs.getTimestamp("detected_at"), rs.getTimestamp("contained_at")),
                nullableSeconds(rs.getTimestamp("detected_at"), rs.getTimestamp("resolved_at"))
        }, organizationId);

        return new IncidentMetrics(total, byStatus, spans.size(),
                average(spans, 0), average(spans, 1), average(spans, 2));
    }

    public List<IncidentRunbookSummary> listRunbooks(String organizationId) {
        return jdbcTemplate.query("""
                select id, name, version, category, summary, steps_json
                from app_incident_runbook where organization_id = ? order by category, name
                """, (rs, n) -> new IncidentRunbookSummary(
                rs.getString("id"), rs.getString("name"), rs.getString("version"),
                rs.getString("category"), rs.getString("summary"), rs.getString("steps_json")), organizationId);
    }

    // --- internals ---

    private String buildResponsePrompt(IncidentRow row) {
        StringBuilder prompt = new StringBuilder("安全事件响应：调查");
        if (row.sourceIp() != null && !row.sourceIp().isBlank()) {
            prompt.append("来自 ").append(row.sourceIp()).append(" 的");
        }
        prompt.append(categoryPhrase(row.category()))
                .append("，先只读取证再在确认后临时阻断来源");
        if (row.sourceIp() != null && !row.sourceIp().isBlank()) {
            prompt.append(" IP ").append(row.sourceIp());
        }
        prompt.append("。");
        return prompt.toString();
    }

    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("\\b((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    private static String detectIpFromText(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher matcher = IPV4.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** 告警类型文本 → 事件类别。 */
    private static String categoryFromAlertType(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("认证") || t.contains("登录") || t.contains("爆破") || t.contains("暴力") || t.contains("brute") || t.contains("ssh")) {
            return "AUTH_ANOMALY";
        }
        if (t.contains("入侵") || t.contains("攻击") || t.contains("intrusion") || t.contains("扫描") || t.contains("scan")) {
            return "INTRUSION";
        }
        if (t.contains("病毒") || t.contains("木马") || t.contains("恶意") || t.contains("malware")) {
            return "MALWARE";
        }
        if (t.contains("外泄") || t.contains("泄露") || t.contains("exfil")) {
            return "EXFILTRATION";
        }
        return "OTHER";
    }

    private static String severityFromLevel(String level) {
        String l = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        return switch (l) {
            case "CRITICAL", "HIGH", "MEDIUM", "LOW" -> l;
            default -> "MEDIUM";
        };
    }

    /** 事件类别 → RAG 检索关键词（用于推荐相关官方文档/SOP）。 */
    private static String sopKeyword(String category) {
        return switch (normalizeUpper(category, "OTHER")) {
            case "AUTH_ANOMALY" -> "认证";
            case "INTRUSION" -> "防火墙";
            case "MALWARE" -> "kysec";
            case "EXFILTRATION" -> "网络";
            default -> "安全";
        };
    }

    private String pickRunbookId(String organizationId, String category) {
        List<String> ids = jdbcTemplate.queryForList("""
                select id from app_incident_runbook
                where organization_id = ? and category = ?
                order by version desc
                """, String.class, organizationId, normalizeUpper(category, "OTHER"));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String categoryPhrase(String category) {
        return switch (normalizeUpper(category, "OTHER")) {
            case "AUTH_ANOMALY" -> "认证异常";
            case "INTRUSION" -> "入侵行为";
            case "MALWARE" -> "恶意软件活动";
            case "EXFILTRATION" -> "数据外泄迹象";
            default -> "可疑安全事件";
        };
    }

    private IncidentRunbookSummary loadRunbook(String organizationId, String runbookId) {
        if (runbookId == null || runbookId.isBlank()) {
            return null;
        }
        List<IncidentRunbookSummary> rows = jdbcTemplate.query("""
                select id, name, version, category, summary, steps_json
                from app_incident_runbook where organization_id = ? and id = ?
                """, (rs, n) -> new IncidentRunbookSummary(
                rs.getString("id"), rs.getString("name"), rs.getString("version"),
                rs.getString("category"), rs.getString("summary"), rs.getString("steps_json")), organizationId, runbookId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private IncidentSummary getSummary(String organizationId, String incidentId) {
        return jdbcTemplate.queryForObject("""
                select id, title, severity, category, source_ip, target_node_id, data_mode, status,
                       occurrence_count, task_id, detected_at, updated_at
                from app_incident where organization_id = ? and id = ?
                """, (rs, n) -> mapSummary(rs), organizationId, incidentId);
    }

    private IncidentRow readRow(String organizationId, String incidentId) {
        List<IncidentRow> rows = jdbcTemplate.query("""
                select id, category, source_ip, target_node_id, status, task_id
                from app_incident where organization_id = ? and id = ?
                """, (rs, n) -> new IncidentRow(
                rs.getString("id"), rs.getString("category"), rs.getString("source_ip"),
                rs.getString("target_node_id"), IncidentStatus.normalize(rs.getString("status")),
                rs.getString("task_id")), organizationId, incidentId);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "事件不存在: " + incidentId);
        }
        return rows.get(0);
    }

    private IncidentSummary mapSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IncidentSummary(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("severity"),
                rs.getString("category"),
                rs.getString("source_ip"),
                rs.getString("target_node_id"),
                rs.getString("data_mode"),
                rs.getString("status"),
                rs.getInt("occurrence_count"),
                rs.getString("task_id"),
                iso(rs.getTimestamp("detected_at")),
                iso(rs.getTimestamp("updated_at"))
        );
    }

    private static String normalizeUpper(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String iso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toString();
    }

    private static Long seconds(Timestamp from, Timestamp to) {
        if (from == null || to == null) {
            return null;
        }
        return Duration.between(from.toLocalDateTime(), to.toLocalDateTime()).getSeconds();
    }

    private static long nullableSeconds(Timestamp from, Timestamp to) {
        Long s = seconds(from, to);
        return s == null ? -1L : s;
    }

    private static Long average(List<long[]> spans, int index) {
        long sum = 0;
        long count = 0;
        for (long[] span : spans) {
            if (span[index] >= 0) {
                sum += span[index];
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    private record IncidentRow(String id, String category, String sourceIp, String targetNodeId,
                              IncidentStatus status, String taskId) {
    }

    private record AlertRow(long id, String level, String type, String host, String suggestion) {
    }
}
