package com.superskidder.kylinops.backend.abac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.common.AppException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ResourceAccessServiceTest {

    private ResourceAccessService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:abac-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table app_resource_policy (
                    id varchar(64) primary key, organization_id varchar(64) not null,
                    subject_type varchar(16) not null, subject_id varchar(128) not null,
                    resource_type varchar(32) not null, resource_pattern varchar(255) not null,
                    action varchar(32) not null, effect varchar(8) not null,
                    condition_json varchar(1024), created_at timestamp not null)
                """);
        insert(jdbc, "p1", "ROLE", "secops", "HOST", "prod-*", "operate", "ALLOW", "{\"maxRisk\":\"R3\"}");
        insert(jdbc, "p2", "ROLE", "secops", "HOST", "prod-db-*", "operate", "DENY", null);
        service = new ResourceAccessService(jdbc, new ObjectMapper());
    }

    private void insert(JdbcTemplate jdbc, String id, String st, String sid, String rt, String pat,
                        String action, String effect, String cond) {
        jdbc.update("""
                insert into app_resource_policy (id, organization_id, subject_type, subject_id, resource_type,
                    resource_pattern, action, effect, condition_json, created_at)
                values (?, 'org-alpha', ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, id, st, sid, rt, pat, action, effect, cond);
    }

    private AbacRequest operate(String host, String risk) {
        return new AbacRequest("user-a", List.of("secops"), "HOST", host, "operate", Map.of("risk", risk));
    }

    @Test
    void allowsProductionOperateWithinRiskCap() {
        assertThat(service.check("org-alpha", operate("prod-web-01", "R3")).allowed()).isTrue();
    }

    @Test
    void deniesProductionDatabaseHostDespiteWildcardAllow() {
        // prod-db-* 有显式 DENY，压过 prod-* 的 ALLOW（高危漏放=0）。
        assertThat(service.check("org-alpha", operate("prod-db-01", "R3")).allowed()).isFalse();
    }

    @Test
    void deniesWhenRiskExceedsCap() {
        assertThat(service.check("org-alpha", operate("prod-web-01", "R4")).allowed()).isFalse();
    }

    @Test
    void deniesUnrelatedRoleByDefault() {
        AbacRequest r = new AbacRequest("user-x", List.of("analyst"), "HOST", "prod-web-01", "operate", Map.of("risk", "R3"));
        assertThat(service.check("org-alpha", r).allowed()).isFalse();
    }

    @Test
    void requireAccessThrowsOnDeny() {
        assertThatThrownBy(() -> service.requireAccess("org-alpha", operate("prod-db-01", "R3")))
                .isInstanceOf(AppException.class);
    }
}
