-- 特权助手对 service/firewall 写操作只回传 stdout；控制面 structuredPayload 将其收成 {output}。
-- 删除从未采集过的 previousState/newState/rule/durationMinutes 输出契约。

update tool_package_version
set output_schema_json = '{"type":"object","additionalProperties":false,"properties":{"output":{"type":"string"}},"required":["output"]}'
where id in (
    'toolpkgver-service-start-v1',
    'toolpkgver-service-stop-v1',
    'toolpkgver-service-restart-v1',
    'toolpkgver-service-reload-v1',
    'toolpkgver-service-enable-v1',
    'toolpkgver-service-disable-v1',
    'toolpkgver-firewall-block-v1',
    'toolpkgver-firewall-unblock-v1'
);
