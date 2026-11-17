package com.superskidder.kylinops.backend.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.common.AppException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 平台大模型配置服务的落库切片测试（H2 PostgreSQL 兼容模式 + 生产基线 DDL）。
 *
 * <p>新增覆盖 create 路径：此前 insert 语句列出 13 列却只写了 11 个占位符，
 * 传入 13 个实参必然在运行期抛异常——「新增大模型配置」必现 500，而 391 个既有
 * 用例无一触达该 SQL。本类以真实 JdbcTemplate 执行 insert 并读回视图，
 * 让列/占位符/实参三者的任何再次错位都会立即失败。
 */
class PlatformLlmConfigServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PlatformLlmConfigService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = createDatabase();
        service = new PlatformLlmConfigService(jdbcTemplate);
    }

    @Test
    void createPersistsAllColumnsAndReadsBackView() {
        LlmModelConfigView view = service.create("user-admin", new CreateLlmModelConfigRequest(
                "openai_compatible", "生产分组", "https://llm.example.com/v1",
                "sk-1234567890abcdef", "qwen2.5-72b", true, 30000, 2,
                "off,low,high", "low"));

        assertThat(view.id()).startsWith("llm-");
        assertThat(view.providerType()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(view.groupName()).isEqualTo("生产分组");
        assertThat(view.baseUrl()).isEqualTo("https://llm.example.com/v1");
        assertThat(view.model()).isEqualTo("qwen2.5-72b");
        assertThat(view.enabled()).isTrue();
        assertThat(view.timeoutMs()).isEqualTo(30000);
        assertThat(view.retryCount()).isEqualTo(2);
        assertThat(view.supportedThinkingLevels()).containsExactly("off", "low", "high");
        assertThat(view.defaultThinkingLevel()).isEqualTo("low");
        assertThat(view.hasApiKey()).isTrue();
        // 视图只回掩码，绝不回明文 API Key。
        assertThat(view.apiKeyMasked()).isEqualTo("sk-1********cdef");
        assertThat(view.updatedBy()).isEqualTo("user-admin");
        assertThat(view.updatedAt()).isNotBlank();

        // 落库校验：占位符与实参错位时，updated_by/updated_at 这类尾列最先出错。
        assertThat(jdbcTemplate.queryForObject(
                "select updated_by from app_platform_llm_config where id = ?", String.class, view.id()))
                .isEqualTo("user-admin");
        assertThat(jdbcTemplate.queryForObject(
                "select api_key from app_platform_llm_config where id = ?", String.class, view.id()))
                .isEqualTo("sk-1234567890abcdef");
        assertThat(jdbcTemplate.queryForObject(
                "select thinking_default from app_platform_llm_config where id = ?", String.class, view.id()))
                .isEqualTo("low");
    }

    @Test
    void createdConfigIsVisibleInListAndBecomesCurrentConfig() {
        LlmModelConfigView created = service.create("user-admin", new CreateLlmModelConfigRequest(
                "DEEPSEEK", null, null, "sk-deepseek-key-value", "deepseek-chat",
                true, null, null, null, null));

        // DEEPSEEK 的 baseUrl 由服务端固定，分组/超时/重试走默认值。
        assertThat(created.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(created.groupName()).isEqualTo("默认分组");
        assertThat(created.timeoutMs()).isEqualTo(15000);
        assertThat(created.retryCount()).isZero();
        assertThat(created.defaultThinkingLevel()).isEqualTo("off");

        List<LlmModelConfigView> views = service.listViews();
        assertThat(views).extracting(LlmModelConfigView::id).contains(created.id());

        LlmModelConfig current = service.currentConfig();
        assertThat(current.id()).isEqualTo(created.id());
        assertThat(current.apiKey()).isEqualTo("sk-deepseek-key-value");
    }

    @Test
    void createRejectsEnablingWithoutApiKey() {
        assertThatThrownBy(() -> service.create("user-admin", new CreateLlmModelConfigRequest(
                "OPENAI_COMPATIBLE", "分组", "https://llm.example.com/v1", "  ",
                "model-x", true, 15000, 0, null, null)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("API Key");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from app_platform_llm_config where id like 'llm-%'", Integer.class))
                .isZero();
    }

    /** 基线 DDL 建 app_platform_llm_config（含 V1 起就有的 13 列）。 */
    private static JdbcTemplate createDatabase() {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:llm-config-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__baseline.sql"));
        } catch (SQLException exception) {
            throw new IllegalStateException("llm config test schema failed on H2", exception);
        }
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        // 基线种子里的 'default' 行未启用且非本类关注对象，清掉以免干扰 listViews/currentConfig 断言。
        jdbcTemplate.update("delete from app_platform_llm_config where id = 'default'");
        return jdbcTemplate;
    }
}
