package com.superskidder.kylinops.backend.agentruntime.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.superskidder.kylinops.backend.agentruntime.security.SecretValue;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ConnectorToolGateway：本地 HttpServer stub 验证 webhook/http_read 的请求-响应、
 * 凭据脱敏（返回值 + 日志 + 审计）、白名单封闭与 SSRF 参数注入防护。
 */
class ConnectorToolGatewayTest {

    private static final String SECRET = "s3cr3t-token-value";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record RecordedRequest(String method, String pathAndQuery, Map<String, List<String>> headers, byte[] body) {}

    private HttpServer server;
    private final List<RecordedRequest> hookRequests = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> cmdbRequests = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> elsewhereRequests = new CopyOnWriteArrayList<>();

    private JdbcTemplate jdbc;
    private ConnectorInvocationAuditRepository auditRepository;
    private ConnectorInvokeTool tool;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks", exchange -> handle(exchange, hookRequests, 200, "{\"ok\":true}"));
        server.createContext("/cmdb", exchange -> {
            byte[] payload = "a".repeat(100_000).getBytes(StandardCharsets.UTF_8);
            record(exchange, cmdbRequests);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> handle(exchange, elsewhereRequests, 200, "redirected"));
        server.start();
        jdbc = ConnectorTestDatabase.create();
        auditRepository = new ConnectorInvocationAuditRepository(jdbc);
        tool = new ConnectorInvokeTool(MAPPER);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange, List<RecordedRequest> sink, int status, String body)
            throws IOException {
        record(exchange, sink);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
        exchange.close();
    }

    private void record(HttpExchange exchange, List<RecordedRequest> sink) throws IOException {
        String pathAndQuery = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        sink.add(new RecordedRequest(exchange.getRequestMethod(), pathAndQuery,
                exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes()));
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private ConnectorToolGateway gateway(ConnectorCatalog catalog, long maxBodyBytes) {
        return new ConnectorToolGateway(catalog,
                reference -> new SecretValue(SECRET.getBytes(StandardCharsets.UTF_8)),
                auditRepository, MAPPER, 5_000, 5_000, maxBodyBytes);
    }

    private ConnectorCatalog webhookCatalog(String credentialReference) {
        return new ConnectorCatalog(baseUrl("/hooks"), credentialReference, "", "");
    }

    private ConnectorCatalog httpReadCatalog() {
        return new ConnectorCatalog("", "", baseUrl("/cmdb"), "");
    }

    private ConnectorToolGateway.Invocation invocation(String connectorId, String path,
                                                       Map<String, String> query, Map<String, Object> body) {
        return new ConnectorToolGateway.Invocation(
                ConnectorTestDatabase.TENANT_ID, "run-connector-1", connectorId, path, query, body);
    }

    @Test
    void webhookPostsBodyWithAuthorizationAndMasksSecretEverywhere() {
        ConnectorToolGateway gateway = gateway(webhookCatalog("env:TEST_TOKEN"), 262_144);
        Logger logger = (Logger) LoggerFactory.getLogger(ConnectorToolGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Map<String, Object> structured = gateway.execute(
                    invocation("webhook", "/tickets", Map.of("ticket", "INC 1"),
                            Map.of("title", "disk alert")));

            assertThat(structured).containsEntry("status", 200)
                    .containsEntry("truncated", false)
                    .containsEntry("authorization", ConnectorToolGateway.SECRET_MASK);
            assertThat(structured.get("target").toString()).isEqualTo(baseUrl("/hooks/tickets"));
            assertThat(structured.get("body").toString()).contains("ok");

            assertThat(hookRequests).hasSize(1);
            RecordedRequest received = hookRequests.get(0);
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.pathAndQuery()).isEqualTo("/hooks/tickets?ticket=INC+1");
            assertThat(received.headers().get("Authorization").get(0)).isEqualTo("Bearer " + SECRET);
            assertThat(new String(received.body(), StandardCharsets.UTF_8)).contains("disk alert");

            // 脱敏断言：返回值、日志、审计行均不含凭据明文。
            String resultJson = writeJson(structured);
            assertThat(resultJson).doesNotContain(SECRET);
            assertThat(appender.list).allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain(SECRET));
            List<Map<String, Object>> auditRows =
                    jdbc.queryForList("select * from connector_invocation");
            assertThat(auditRows).hasSize(1);
            Map<String, Object> row = auditRows.get(0);
            assertThat(writeJson(row)).doesNotContain(SECRET);
            assertThat(row.get("connector_id")).isEqualTo("webhook");
            assertThat(row.get("response_status")).isEqualTo(200);
            assertThat(row.get("request_digest").toString()).matches("[0-9a-f]{64}");
            assertThat(row.get("target").toString()).isEqualTo(baseUrl("/hooks/tickets"));
            assertThat(((Number) row.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void unknownConnectorIsRejectedWithoutAnyHttpRequest() {
        ConnectorToolGateway gateway = gateway(webhookCatalog(""), 262_144);

        assertThatThrownBy(() -> gateway.execute(invocation("cmdb_write", "/x", null, Map.of())))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("unknown connector");
        assertThat(hookRequests).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from connector_invocation", Long.class)).isZero();
    }

    @Test
    void unconfiguredConnectorFailsClosed() {
        ConnectorToolGateway gateway = gateway(new ConnectorCatalog("", "", "", ""), 262_144);

        assertThatThrownBy(() -> gateway.execute(invocation("webhook", "/x", null, Map.of())))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("not configured");
        assertThat(hookRequests).isEmpty();
    }

    @Test
    void pathArgumentsCannotInjectAnotherHost() {
        ConnectorToolGateway gateway = gateway(webhookCatalog(""), 262_144);

        // 绝对 URL / 协议相对 URL / 路径穿越 / 片段注入全部拒绝，且 stub 收不到任何请求。
        for (String malicious : List.of(
                "http://evil.example/steal",
                "//evil.example/steal",
                "/a/../b",
                "/a\\..\\b",
                "/path#fragment",
                "/path?injected=1")) {
            assertThatThrownBy(() -> gateway.execute(invocation("webhook", malicious, null, Map.of())))
                    .isInstanceOf(ConnectorException.class)
                    .hasMessageContaining("path");
        }
        assertThatThrownBy(() -> gateway.execute(
                invocation("webhook", "/ok", Map.of("bad key", "v"), Map.of())))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("query");
        assertThat(hookRequests).isEmpty();
    }

    @Test
    void buildTargetNeverLetsArgumentsChangeHostSchemeOrPort() {
        ConnectorDefinition definition = new ConnectorDefinition("webhook", "webhook",
                "POST", "https://cmdb.internal:8443/api", "", "R3", false);

        assertThat(ConnectorToolGateway.buildTarget(definition, "/hosts", Map.of("q", "a b")))
                .hasToString("https://cmdb.internal:8443/api/hosts?q=a+b");
        assertThat(ConnectorToolGateway.buildTarget(definition, null, null))
                .hasToString("https://cmdb.internal:8443/api");
    }

    @Test
    void requestBodyAboveLimitIsRejected() {
        ConnectorToolGateway gateway = gateway(webhookCatalog(""), 16);

        assertThatThrownBy(() -> gateway.execute(
                invocation("webhook", "/tickets", null, Map.of("payload", "x".repeat(1024)))))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("exceeds");
        assertThat(hookRequests).isEmpty();
    }

    @Test
    void httpReadUsesGetTruncatesResponseAt64KbAndRejectsBody() {
        ConnectorToolGateway gateway = gateway(httpReadCatalog(), 262_144);

        Map<String, Object> structured = gateway.execute(
                invocation("http_read", "/hosts", Map.of("region", "cn-north"), null));

        assertThat(structured).containsEntry("status", 200).containsEntry("truncated", true);
        assertThat(structured.get("body").toString()).hasSize(ConnectorToolGateway.MAX_RESPONSE_BYTES);
        assertThat(cmdbRequests).hasSize(1);
        assertThat(cmdbRequests.get(0).method()).isEqualTo("GET");
        assertThat(cmdbRequests.get(0).pathAndQuery()).isEqualTo("/cmdb/hosts?region=cn-north");

        assertThatThrownBy(() -> gateway.execute(
                invocation("http_read", "/hosts", null, Map.of("k", "v"))))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void redirectsAreNotFollowed() throws IOException {
        server.removeContext("/hooks");
        server.createContext("/hooks", exchange -> {
            record(exchange, hookRequests);
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        ConnectorToolGateway gateway = gateway(webhookCatalog(""), 262_144);

        Map<String, Object> structured = gateway.execute(
                invocation("webhook", "/tickets", null, Map.of("a", 1)));

        assertThat(structured).containsEntry("status", 302);
        assertThat(elsewhereRequests).isEmpty();
    }

    @Test
    void dispatcherRejectsNonConnectorToolInstance() {
        ConnectorToolDispatcher dispatcher =
                new ConnectorToolDispatcher(gateway(webhookCatalog(""), 262_144), MAPPER);
        com.superskidder.kylinops.backend.agentruntime.tool.FirewallBlockTool foreignTool =
                new com.superskidder.kylinops.backend.agentruntime.tool.FirewallBlockTool(MAPPER);

        assertThatThrownBy(() -> dispatcher.dispatch(null, foreignTool, Map.of()))
                .isInstanceOf(com.superskidder.kylinops.backend.agentruntime.mcp.NodeMcpGateway.ToolCallException.class)
                .hasMessageContaining("unsupported connector tool");
    }

    @Test
    void dispatcherEndToEndValidatesRendersAndAudits() {
        ConnectorToolDispatcher dispatcher =
                new ConnectorToolDispatcher(gateway(webhookCatalog("env:TEST_TOKEN"), 262_144), MAPPER);

        ConnectorToolDispatcher.DispatchOutcome outcome = dispatcher.dispatch(
                new com.superskidder.kylinops.backend.agentruntime.persistence.LeasedAgentRun(
                        "run-connector-1", ConnectorTestDatabase.TENANT_ID, "user-alpha", "instance-1",
                        "notify on-call", "CAUTIOUS", "{}", "{}", "{}", 0L, null, null),
                tool,
                Map.of("connector_id", "webhook", "path", "/tickets", "body", Map.of("t", 1)));

        assertThat(outcome.structuredOutput()).containsEntry("connectorId", "webhook")
                .containsEntry("status", 200);
        assertThat(outcome.renderedOutput()).containsEntry("type", "connector-invoke");
        assertThat(writeJson(outcome)).doesNotContain(SECRET);
        assertThat(jdbc.queryForObject("select count(*) from connector_invocation", Long.class)).isEqualTo(1);
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
