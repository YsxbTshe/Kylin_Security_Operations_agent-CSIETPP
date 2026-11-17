-- V11：为 RUNTIME 执行器工具补 tool_package_version 行。
--
-- 为什么必须补：tool_invocation.tool_package_version_id 是 not null 且外键到
-- tool_package_version。控制面工具（RuntimeTool）各自声明了 toolpkgver-runtime-* 的
-- packageVersionId，但这些行从未被任何迁移种过——于是只要一个 RUNTIME 工具走上
-- 审批分支（DispatchGate 判 NEEDS_APPROVAL → requestApproval →
-- createAwaitingToolInvocation），insert 就会撞外键，异常向上抛，整条 run 被判 FAILED。
--
-- 触发路径是真实存在的，不是理论问题：
--   * PolicyEngine.containsSensitivePath 会把参数里出现 /etc、/boot、/usr、
--     /var/lib/mysql 等前缀的调用升到 R4 —— 模型只要给 kylin.vfs.read 传一个
--     "/etc/..." 之类的串就会命中；
--   * POLICY_AUDITD_DOWN_ESCALATION / POLICY_KYSEC_DISABLED_ESCALATION 会在
--     objective 里出现相应字样时把风险抬到 R3/R4；
--   * 工具自身也可能声明 alwaysRequireApproval。
-- 任何一条命中，用户看到的都不是「这一步需要审批」而是「任务失败」，且失败原因是
-- 一个数据库外键错误 —— 策略判定退化成崩溃，这是本次要修的实质缺陷。
--
-- 补上这些行之后，RUNTIME 工具与节点工具在审批链路上行为一致：该审批就挂起等审批，
-- 该拒绝就干净拒绝。既有的敏感路径护栏一点没有放松（这一点是刻意的：读 /etc 下的文件
-- 本就该经人确认，正确表现是走审批，而不是让整条 run 崩掉）。
--
-- 口径与 V3 一致：runtime 工具不属于节点工具包，单独归 kylin.runtime 包；
-- schema digest 沿用 'pending'（运行时工具从未进入签名清单，此处不伪造签名）。

insert into tool_package (id, package_key, title, enabled, created_at, updated_at)
values ('toolpkg-kylin-runtime', 'kylin.runtime', 'Kylin Control-Plane Runtime Tools',
        true, current_timestamp, current_timestamp);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values
('toolpkgver-runtime-knowledge-search-v1', 'toolpkg-kylin-runtime', '1.0.0',
 'kylin.runtime.knowledge_search', '{}',
 '{"type":"object","additionalProperties":true}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-runtime-recruit-v1', 'toolpkg-kylin-runtime', '1.0.0',
 'kylin.runtime.recruit_agent', '{}',
 '{"type":"object","additionalProperties":true}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-runtime-skill-search-v1', 'toolpkg-kylin-runtime', '1.0.0',
 'kylin.skill.search', '{}',
 '{"type":"object","additionalProperties":true}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-runtime-skill-load-v1', 'toolpkg-kylin-runtime', '1.0.0',
 'kylin.skill.load', '{}',
 '{"type":"object","additionalProperties":true}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()),
('toolpkgver-runtime-vfs-read-v1', 'toolpkg-kylin-runtime', '1.0.0',
 'kylin.vfs.read', '{}',
 '{"type":"object","additionalProperties":true}', 'pending', 'pending', 'SM2_WITH_SM3', 'pending', now());
