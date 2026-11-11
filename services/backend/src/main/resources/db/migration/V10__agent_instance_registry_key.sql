-- 专家注册表（app_agent_registry）接入运行时下发路径所需的身份桥。
--
-- 问题：注册表用 app_agent_registry.agent_key 标识专家（sense-collector /
-- incident-responder / cleanup-operator …），而运行时的执行主体是 agent_instance。
-- 两者此前没有任何关联列——recruit_agent 派生的子 Agent 走的是「动态规格」路径
-- （definition_version_id 恒为 null，规格只在内存里传递、冻结进 definition_snapshot_json），
-- 因此连 agent_definition.definition_key 都没有可用值。没有这一列，DispatchGate
-- 就无法回答「当前这次工具调用是哪一位注册专家发起的」，AgentRegistryService.authorize
-- 也就只能停留在「引擎先行」。
--
-- 设计取舍：**可空，且不回填**。
--   · 根 Agent（depth=0，由人直接发起）不是注册表里的专家，本来就没有 key；
--   · 存量 run 的 agent_instance 同样没有 key。
-- DispatchGate 的约定是「key 为 null → 不做注册表判定」。如果这里给一个非空默认值
-- 或强行回填，等于给所有存量实例安上一个它并不具备的身份，下一次下发就会被
-- 能力封套判定拒绝，把现有全部链路一次性打断。fail-closed 的正确落点是
-- 「有身份就必须在封套内」，不是「没有身份就一律拒绝」。
--
-- 索引用于注册表侧反查「某位专家当前有哪些活跃实例」（运营/审计视角），
-- 判定路径本身按主键查 agent_instance，不依赖它。
alter table agent_instance add column registry_agent_key varchar(64);

create index agent_instance_registry_key_idx on agent_instance (registry_agent_key);
