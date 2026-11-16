package com.superskidder.kylinops.backend.abac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.abac.ResourcePolicy.Effect;
import com.superskidder.kylinops.backend.abac.ResourcePolicy.SubjectType;
import com.superskidder.kylinops.backend.common.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 资源级授权入口（ABAC）。从库中取出与主体/资源相关的策略，交给
 * {@link AbacPolicyEngine} 判定。与 RBAC 的全局 requirePermission 叠加使用：
 * 先过全局功能权限，再过资源级授权，二者都通过才允许对具体资源动作。
 */
@Service
public class ResourceAccessService {

    private final JdbcTemplate jdbcTemplate;
    private final AbacPolicyEngine engine;

    public ResourceAccessService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.engine = new AbacPolicyEngine(objectMapper);
    }

    /** 判定但不抛异常；返回决策供调用方审计或分支。 */
    public AbacDecision check(String organizationId, AbacRequest request) {
        List<ResourcePolicy> policies = loadPolicies(organizationId, request);
        return engine.decide(policies, request);
    }

    /** 判定并在拒绝时抛出 403。 */
    public void requireAccess(String organizationId, AbacRequest request) {
        AbacDecision decision = check(organizationId, request);
        if (!decision.allowed()) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "无权对资源 " + request.resourceType() + ":" + request.resourceId()
                            + " 执行 " + request.action() + "（" + decision.reason() + "）");
        }
    }

    private List<ResourcePolicy> loadPolicies(String organizationId, AbacRequest request) {
        // 只取与本主体（用户 或 其任一角色）+ 本资源类型相关的策略，缩小判定集合。
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, subject_type, subject_id, resource_type, resource_pattern, action, effect, condition_json
                from app_resource_policy
                where organization_id = ? and resource_type = ? and (
                    (subject_type = 'USER' and subject_id = ?)
                """);
        args.add(organizationId);
        args.add(request.resourceType());
        args.add(request.userId() == null ? "" : request.userId());

        List<String> roles = request.roles() == null ? List.of() : request.roles();
        if (!roles.isEmpty()) {
            String placeholders = String.join(",", roles.stream().map(r -> "?").toList());
            sql.append(" or (subject_type = 'ROLE' and subject_id in (").append(placeholders).append("))");
            args.addAll(roles);
        }
        sql.append(" )");

        return jdbcTemplate.query(sql.toString(), (rs, n) -> {
            String pattern = rs.getString("resource_pattern");
            return new ResourcePolicy(
                    rs.getString("id"),
                    SubjectType.valueOf(rs.getString("subject_type").trim().toUpperCase(Locale.ROOT)),
                    rs.getString("subject_id"),
                    rs.getString("resource_type"),
                    pattern,
                    rs.getString("action"),
                    Effect.valueOf(rs.getString("effect").trim().toUpperCase(Locale.ROOT)),
                    rs.getString("condition_json"),
                    specificityOf(pattern));
        }, args.toArray());
    }

    /** 越具体的模式优先级越高：精确 > 前缀通配 > 全通配。 */
    private int specificityOf(String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        if ("*".equals(p)) {
            return 10;
        }
        if (p.endsWith("*")) {
            return 50 + p.length();
        }
        return 100 + p.length();
    }
}
