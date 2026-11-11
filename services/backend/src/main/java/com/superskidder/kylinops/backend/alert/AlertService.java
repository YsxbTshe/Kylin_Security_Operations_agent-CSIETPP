package com.superskidder.kylinops.backend.alert;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AlertService {

    private final JdbcTemplate jdbcTemplate;

    public AlertService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AlertSummary> listAlerts(String organizationId) {
        return jdbcTemplate.query("""
                SELECT id, level, type, host, status, suggestion
                FROM app_alert_event
                WHERE organization_id = ?
                ORDER BY id DESC
                """, (resultSet, rowNum) -> new AlertSummary(
                resultSet.getString("id"),
                resultSet.getString("level"),
                resultSet.getString("type"),
                resultSet.getString("host"),
                resultSet.getString("status"),
                resultSet.getString("suggestion")
        ), organizationId);
    }
}
