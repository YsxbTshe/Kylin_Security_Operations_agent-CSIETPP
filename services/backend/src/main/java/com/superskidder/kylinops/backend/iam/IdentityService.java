package com.superskidder.kylinops.backend.iam;

import com.superskidder.kylinops.backend.auth.AuthenticatedUser;
import com.superskidder.kylinops.backend.auth.CurrentUserResponse;
import com.superskidder.kylinops.backend.auth.PasswordHasher;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class IdentityService {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final PasswordHasher passwordHasher;

    public IdentityService(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            PasswordHasher passwordHasher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.passwordHasher = passwordHasher;
    }

    public Optional<UserAccount> findUserByUsername(String username) {
        return jdbcTemplate.query(
                        "select id, username, display_name, email, avatar_url, password, enabled, must_change_password from app_user_account where username = ?",
                        userAccountRowMapper(),
                        username)
                .stream()
                .findFirst();
    }

    public Optional<UserAccount> findUserById(String userId) {
        return jdbcTemplate.query(
                        "select id, username, display_name, email, avatar_url, password, enabled, must_change_password from app_user_account where id = ?",
                        userAccountRowMapper(),
                        userId)
                .stream()
                .findFirst();
    }

    public AuthenticatedUser buildAuthenticatedUser(String userId, String desiredOrganizationId) {
        UserAccount account = findUserById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "用户不存在"));
        List<String> organizationIds = findUserOrganizationIds(userId);
        if (organizationIds.isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN, "当前用户未关联任何组织");
        }

        String currentOrganizationId = desiredOrganizationId != null && organizationIds.contains(desiredOrganizationId)
                ? desiredOrganizationId
                : findDefaultOrganizationId(userId).orElse(organizationIds.get(0));
        List<String> roleCodes = findUserRoleCodes(userId);
        Set<String> permissions = new LinkedHashSet<>(findPermissionsByRoleCodes(roleCodes));

        return new AuthenticatedUser(
                account.id(),
                account.username(),
                account.displayName(),
                account.email(),
                account.avatarUrl(),
                account.enabled(),
                account.mustChangePassword(),
                currentOrganizationId,
                organizationIds,
                roleCodes,
                permissions
        );
    }

    public CurrentUserResponse buildCurrentUserResponse(AuthenticatedUser user) {
        return new CurrentUserResponse(
                user.userId(),
                user.username(),
                user.displayName(),
                user.email(),
                user.avatarUrl(),
                user.enabled(),
                user.mustChangePassword(),
                user.currentOrganizationId(),
                listOrganizationsByIds(user.organizationIds(), user.currentOrganizationId()),
                buildRoleViews(user.roleCodes()),
                user.permissions()
        );
    }

    public List<OrganizationView> listAccessibleOrganizations(String userId, String currentOrganizationId) {
        return listOrganizationsByIds(findUserOrganizationIds(userId), currentOrganizationId);
    }

    public List<OrganizationView> listOrganizations(AuthenticatedUser actor) {
        if (actor.roleCodes().contains("platform_admin")) {
            return listAllOrganizations(actor.currentOrganizationId());
        }

        return listOrganizationsByIds(findUserOrganizationIds(actor.userId()), actor.currentOrganizationId());
    }

    public List<OrganizationView> listAllOrganizations(String currentOrganizationId) {
        return jdbcTemplate.query(
                """
                select o.id, o.code, o.name,
                       count(distinct h.id) as host_count,
                       count(distinct uo.user_id) as member_count
                from app_organization o
                left join app_managed_host h on h.organization_id = o.id
                left join app_user_organization uo on uo.organization_id = o.id
                group by o.id, o.code, o.name
                order by o.name
                """,
                (resultSet, rowNum) -> new OrganizationView(
                        resultSet.getString("id"),
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getLong("host_count"),
                        resultSet.getLong("member_count"),
                        resultSet.getString("id").equals(currentOrganizationId)));
    }

    public List<RoleView> listAllRoles() {
        return jdbcTemplate.query(
                "select code, name, description from app_role order by code",
                (resultSet, rowNum) -> new RoleView(
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        findPermissionsByRoleCodes(List.of(resultSet.getString("code")))));
    }

    public List<PermissionView> listAllPermissions() {
        return jdbcTemplate.query(
                "select code, name, module, description from app_permission order by module, code",
                (resultSet, rowNum) -> new PermissionView(
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getString("module"),
                        resultSet.getString("description")));
    }

    public RoleView createRole(AuthenticatedUser actor, CreateRoleRequest request) {
        requirePlatformAdmin(actor);
        String code = request.code().trim();

        Integer existingRoleCount = jdbcTemplate.queryForObject(
                "select count(1) from app_role where code = ?",
                Integer.class,
                code
        );
        if (existingRoleCount != null && existingRoleCount > 0) {
            throw new ResponseStatusException(BAD_REQUEST, "角色编码已存在");
        }

        assertPermissionsExist(request.permissions());
        jdbcTemplate.update(
                "insert into app_role (code, name, description) values (?, ?, ?)",
                code,
                request.name().trim(),
                request.description().trim()
        );
        replaceRolePermissions(code, request.permissions());
        return buildRoleViews(List.of(code)).get(0);
    }

    public RoleView updateRole(AuthenticatedUser actor, String roleCode, UpdateRoleRequest request) {
        requirePlatformAdmin(actor);
        Integer existingRoleCount = jdbcTemplate.queryForObject(
                "select count(1) from app_role where code = ?",
                Integer.class,
                roleCode
        );
        if (existingRoleCount == null || existingRoleCount == 0) {
            throw new ResponseStatusException(NOT_FOUND, "角色不存在");
        }

        assertPermissionsExist(request.permissions());
        jdbcTemplate.update(
                "update app_role set name = ?, description = ? where code = ?",
                request.name().trim(),
                request.description().trim(),
                roleCode
        );
        replaceRolePermissions(roleCode, request.permissions());
        return buildRoleViews(List.of(roleCode)).get(0);
    }

    public PermissionView createPermission(AuthenticatedUser actor, CreatePermissionRequest request) {
        requirePlatformAdmin(actor);
        String code = request.code().trim();

        Integer existingPermissionCount = jdbcTemplate.queryForObject(
                "select count(1) from app_permission where code = ?",
                Integer.class,
                code
        );
        if (existingPermissionCount != null && existingPermissionCount > 0) {
            throw new ResponseStatusException(BAD_REQUEST, "权限编码已存在");
        }

        jdbcTemplate.update(
                "insert into app_permission (code, name, module, description) values (?, ?, ?, ?)",
                code,
                request.name().trim(),
                request.module().trim(),
                request.description().trim()
        );
        return getPermissionView(code);
    }

    public PermissionView updatePermission(AuthenticatedUser actor, String permissionCode, UpdatePermissionRequest request) {
        requirePlatformAdmin(actor);
        int updated = jdbcTemplate.update(
                "update app_permission set name = ?, module = ?, description = ? where code = ?",
                request.name().trim(),
                request.module().trim(),
                request.description().trim(),
                permissionCode
        );
        if (updated == 0) {
            throw new ResponseStatusException(NOT_FOUND, "权限不存在");
        }
        return getPermissionView(permissionCode);
    }

    public void deleteRole(AuthenticatedUser actor, String roleCode) {
        requirePlatformAdmin(actor);
        Integer existingRoleCount = jdbcTemplate.queryForObject(
                "select count(1) from app_role where code = ?",
                Integer.class,
                roleCode
        );
        if (existingRoleCount == null || existingRoleCount == 0) {
            throw new ResponseStatusException(NOT_FOUND, "角色不存在");
        }

        Integer assignedUserCount = jdbcTemplate.queryForObject(
                "select count(1) from app_user_role where role_code = ?",
                Integer.class,
                roleCode
        );
        if (assignedUserCount != null && assignedUserCount > 0) {
            throw new ResponseStatusException(BAD_REQUEST, "角色已分配给用户，无法删除");
        }

        jdbcTemplate.update("delete from app_role where code = ?", roleCode);
    }

    public void deletePermission(AuthenticatedUser actor, String permissionCode) {
        requirePlatformAdmin(actor);
        Integer existingPermissionCount = jdbcTemplate.queryForObject(
                "select count(1) from app_permission where code = ?",
                Integer.class,
                permissionCode
        );
        if (existingPermissionCount == null || existingPermissionCount == 0) {
            throw new ResponseStatusException(NOT_FOUND, "权限不存在");
        }

        Integer boundRoleCount = jdbcTemplate.queryForObject(
                "select count(1) from app_role_permission where permission_code = ?",
                Integer.class,
                permissionCode
        );
        if (boundRoleCount != null && boundRoleCount > 0) {
            throw new ResponseStatusException(BAD_REQUEST, "权限已绑定到角色，无法删除");
        }

        jdbcTemplate.update("delete from app_permission where code = ?", permissionCode);
    }

    public List<UserView> listUsers(AuthenticatedUser actor) {
        return jdbcTemplate.query(
                        """
                        select distinct u.id
                        from app_user_account u
                        join app_user_organization uo on u.id = uo.user_id
                        where uo.organization_id = ?
                        order by u.id
                        """,
                        (resultSet, rowNum) -> resultSet.getString("id"),
                        actor.currentOrganizationId())
                .stream()
                .map(this::toUserView)
                .toList();
    }

    public UserView createUser(AuthenticatedUser actor, CreateUserRequest request) {
        assertOrganizationsAssignable(actor, request.organizationIds(), request.defaultOrganizationId(), false, List.of());
        List<RoleView> roles = assertRoleCodesExist(request.roleCodes());
        assertRolesAssignable(actor, request.roleCodes());

        if (findUserByUsername(request.username().trim()).isPresent()) {
            throw new ResponseStatusException(BAD_REQUEST, "用户名已存在");
        }

        String userId = "user-" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                "insert into app_user_account (id, username, display_name, email, avatar_url, password, enabled, must_change_password) values (?, ?, ?, ?, ?, ?, ?, true)",
                userId,
                request.username().trim(),
                request.displayName().trim(),
                normalize(request.email()),
                normalize(request.avatarUrl()),
                passwordHasher.hash(request.password()),
                request.enabled());

        replaceUserRoles(userId, roles.stream().map(RoleView::code).toList());
        replaceUserOrganizations(userId, request.organizationIds(), request.defaultOrganizationId());
        return toUserView(userId);
    }

    public OrganizationView createOrganization(AuthenticatedUser actor, CreateOrganizationRequest request) {
        requirePlatformAdmin(actor);
        String code = request.code().trim();
        String name = request.name().trim();

        Integer existingCodeCount = jdbcTemplate.queryForObject(
                "select count(1) from app_organization where code = ?",
                Integer.class,
                code);
        if (existingCodeCount != null && existingCodeCount > 0) {
            throw new ResponseStatusException(BAD_REQUEST, "组织编码已存在");
        }

        String organizationId = "org-" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                "insert into app_organization (id, code, name) values (?, ?, ?)",
                organizationId,
                code,
                name
        );
        return getOrganizationView(organizationId, actor.currentOrganizationId());
    }

    public OrganizationView updateOrganization(AuthenticatedUser actor, String organizationId, UpdateOrganizationRequest request) {
        requirePlatformAdmin(actor);
        Integer existingOrganizationCount = jdbcTemplate.queryForObject(
                "select count(1) from app_organization where id = ?",
                Integer.class,
                organizationId);
        if (existingOrganizationCount == null || existingOrganizationCount == 0) {
            throw new ResponseStatusException(NOT_FOUND, "组织不存在");
        }

        String name = request.name().trim();
        jdbcTemplate.update(
                "update app_organization set name = ? where id = ?",
                name,
                organizationId
        );
        return getOrganizationView(organizationId, actor.currentOrganizationId());
    }

    public UserView updateUser(AuthenticatedUser actor, String userId, UpdateUserRequest request) {
        UserAccount existing = findUserById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "用户不存在"));
        List<String> existingOrganizationIds = findUserOrganizationIds(userId);
        assertOrganizationsAssignable(actor, request.organizationIds(), request.defaultOrganizationId(), true, existingOrganizationIds);
        List<RoleView> roles = assertRoleCodesExist(request.roleCodes());
        assertRolesAssignable(actor, request.roleCodes());

        String password = request.password() == null || request.password().isBlank()
                ? existing.password()
                : passwordHasher.hash(request.password());
        boolean mustChangePassword = request.password() == null || request.password().isBlank()
                ? existing.mustChangePassword()
                : false;

        jdbcTemplate.update(
                "update app_user_account set display_name = ?, email = ?, avatar_url = ?, password = ?, enabled = ?, must_change_password = ? where id = ?",
                request.displayName().trim(),
                normalize(request.email()),
                normalize(request.avatarUrl()),
                password,
                request.enabled(),
                mustChangePassword,
                userId);

        replaceUserRoles(userId, roles.stream().map(RoleView::code).toList());
        replaceUserOrganizations(userId, request.organizationIds(), request.defaultOrganizationId());
        return toUserView(userId);
    }

    public void changeOwnPassword(String userId, String currentPassword, String newPassword) {
        UserAccount existing = findUserById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "用户不存在"));
        if (!passwordHasher.matches(currentPassword, existing.password())) {
            throw new ResponseStatusException(BAD_REQUEST, "当前口令不正确");
        }
        if (currentPassword.equals(newPassword)) {
            throw new ResponseStatusException(BAD_REQUEST, "新口令不能与当前口令相同");
        }
        jdbcTemplate.update(
                "update app_user_account set password = ?, must_change_password = false where id = ?",
                passwordHasher.hash(newPassword),
                userId);
    }

    public boolean canAccessOrganization(String userId, String organizationId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from app_user_organization where user_id = ? and organization_id = ?",
                Integer.class,
                userId,
                organizationId);
        return count != null && count > 0;
    }

    public Optional<String> findPrimaryOrganizationId(String userId) {
        List<String> organizationIds = findUserOrganizationIds(userId);
        if (organizationIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(findDefaultOrganizationId(userId).orElse(organizationIds.get(0)));
    }

    public UserView toUserView(String userId) {
        UserAccount account = findUserById(userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "用户不存在"));
        List<String> roleCodes = findUserRoleCodes(userId);
        List<String> organizationIds = findUserOrganizationIds(userId);
        String defaultOrganizationId = findDefaultOrganizationId(userId).orElse(organizationIds.isEmpty() ? null : organizationIds.get(0));
        return new UserView(
                account.id(),
                account.username(),
                account.displayName(),
                account.email(),
                account.avatarUrl(),
                account.enabled(),
                defaultOrganizationId,
                buildRoleViews(roleCodes),
                listOrganizationsByIds(organizationIds, defaultOrganizationId)
        );
    }

    private void assertOrganizationsAssignable(
            AuthenticatedUser actor,
            Collection<String> organizationIds,
            String defaultOrganizationId,
            boolean isUpdate,
            Collection<String> existingOrganizationIds
    ) {
        Set<String> normalizedIds = new LinkedHashSet<>(organizationIds);
        if (normalizedIds.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "至少需要一个组织");
        }
        if (defaultOrganizationId == null || defaultOrganizationId.isBlank() || !normalizedIds.contains(defaultOrganizationId)) {
            throw new ResponseStatusException(BAD_REQUEST, "默认组织必须包含在组织列表中");
        }

        if (actor.roleCodes().contains("platform_admin")) {
            if (!actor.organizationIds().containsAll(normalizedIds)) {
                throw new ResponseStatusException(FORBIDDEN, "仅可分配当前用户已加入的组织");
            }
            return;
        }

        Set<String> currentOrganizationOnly = Set.of(actor.currentOrganizationId());
        if (!normalizedIds.equals(currentOrganizationOnly)) {
            throw new ResponseStatusException(FORBIDDEN, "当前组织管理员仅可管理当前组织成员");
        }

        if (isUpdate) {
            Set<String> existingIds = new LinkedHashSet<>(existingOrganizationIds);
            if (!existingIds.contains(actor.currentOrganizationId())) {
                throw new ResponseStatusException(FORBIDDEN, "仅可修改当前组织内的用户");
            }
            if (!existingIds.equals(currentOrganizationOnly)) {
                throw new ResponseStatusException(FORBIDDEN, "跨组织用户仅允许由平台管理员修改");
            }
        }
    }

    private List<RoleView> assertRoleCodesExist(Collection<String> roleCodes) {
        List<RoleView> roles = buildRoleViews(roleCodes);
        if (roles.size() != new LinkedHashSet<>(roleCodes).size()) {
            throw new ResponseStatusException(BAD_REQUEST, "角色不存在或已失效");
        }
        return roles;
    }

    /**
     * 角色赋权收敛：非 platform_admin 不得授予 platform_admin。
     * 组织管理员只能在组织范围内管成员，不能把任意用户提权成平台管理员。
     */
    private void assertRolesAssignable(AuthenticatedUser actor, Collection<String> roleCodes) {
        if (actor.roleCodes().contains("platform_admin")) {
            return;
        }
        for (String roleCode : roleCodes) {
            if ("platform_admin".equals(roleCode)) {
                throw new ResponseStatusException(FORBIDDEN, "仅平台管理员可授予平台管理员角色");
            }
        }
    }

    private void assertPermissionsExist(Collection<String> permissionCodes) {
        Set<String> normalizedCodes = new LinkedHashSet<>(permissionCodes);
        if (normalizedCodes.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "至少需要一个权限");
        }
        Integer count = namedParameterJdbcTemplate.queryForObject(
                "select count(1) from app_permission where code in (:codes)",
                new MapSqlParameterSource("codes", normalizedCodes),
                Integer.class
        );
        if (count == null || count != normalizedCodes.size()) {
            throw new ResponseStatusException(BAD_REQUEST, "权限不存在或已失效");
        }
    }

    private List<RoleView> buildRoleViews(Collection<String> roleCodes) {
        Set<String> normalizedCodes = new LinkedHashSet<>(roleCodes);
        if (normalizedCodes.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("codes", normalizedCodes);
        return namedParameterJdbcTemplate.query(
                "select code, name, description from app_role where code in (:codes) order by code",
                parameters,
                (resultSet, rowNum) -> new RoleView(
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        findPermissionsByRoleCodes(List.of(resultSet.getString("code")))));
    }

    private List<OrganizationView> listOrganizationsByIds(Collection<String> organizationIds, String currentOrganizationId) {
        Set<String> normalizedIds = new LinkedHashSet<>(organizationIds);
        if (normalizedIds.isEmpty()) {
            return List.of();
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource("ids", normalizedIds);
        return namedParameterJdbcTemplate.query(
                """
                select o.id, o.code, o.name,
                       count(distinct h.id) as host_count,
                       count(distinct uo.user_id) as member_count
                from app_organization o
                left join app_managed_host h on h.organization_id = o.id
                left join app_user_organization uo on uo.organization_id = o.id
                where o.id in (:ids)
                group by o.id, o.code, o.name
                order by o.name
                """,
                parameters,
                (resultSet, rowNum) -> new OrganizationView(
                        resultSet.getString("id"),
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getLong("host_count"),
                        resultSet.getLong("member_count"),
                        resultSet.getString("id").equals(currentOrganizationId)));
    }

    private OrganizationView getOrganizationView(String organizationId, String currentOrganizationId) {
        return listOrganizationsByIds(List.of(organizationId), currentOrganizationId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "组织不存在"));
    }

    private PermissionView getPermissionView(String permissionCode) {
        return jdbcTemplate.query(
                        "select code, name, module, description from app_permission where code = ?",
                        (resultSet, rowNum) -> new PermissionView(
                                resultSet.getString("code"),
                                resultSet.getString("name"),
                                resultSet.getString("module"),
                                resultSet.getString("description")
                        ),
                        permissionCode)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "权限不存在"));
    }

    private List<String> findUserRoleCodes(String userId) {
        return jdbcTemplate.queryForList(
                "select role_code from app_user_role where user_id = ? order by role_code",
                String.class,
                userId);
    }

    private List<String> findUserOrganizationIds(String userId) {
        return jdbcTemplate.queryForList(
                "select organization_id from app_user_organization where user_id = ? order by is_default desc, organization_id",
                String.class,
                userId);
    }

    private Optional<String> findDefaultOrganizationId(String userId) {
        return jdbcTemplate.query(
                        "select organization_id from app_user_organization where user_id = ? and is_default = true limit 1",
                        (resultSet, rowNum) -> resultSet.getString("organization_id"),
                        userId)
                .stream()
                .findFirst();
    }

    private List<String> findPermissionsByRoleCodes(Collection<String> roleCodes) {
        Set<String> normalizedCodes = new LinkedHashSet<>(roleCodes);
        if (normalizedCodes.isEmpty()) {
            return List.of();
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource("roleCodes", normalizedCodes);
        return namedParameterJdbcTemplate.queryForList(
                """
                select distinct permission_code
                from app_role_permission
                where role_code in (:roleCodes)
                order by permission_code
                """,
                parameters,
                String.class);
    }

    private void replaceUserRoles(String userId, Collection<String> roleCodes) {
        jdbcTemplate.update("delete from app_user_role where user_id = ?", userId);
        for (String roleCode : new LinkedHashSet<>(roleCodes)) {
            jdbcTemplate.update("insert into app_user_role (user_id, role_code) values (?, ?)", userId, roleCode);
        }
    }

    private void replaceRolePermissions(String roleCode, Collection<String> permissionCodes) {
        jdbcTemplate.update("delete from app_role_permission where role_code = ?", roleCode);
        for (String permissionCode : new LinkedHashSet<>(permissionCodes)) {
            jdbcTemplate.update(
                    "insert into app_role_permission (role_code, permission_code) values (?, ?)",
                    roleCode,
                    permissionCode
            );
        }
    }

    private void replaceUserOrganizations(String userId, Collection<String> organizationIds, String defaultOrganizationId) {
        jdbcTemplate.update("delete from app_user_organization where user_id = ?", userId);
        for (String organizationId : new LinkedHashSet<>(organizationIds)) {
            jdbcTemplate.update(
                    "insert into app_user_organization (user_id, organization_id, is_default) values (?, ?, ?)",
                    userId,
                    organizationId,
                    organizationId.equals(defaultOrganizationId));
        }
    }

    private RowMapper<UserAccount> userAccountRowMapper() {
        return (resultSet, rowNum) -> new UserAccount(
                resultSet.getString("id"),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                resultSet.getString("avatar_url"),
                resultSet.getString("password"),
                resultSet.getBoolean("enabled"),
                resultSet.getBoolean("must_change_password"));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requirePlatformAdmin(AuthenticatedUser actor) {
        if (!actor.roleCodes().contains("platform_admin")) {
            throw new ResponseStatusException(FORBIDDEN, "仅平台管理员可执行该操作");
        }
    }

    public record UserAccount(
            String id,
            String username,
            String displayName,
            String email,
            String avatarUrl,
            String password,
            boolean enabled,
            boolean mustChangePassword
    ) {
    }
}
