-- agent-approval 决策策略（补 ApprovalService.decide 的 ABAC 判据）。
--
-- 背景：ApprovalService.decide 对每一次审批决策做 resource_type='agent-approval'、
-- action='decide' 的 ABAC 判定，无匹配 ALLOW 即 fail-closed 拒绝。此前仅种了
-- agent-tool 执行策略，agent-approval 一条也没有，导致 CAUTIOUS 模式下任何真实
-- 用户的审批动作都被 ABAC_DENIED（HTTP 403），整条"规划→待审批→人工确认→下发"
-- 链路开箱不可演示。
--
-- 与 HOST operate / agent-tool execute 同角色对齐：具备运维职责的角色
-- （platform_admin / org_admin / security_operator）可审批 Agent 写操作；
-- auditor / viewer 无此策略，仍被 default-deny。风险上限沿用各角色的 HOST 口径。
-- 发起人不可自批由 ApprovalService 代码强制，不依赖本策略。
--
-- 放在独立迁移而非改 V1/V5：现有开发库按 checksum 增量执行本迁移即获得策略。
insert into app_resource_policy (id, organization_id, subject_type, subject_id, resource_type,
    resource_pattern, action, effect, condition_json, created_at)
select 'rp-agentapproval-' || o.id || '-' || p.subject_id,
       o.id, 'ROLE', p.subject_id, 'agent-approval', '*', 'decide', 'ALLOW', p.condition_json, CURRENT_TIMESTAMP
from (values ('org-alpha'), ('org-beta'), ('org-gamma')) as o(id)
cross join (values
    ('platform_admin',    '{"maxRisk":"R5"}'),
    ('org_admin',         '{"maxRisk":"R3"}'),
    ('security_operator', '{"maxRisk":"R3"}')
) as p(subject_id, condition_json);
