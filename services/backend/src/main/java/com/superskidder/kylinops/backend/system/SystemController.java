package com.superskidder.kylinops.backend.system;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.dashboard.DashboardService;
import com.superskidder.kylinops.backend.dashboard.ScreenDashboardResponse;
import com.superskidder.kylinops.backend.security.SharedTokenVerifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController implements InitializingBean {

    private final DashboardService dashboardService;
    private final JdbcTemplate jdbcTemplate;
    private final String screenSharedToken;
    private final String profileMode;
    private final boolean strictMode;

    public SystemController(
            DashboardService dashboardService,
            JdbcTemplate jdbcTemplate,
            @Value("${app.screen.shared-token:}") String screenSharedToken,
            @Value("${app.profile.mode:mvp}") String profileMode,
            @Value("${app.strict-mode.enabled:false}") boolean strictMode
    ) {
        this.dashboardService = dashboardService;
        this.jdbcTemplate = jdbcTemplate;
        this.screenSharedToken = screenSharedToken == null ? "" : screenSharedToken.trim();
        this.profileMode = profileMode == null ? "" : profileMode.trim();
        this.strictMode = strictMode;
    }

    /**
     * 运行时能力状态：供前端判断是否需要提示“未配置”并在严格模式下硬门控。
     * strictMode（总开关）、LLM 是否已配置可用、embedding 是否为真实向量。
     */
    @GetMapping("/runtime-status")
    public ApiResponse<Map<String, Object>> runtimeStatus() {
        Integer llmCount = jdbcTemplate.queryForObject(
                "select count(1) from app_platform_llm_config where enabled = true and base_url <> ''", Integer.class);
        boolean llmConfigured = llmCount != null && llmCount > 0;
        String llmModel = null;
        if (llmConfigured) {
            llmModel = jdbcTemplate.query(
                    "select model from app_platform_llm_config where enabled = true and base_url <> '' order by id limit 1",
                    rs -> rs.next() ? rs.getString(1) : null);
        }
        String embeddingProvider = jdbcTemplate.query(
                "select embedding_provider from app_rag_config order by id limit 1",
                rs -> rs.next() ? rs.getString(1) : null);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("strictMode", strictMode);
        status.put("llmConfigured", llmConfigured);
        status.put("llmModel", llmModel);
        status.put("embeddingProvider", embeddingProvider);
        status.put("embeddingReal", "OPENAI_COMPATIBLE".equals(embeddingProvider));
        return ApiResponse.success(status);
    }

    @Override
    public void afterPropertiesSet() {
        SharedTokenVerifier.requireConfigured(screenSharedToken, "app.screen.shared-token");
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        boolean databaseUp = isDatabaseUp();
        Map<String, String> health = new LinkedHashMap<>();
        health.put("service", "backend");
        health.put("status", databaseUp ? "UP" : "DOWN");
        health.put("database", databaseUp ? "UP" : "DOWN");
        health.put("profileMode", profileMode.isBlank() ? "unknown" : profileMode);
        return ApiResponse.success(health);
    }

    @GetMapping("/screen")
    public ApiResponse<ScreenDashboardResponse> screen(
            @RequestHeader(value = "X-Screen-Token", required = false) String screenToken,
            @RequestParam(defaultValue = "org-alpha") String organizationId
    ) {
        requireScreenToken(screenToken);
        validateOrganizationId(organizationId);
        return ApiResponse.success(dashboardService.screen(organizationId));
    }

    private void validateOrganizationId(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId 不能为空");
        }
        if (organizationId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId 长度不能超过128");
        }
    }

    private void requireScreenToken(String screenToken) {
        if (!SharedTokenVerifier.matches(screenSharedToken, screenToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "大屏共享密钥无效");
        }
    }

    private boolean isDatabaseUp() {
        try {
            Integer value = jdbcTemplate.queryForObject("select 1", Integer.class);
            return value != null && value == 1;
        } catch (Exception exception) {
            return false;
        }
    }
}
