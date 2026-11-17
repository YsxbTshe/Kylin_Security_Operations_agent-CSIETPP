package com.superskidder.kylinops.backend.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.superskidder.kylinops.backend.auth.PasswordHasher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies the persisted graph-RAG system (the file DB built by {@link RagHybridBenchmarkIT}):
 * the graph viewer API returns real data, and resets the admin password to a known value so the
 * running backend + admin-web can be exercised live. Opt-in: RAG_VERIFY=1.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RAG_VERIFY", matches = "1")
class RagSystemVerifyIT {

    private static final String ORG = "org-alpha";
    private static final String ADMIN_PASSWORD = "Kylinops@2026";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:./target/rag-bench-db;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false;AUTO_SERVER=TRUE");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RagService ragService;
    @Autowired
    private PasswordHasher passwordHasher;

    @Test
    void verifyGraphAndResetAdmin() {
        // Reset admin password to a known value for live login.
        int updated = jdbcTemplate.update("update app_user_account set password = ? where username = 'admin'",
                passwordHasher.hash(ADMIN_PASSWORD));
        System.out.println("[VERIFY] admin password reset (" + updated + " row) -> admin / " + ADMIN_PASSWORD);

        Map<String, Object> stats = ragService.graphStats(ORG);
        RagSubgraph graph = ragService.graph(ORG, null, 300);
        System.out.printf("[VERIFY] graph stats=%s; fetched nodes=%d edges=%d%n",
                stats, graph.nodes().size(), graph.edges().size());
        if (!graph.nodes().isEmpty()) {
            var sample = graph.nodes().get(0);
            System.out.printf("[VERIFY] sample node: %s [%s] salience=%.2f%n",
                    sample.name(), sample.nodeType(), sample.salience());
        }

        assertThat(((Number) stats.get("nodeCount")).intValue()).isGreaterThan(0);
        assertThat(graph.nodes()).isNotEmpty();
        assertThat(graph.edges()).isNotEmpty();
    }
}
