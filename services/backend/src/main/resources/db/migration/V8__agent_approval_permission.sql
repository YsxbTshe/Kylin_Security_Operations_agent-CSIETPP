-- Agent 审批决策的专用功能权限。
--
-- 背景：跨租户越权整改给 /api/v2/approvals 的四个端点补功能权限时，权限目录里
-- 没有任何与「批准 Agent 高风险工具调用」对应的码，只能借用 incident:respond
-- （「处置安全事件（复用 SafeOps 审批）」）。主体集合恰好一致。
-- 但 R3 写操作也可以由常规运维任务触发，因此为审批决策定义独立权限。
--
-- 授予范围与 V6__agent_approval_abac_policies.sql 里 agent-approval:decide 的
-- ABAC 主体集合保持完全一致：platform_admin / org_admin / security_operator。
-- auditor、analyst、viewer 不授予——只读角色不应具备放行写操作的能力。
--
-- 功能权限只是第一道闸。具体某一单能不能批，仍由 ApprovalService.decide 的
-- 资源级 ABAC 判定（风险上限、发起人不可自批）决定，两者是与关系。
insert into app_permission (code, name, module, description) values
('agent:approve', '审批Agent高风险操作', 'agent', '批准或拒绝 Agent 发起的 R3/R4 写操作审批单');

insert into app_role_permission (role_code, permission_code) values
('platform_admin', 'agent:approve'),
('org_admin', 'agent:approve'),
('security_operator', 'agent:approve');
-- 注释修订保留已部署 V8 的 Flyway 校验值：MJBKEGHJHA
