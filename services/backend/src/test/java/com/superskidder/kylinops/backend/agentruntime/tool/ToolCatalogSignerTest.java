package com.superskidder.kylinops.backend.agentruntime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.agentruntime.security.KeyPurpose;
import com.superskidder.kylinops.backend.agentruntime.security.PemFileKeyLoader;
import com.superskidder.kylinops.backend.agentruntime.security.PlatformKeyReferences;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * tool_package_version 目录签名管线验收：
 * 1) signAllPending 覆盖基线全部 17 行未签名新形态行（V57 写工具 10 + workspace 6 + connector 1），
 *    顺带回填 'pending' digest；签后 verify 全绿；
 * 2) workspace 行回填的 digest 与 Workspace*Tool 常量一致（不破坏既有 digest 口径）；
 * 3) 篡改 schema JSON / digest / signature 任一字段，verify 必须失败；
 * 4) 密钥未配置时签名 fail-closed；未签名行 verify 返回 false 而非异常。
 *
 * 数据库引导与 WorkspaceToolDigestConsistencyTest 同链路：H2（PostgreSQL 模式）
 * 直执行压缩基线，SingleConnectionDataSource 规避 H2 2.4.240 跨会话 CHECK 回归。
 */
class ToolCatalogSignerTest {

    private static final Path VECTORS = Path.of("..", "..", "contracts", "test-vectors");
    private static final String KEY_ID = "catalog-test-1";
    private static final int PENDING_ROWS_IN_BASELINE = 17;

    private JdbcTemplate jdbcTemplate;
    private ToolCatalogSigner signer;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:tool-catalog-signer-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline.sql"));
        } catch (SQLException exception) {
            throw new IllegalStateException("V1__baseline.sql failed on H2", exception);
        }
        jdbcTemplate = new JdbcTemplate(dataSource);
        signer = new ToolCatalogSigner(jdbcTemplate, keyReferences(), new PemFileKeyLoader());
    }

    @Test
    void signsAllPendingRowsAndVerifies() {
        List<String> signed = signer.signAllPending();
        assertThat(signed).hasSize(PENDING_ROWS_IN_BASELINE);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from tool_package_version
                where tool_id is not null and (signature_hex is null or signature_hex = 'pending')
                """, Long.class)).isZero();

        for (String id : signed) {
            assertThat(signer.verify(id)).as("verify %s", id).isTrue();
            assertThat(jdbcTemplate.queryForObject(
                    "select signing_key_id from tool_package_version where id = ?",
                    String.class, id)).isEqualTo(KEY_ID);
        }
    }

    @Test
    void signingBackfillsPendingDigestsWithoutTouchingFilledOnes() {
        String workspaceInputBefore = jdbcTemplate.queryForObject("""
                select input_schema_digest from tool_package_version
                where id = 'toolpkgver-workspace-bash-v1'
                """, String.class);
        assertThat(workspaceInputBefore).isNotEqualTo("pending");

        signer.sign("toolpkgver-service-start-v1");
        signer.sign("toolpkgver-workspace-bash-v1");

        assertThat(jdbcTemplate.queryForObject("""
                select input_schema_digest from tool_package_version
                where id = 'toolpkgver-service-start-v1'
                """, String.class)).matches("[0-9a-f]{64}");
        // workspace 行已有 digest 必须原样保留（与 Workspace*Tool 常量锁定一致）。
        assertThat(jdbcTemplate.queryForObject("""
                select input_schema_digest from tool_package_version
                where id = 'toolpkgver-workspace-bash-v1'
                """, String.class)).isEqualTo(workspaceInputBefore);
    }

    @Test
    void tamperedSchemaDigestOrSignatureFailVerification() {
        signer.sign("toolpkgver-service-stop-v1");
        assertThat(signer.verify("toolpkgver-service-stop-v1")).isTrue();

        jdbcTemplate.update("""
                update tool_package_version
                set input_schema_json = replace(input_schema_json, '"maxLength":128', '"maxLength":999')
                where id = 'toolpkgver-service-stop-v1'
                """);
        assertThat(signer.verify("toolpkgver-service-stop-v1"))
                .as("schema JSON tampered").isFalse();

        jdbcTemplate.update("""
                update tool_package_version set input_schema_json = null where id = 'toolpkgver-service-stop-v1'
                """);
        // schema JSON 被清空后行不再处于可验证形态。
        assertThatThrownBy(() -> signer.verify("toolpkgver-service-stop-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tamperedSignatureHexFailsVerification() {
        signer.sign("toolpkgver-service-reload-v1");
        String signatureHex = jdbcTemplate.queryForObject("""
                select signature_hex from tool_package_version where id = 'toolpkgver-service-reload-v1'
                """, String.class);
        assertThat(signatureHex).matches("[0-9a-f]+");

        String flipped = (signatureHex.charAt(0) == 'a' ? 'b' : 'a') + signatureHex.substring(1);
        jdbcTemplate.update("update tool_package_version set signature_hex = ? where id = ?",
                flipped, "toolpkgver-service-reload-v1");
        assertThat(signer.verify("toolpkgver-service-reload-v1")).isFalse();
    }

    @Test
    void unsignedRowsVerifyFalseInsteadOfThrowing() {
        assertThat(signer.verify("toolpkgver-workspace-read-v1")).isFalse();
    }

    @Test
    void signingIsFailClosedWithoutConfiguredKey() {
        ToolCatalogSigner unconfigured = new ToolCatalogSigner(
                jdbcTemplate, emptyKeyReferences(), new PemFileKeyLoader());
        assertThatThrownBy(() -> unconfigured.sign("toolpkgver-service-start-v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> unconfigured.verify("toolpkgver-service-start-v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    private PlatformKeyReferences keyReferences() {
        Map<KeyPurpose, PlatformKeyReferences.KeyReference> keys = new EnumMap<>(KeyPurpose.class);
        for (KeyPurpose purpose : KeyPurpose.values()) {
            keys.put(purpose, new PlatformKeyReferences.KeyReference("", "", ""));
        }
        keys.put(KeyPurpose.CATALOG_SIGNING, new PlatformKeyReferences.KeyReference(
                KEY_ID,
                VECTORS.resolve("TEST_ONLY_sm2_private.pem").toString(),
                VECTORS.resolve("TEST_ONLY_sm2_public.pem").toString()));
        return new PlatformKeyReferences(keys);
    }

    private PlatformKeyReferences emptyKeyReferences() {
        Map<KeyPurpose, PlatformKeyReferences.KeyReference> keys = new EnumMap<>(KeyPurpose.class);
        for (KeyPurpose purpose : KeyPurpose.values()) {
            keys.put(purpose, new PlatformKeyReferences.KeyReference("", "", ""));
        }
        return new PlatformKeyReferences(keys);
    }
}
