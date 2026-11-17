-- 初始口令必须更换：种子账号与新建账号默认要求改密。
-- 演示/测试可通过 app.auth.enforce-password-change=false 关闭拦截，但仍返回 mustChangePassword。
alter table app_user_account add column must_change_password boolean not null default true;

update app_user_account
set must_change_password = true
where username in ('admin', 'secops', 'auditor', 'analyst');
