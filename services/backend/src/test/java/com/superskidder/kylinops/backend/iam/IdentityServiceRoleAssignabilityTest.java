package com.superskidder.kylinops.backend.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.auth.AuthenticatedUser;
import com.superskidder.kylinops.backend.auth.PasswordHasher;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

class IdentityServiceRoleAssignabilityTest {

    private IdentityService identityService;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:iam-role-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                "sa", "", true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__baseline.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V7__user_must_change_password.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        identityService = new IdentityService(jdbc, new NamedParameterJdbcTemplate(jdbc), new PasswordHasher());
    }

    @Test
    void orgAdminCannotGrantPlatformAdmin() {
        AuthenticatedUser orgAdmin = actor("user-secops", "org_admin");
        CreateUserRequest request = new CreateUserRequest(
                "escalated", "Escalated User", null, null, "Password1",
                true, List.of("platform_admin"), List.of("org-alpha"), "org-alpha");

        assertThatThrownBy(() -> identityService.createUser(orgAdmin, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("仅平台管理员可授予平台管理员角色");
    }

    @Test
    void orgAdminCanGrantOrgAdminWithinCurrentOrg() {
        AuthenticatedUser orgAdmin = actor("user-secops", "org_admin");
        UserView created = identityService.createUser(orgAdmin, new CreateUserRequest(
                "peer-admin", "Peer Admin", null, null, "Password1",
                true, List.of("org_admin"), List.of("org-alpha"), "org-alpha"));

        assertThat(created.roles()).extracting(RoleView::code).containsExactly("org_admin");
    }

    @Test
    void platformAdminCanGrantPlatformAdmin() {
        AuthenticatedUser platformAdmin = actor("user-admin", "platform_admin");
        UserView created = identityService.createUser(platformAdmin, new CreateUserRequest(
                "peer-padmin", "Peer Platform Admin", null, null, "Password1",
                true, List.of("platform_admin"), List.of("org-alpha"), "org-alpha"));

        assertThat(created.roles()).extracting(RoleView::code).containsExactly("platform_admin");
    }

    private AuthenticatedUser actor(String userId, String role) {
        return new AuthenticatedUser(
                userId, userId, userId, null, null, true, false,
                "org-alpha", List.of("org-alpha"), List.of(role), Set.of("user:write"));
    }
}
