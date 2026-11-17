# Kylin SafeOps Node Executor

The Node Executor is a deterministic, non-LLM execution boundary. It no longer runs a local
LLM/ReAct loop and no longer polls old task/turn/trace endpoints.

Current baseline:

- Enrolls with the backend, registers over gRPC/mTLS, and keeps a ControlStream open.
- Exposes a standard MCP tool surface in code (`initialize`, `tools/list`, `tools/call`, `outputSchema`, `notifications/tools/list_changed`).
- Executes only structured SafeToolCall commands received from ControlStream through allowlisted MCP tool names.
- Schedules local execution through configurable worker slots and a local runner pool (`KYLIN_SCHEDULER_MAX_PARALLEL`, `KYLIN_RUNNER_POOL_SIZE`), optionally executing each runner slot in a child process with `KYLIN_RUNNER_ISOLATION_MODE=process` and `KYLIN_RUNNER_PROCESS_TIMEOUT_SECONDS`, persists accepted commands before execution, restores queued/running commands after restart, and exposes runner state in heartbeat / metrics.
- Uses Execution Context plus a durable local offline audit buffer at `{KYLIN_NODE_EXECUTOR_SESSION_DIR}/audit-buffer/events.jsonl` for non-heartbeat ControlStream events.
- Fails closed when `KYLIN_NODE_EXECUTOR_SESSION_DIR` is unavailable or a non-heartbeat audit event is missing `event_id`, `node_id`, or `event_type`, so events are not sent without durable local persistence.
- Replays unacknowledged audit events after reconnect, records local replay attempts, and removes them only after backend single-event or batch ACK.
- Protects the local offline audit buffer with a JSONL entry hash chain, capacity fail-closed limits, legacy file migration, and corrupt/tampered/missing-hash row quarantine before replay.
- Keeps the existing executor handlers internally, mapped to MCP names:
  - `kylin.disk.usage`
  - `kylin.process.list`
  - `kylin.service.status`
  - `kylin.service.restart`
  - `kylin.firewall.state`
  - `kylin.log.journal_query`

No generic shell tool is exposed.
