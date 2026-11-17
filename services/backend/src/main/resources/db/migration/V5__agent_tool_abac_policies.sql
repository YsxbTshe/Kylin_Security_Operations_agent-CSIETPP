-- agent-tool 执行策略（补 DispatchGate 的 ABAC 判据）。
--
-- 背景：DispatchGate 对每一次工具下发（含只读诊断）做 resource_type='agent-tool' 的 ABAC 判定，
-- 无匹配 ALLOW 即 fail-closed 拒绝。此前 baseline 只种了 resource_type='HOST' 的策略，
-- agent-tool 一条也没有，导致任何真实用户驱动的 Agent Run 在下发第一个工具时就被 ABAC_DENIED，
-- 整条“自然语言→规划→执行”链路零通过。
--
-- 与 HOST operate 同角色、同风险上限对齐：具备运维职责的角色（platform_admin/org_admin/
-- security_operator）可驱动 Agent 工具，风险上限沿用 HOST 侧口径；auditor/viewer 无此策略，
-- 其发起的 run 仍被 ABAC_DENIED，符合最小权限（deny-by-default）。
--
-- 放在独立迁移而非改 V1__baseline：现有开发库按 checksum 增量执行本迁移即获得策略，无需重建。
insert into app_resource_policy (id, organization_id, subject_type, subject_id, resource_type,
    resource_pattern, action, effect, condition_json, created_at)
select 'rp-agenttool-' || o.id || '-' || p.subject_id,
       o.id, 'ROLE', p.subject_id, 'agent-tool', '*', 'execute', 'ALLOW', p.condition_json, CURRENT_TIMESTAMP
from (values ('org-alpha'), ('org-beta'), ('org-gamma')) as o(id)
cross join (values
    ('platform_admin',    '{"maxRisk":"R5"}'),
    ('org_admin',         '{"maxRisk":"R3"}'),
    ('security_operator', '{"maxRisk":"R3"}')
) as p(subject_id, condition_json);
