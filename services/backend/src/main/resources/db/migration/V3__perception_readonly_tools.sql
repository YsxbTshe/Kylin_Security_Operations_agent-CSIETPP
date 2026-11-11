-- 感知/诊断只读工具接入新链路 ToolRegistry。
-- 行形态与 V57 写工具相同（tool_package_id + tool_id + schema 分列，digest pending）。
-- 运行时校验以 Java PerceptionCatalog 为准；本表仅满足 tool_invocation 外键。
-- 同步把 cleanup.execute / firewall.block_ip 的入参列改成与 Go defaultCatalog 一致。

update tool_package_version
set input_schema_json = '{"type":"object","additionalProperties":false,"properties":{"ip":{"type":"string","minLength":1},"scope":{"type":"string","minLength":1},"duration":{"type":"string","minLength":1}},"required":["ip","scope","duration"]}'
where id = 'toolpkgver-firewall-block-v1';

update tool_package_version
set input_schema_json = '{"type":"object","additionalProperties":false,"properties":{"targetDir":{"type":"string","minLength":1},"mode":{"type":"string","enum":["archive","archive_then_truncate"]},"maxFiles":{"type":"integer","minimum":1,"maximum":100}}}'
where id = 'toolpkgver-cleanup-execute-v1';

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values
('toolpkgver-disk-usage-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.disk.usage', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-disk-large_files-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.disk.large_files', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-io-snapshot-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.io.snapshot', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-metrics-node_exporter_snapshot-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.metrics.node_exporter_snapshot', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-process-list-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.process.list', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-process-top_cpu-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.process.top_cpu', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-process-top_memory-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.process.top_memory', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-firewall-state-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.firewall.state', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-firewall-rules-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.firewall.rules', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-net-listening_ports-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.net.listening_ports', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-net-connections-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.net.connections', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-log-journal_query-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.log.journal_query', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-file-lsof_path-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.file.lsof_path', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-file-stat-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.file.stat', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-file-sm3-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.file.sm3', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-system-sysctl_snapshot-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.system.sysctl_snapshot', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-system-mounts-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.system.mounts', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-package-list-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.package.list', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-package-info-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.package.info', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-user-accounts-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.user.accounts', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-user-privileged_groups-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.user.privileged_groups', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-user-last_logins-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.user.last_logins', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-config-permissions_snapshot-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.config.permissions_snapshot', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-diagnosis-triage-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.diagnosis.triage', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-cleanup-plan-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.cleanup.plan', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-security-kysec_status-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.security.kysec_status', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-security-selinux_status-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.security.selinux_status', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-security-auditd_status-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.security.auditd_status', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-security-security_switch_status-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.security.security_switch_status', '{}', '{"type":"object","additionalProperties":true,"properties":{"output":{"type":"string"}}}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now());
