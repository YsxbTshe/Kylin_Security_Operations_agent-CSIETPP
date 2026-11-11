-- V57_1: resource_lock 过期扫描的索引（PostgreSQL 专有）。
-- 原 V57 内联 CREATE INDEX ... WHERE 子句，H2 无法解析；按 V15_1 先例拆分到
-- db/postgresql（FlywayConfig#resolvedLocations 仅在 PostgreSQL 上追加该目录）。
--
-- 谓词里的 now() 已去掉：PostgreSQL 要求索引谓词是 IMMUTABLE 的，
-- `where expires_at < now() + interval '30 minutes'` 会被直接拒绝
-- （ERROR: functions in index predicate must be marked IMMUTABLE），
-- 这条迁移在真实 PG 上从未成功执行过。改为对非空 expires_at 建索引：
-- 过期扫描的查询形如 `expires_at > ?`（见 DashboardService / ResourceLockStore），
-- 参数化的时间点由查询提供，索引不需要（也不能）把"当前时间"烘进定义里。
create index if not exists resource_lock_expiry_idx
    on resource_lock (expires_at)
    where expires_at is not null;
