-- 心跳补传逐探针明细后落入会话表（单一事实源）。
-- app_managed_host.native_guard_json / *_count 仍无写入方，展示改读这里。
alter table agent_runtime_node_session add column native_guard_json text;
alter table agent_runtime_node_session add column native_guard_critical_count integer not null default 0;
alter table agent_runtime_node_session add column native_guard_warning_count integer not null default 0;
