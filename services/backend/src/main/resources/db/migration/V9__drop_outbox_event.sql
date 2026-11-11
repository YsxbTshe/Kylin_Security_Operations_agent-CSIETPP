-- outbox_event 下线（数据层欠账清理）。
--
-- 这张表是事务外发消息箱，唯一的消费者是 Redis Stream 发布器；发布器已随 Phase 7
-- 重构删除（pom 里连 redis 依赖都没有了），此后 AgentRunEventRepository.appendEvent
-- 仍为每条审计事件多写一份**带完整正文**的 PENDING 行，而全库无任何读取方——
-- 只有 BackendMetrics / DashboardService 数它的行数。后果有两条，都不可接受：
--   1. 表无界增长：每条 run 事件写两份，永远没人搬走；
--   2. 保留策略绕过：RuntimeRetentionService 只清 agent_run_event，
--      事件正文在保留期到点后仍完整留在 outbox_event 里。
--
-- 审计的权威来源是 agent_run_event 的 SM3 哈希链（prev_hash/event_hash 可校验），
-- outbox 从不参与校验。留一张永远 PENDING 的表只会让人误以为存在异步投递链路。
-- 将来真要接消息总线，重新加一张带消费者的表即可，不需要现在留空壳。
drop index if exists outbox_agent_run_pending_idx;

drop table if exists outbox_event;
