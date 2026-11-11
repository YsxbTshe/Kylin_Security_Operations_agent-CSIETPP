package com.superskidder.kylinops.backend.audit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import com.superskidder.kylinops.backend.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private static final DateTimeFormatter QUERY_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_OPERATOR_LENGTH = 128;
    private static final int MAX_ACTION_LENGTH = 128;
    private static final int MAX_TARGET_LENGTH = 128;

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String organizationId, String operator, String action, String target) {
        jdbcTemplate.update("""
                INSERT INTO app_audit_entry (organization_id, operator, action, target, event_time)
                VALUES (?, ?, ?, ?, ?)
                """,
                organizationId,
                truncate(blankToDefault(operator, "system"), MAX_OPERATOR_LENGTH),
                truncate(blankToDefault(action, "unknown"), MAX_ACTION_LENGTH),
                truncate(blankToDefault(target, "-"), MAX_TARGET_LENGTH),
                Timestamp.valueOf(LocalDateTime.now())
        );
    }

    public List<AuditRecord> listAudits(String organizationId) {
        return listAudits(organizationId, null, null, null, null, null, 100);
    }

    public List<AuditRecord> listAudits(String organizationId, String operator, String action, String target, int limit) {
        return listAudits(organizationId, operator, action, target, null, null, limit);
    }

    public List<AuditRecord> listAudits(
            String organizationId,
            String operator,
            String action,
            String target,
            String fromTime,
            String toTime,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, operator, action, target, event_time
                FROM app_audit_entry
                WHERE organization_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        addLikeFilter(sql, args, "operator", operator);
        addLikeFilter(sql, args, "action", action);
        addLikeFilter(sql, args, "target", target);
        addTimeRange(sql, args, fromTime, toTime);
        sql.append(" ORDER BY event_time DESC, id DESC LIMIT ?");
        args.add(normalizeLimit(limit));

        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> new AuditRecord(
                resultSet.getString("id"),
                resultSet.getString("operator"),
                resultSet.getString("action"),
                resultSet.getString("target"),
                resultSet.getTimestamp("event_time").toLocalDateTime().toString().replace('T', ' ')
        ), args.toArray());
    }

    public String exportCsv(String organizationId, String operator, String action, String target, int limit) {
        return exportCsv(organizationId, operator, action, target, null, null, limit);
    }

    public String exportCsv(
            String organizationId,
            String operator,
            String action,
            String target,
            String fromTime,
            String toTime,
            int limit
    ) {
        StringBuilder csv = new StringBuilder("id,operator,action,target,time\n");
        for (AuditRecord record : listAudits(organizationId, operator, action, target, fromTime, toTime, limit)) {
            csv.append(csv(record.id())).append(',')
                    .append(csv(record.operator())).append(',')
                    .append(csv(record.action())).append(',')
                    .append(csv(record.target())).append(',')
                    .append(csv(record.time())).append('\n');
        }
        return csv.toString();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void addLikeFilter(StringBuilder sql, List<Object> args, String column, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return;
        }
        sql.append(" AND LOWER(").append(column).append(") LIKE LOWER(?)");
        args.add("%" + normalized + "%");
    }

    private void addTimeRange(StringBuilder sql, List<Object> args, String fromTime, String toTime) {
        LocalDateTime from = parseQueryTime(fromTime, "fromTime");
        LocalDateTime to = parseQueryTime(toTime, "toTime");
        if (from != null) {
            sql.append(" AND event_time >= ?");
            args.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND event_time <= ?");
            args.add(Timestamp.valueOf(to));
        }
    }

    private LocalDateTime parseQueryTime(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(normalized, QUERY_TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new AppException(HttpStatus.BAD_REQUEST, field + " 格式必须为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private int normalizeLimit(int value) {
        if (value <= 0) {
            return 100;
        }
        return Math.min(value, 500);
    }

    private String csv(String value) {
        String normalized = value == null ? "" : value;
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }
}
