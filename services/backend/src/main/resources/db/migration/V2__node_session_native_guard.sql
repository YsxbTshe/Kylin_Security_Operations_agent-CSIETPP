-- 迭代 7.6：Native Guard 姿态进入控制面（此前 worker 硬编码 "AVAILABLE"，freshness 门形同虚设）。
-- 心跳（NodeHeartbeat.native_guard_status / native_guard_observed_at，proto 字段早已就绪）
-- 落入节点会话表；DispatchGate 依据真实姿态与观测时间做 R3+ 的新鲜度与 CRITICAL 拒绝。
alter table agent_runtime_node_session add column native_guard_status varchar(32);
alter table agent_runtime_node_session add column native_guard_observed_at timestamp;
