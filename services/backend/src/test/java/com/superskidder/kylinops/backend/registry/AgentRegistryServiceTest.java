package com.superskidder.kylinops.backend.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 专家注册表 / 知识域 / Agent IAM 单测（决赛计划 R）：能力封套授权 fail-closed、upsert 幂等、知识域。 */
class AgentRegistryServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AgentRegistryService service;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:agent-registry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema();
        service = new AgentRegistryService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void authorizeAllowsToolWithinEnvelopeAndRiskCeiling() {
        service.upsert("org-alpha", agent("incident-responder", "executor",
                List.of("kylin.firewall.block_ip", "kylin.firewall.rules"), "R3", true));
        assertThat(service.authorize("org-alpha", "incident-responder", "kylin.firewall.block_ip", "R3").allowed()).isTrue();
        assertThat(service.authorize("org-alpha", "incident-responder", "kylin.firewall.rules", "R0").allowed()).isTrue();
    }

    @Test
    void authorizeDeniesToolOutsideEnvelope() {
        service.upsert("org-alpha", agent("incident-responder", "executor",
                List.of("kylin.firewall.block_ip"), "R3", true));
        AgentAuthorization decision = service.authorize("org-alpha", "incident-responder", "kylin.cleanup.execute", "R3");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("能力封套");
    }

    @Test
    void authorizeDeniesRiskAboveCeiling() {
        // 工具在封套内，但风险上限只有 R0 → R3 请求被拒。
        service.upsert("org-alpha", agent("weak-agent", "executor",
                List.of("kylin.firewall.block_ip"), "R0", true));
        AgentAuthorization decision = service.authorize("org-alpha", "weak-agent", "kylin.firewall.block_ip", "R3");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("超过");
    }

    @Test
    void authorizeDeniesDisabledOrUnknownAgent() {
        service.upsert("org-alpha", agent("incident-responder", "executor",
                List.of("kylin.firewall.block_ip"), "R3", true));
        service.setEnabled("org-alpha", "incident-responder", false);
        assertThat(service.authorize("org-alpha", "incident-responder", "kylin.firewall.block_ip", "R3").allowed()).isFalse();
        // 未注册（含跨组织）一律拒。
        assertThat(service.authorize("org-alpha", "ghost", "kylin.firewall.block_ip", "R0").allowed()).isFalse();
        assertThat(service.authorize("org-beta", "incident-responder", "kylin.firewall.block_ip", "R3").allowed()).isFalse();
    }

    @Test
    void upsertIsIdempotentByAgentKey() {
        service.upsert("org-alpha", agent("sense-collector", "sense", List.of("kylin.diagnosis.triage"), "R0", true));
        AgentRegistryEntry updated = service.upsert("org-alpha",
                agent("sense-collector", "sense", List.of("kylin.diagnosis.triage", "kylin.net.connections"), "R1", true));
        assertThat(jdbcTemplate.queryForObject(
                "select count(1) from app_agent_registry where organization_id = ? and agent_key = ?",
                Long.class, "org-alpha", "sense-collector")).isEqualTo(1L);
        assertThat(updated.allowedTools()).containsExactly("kylin.diagnosis.triage", "kylin.net.connections");
        assertThat(updated.maxRisk()).isEqualTo("R1");
        assertThat(service.permits("org-alpha", "sense-collector", "kylin.net.connections")).isTrue();
        assertThat(service.permits("org-alpha", "sense-collector", "kylin.cleanup.execute")).isFalse();
    }

    @Test
    void domainUpsertAndList() {
        service.upsertDomain("org-alpha", "incident-response", "安全事件响应", "取证/阻断/验证/恢复");
        service.upsertDomain("org-alpha", "incident-response", "安全事件响应(改)", "更新描述");
        List<KnowledgeDomain> domains = service.listDomains("org-alpha");
        assertThat(domains).hasSize(1);
        assertThat(domains.get(0).name()).isEqualTo("安全事件响应(改)");
    }

    private AgentUpsertRequest agent(String key, String role, List<String> tools, String maxRisk, boolean enabled) {
        return new AgentUpsertRequest(key, key + " 名称", role, "desc",
                List.of("incident-response"), tools, maxRisk, 50000L, enabled, "1.0.0");
    }

    private void createSchema() {
        jdbcTemplate.execute("drop table if exists app_agent_registry");
        jdbcTemplate.execute("drop table if exists app_knowledge_domain");
        jdbcTemplate.execute("""
                create table app_agent_registry (
                    id varchar(64) primary key,
                    organization_id varchar(64) not null,
                    agent_key varchar(64) not null,
                    name varchar(128) not null,
                    role varchar(64) not null,
                    description varchar(512),
                    knowledge_domains varchar(512),
                    allowed_tools clob not null,
                    max_risk varchar(8) not null,
                    budget_tokens bigint not null,
                    enabled boolean not null,
                    version varchar(32) not null,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    unique (organization_id, agent_key)
                )
                """);
        jdbcTemplate.execute("""
                create table app_knowledge_domain (
                    id varchar(64) primary key,
                    organization_id varchar(64) not null,
                    code varchar(64) not null,
                    name varchar(128) not null,
                    description varchar(512),
                    created_at timestamp not null,
                    unique (organization_id, code)
                )
                """);
    }
}
