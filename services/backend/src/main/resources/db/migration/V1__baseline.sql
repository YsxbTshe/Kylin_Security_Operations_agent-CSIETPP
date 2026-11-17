-- ============================================================================
-- V1__baseline.sql — Phase 7 迭代 7.4：Flyway 新开发基线
--
-- 旧迁移链 V1..V68 + V56_1（共 69 个文件）已压缩为本单一基线：只保留迭代 7.2
-- 删除旧执行链路（safeops/hostcontrol/platformagent/旧 checkpoint/recruitment/
-- assistant）后**存活代码仍在使用的表**的最终形态（含现存种子数据）。
-- 按设计文档（docs/01-架构设计/Agent执行架构-现状与设计差距.md §Flyway 基线）
-- 的口径：已有开发数据库统一重建，不迁移旧数据、不做兼容。
--
-- 已移除的旧链路专用表（不再创建）：
--   app_host_agent_task / app_host_agent_trace / app_host_agent_approval /
--   app_host_agent_turn（platformagent/hostcontrol 链路）
--   app_assistant_session / app_assistant_message / app_assistant_attachment /
--   app_assistant_message_step / app_assistant_host_task（assistant 链路）
--   host_tool_call / host_task_revision / rollback_record / host_agent_artifact
--   （旧 SafeOps 事件存储中只服务旧链路的表）
--   app_agent_checkpoint / app_agent_recruitment（旧 checkpoint/招募，已被
--   agent_run_checkpoint 与 app_agent_registry 取代）
-- 相应地：host_task_event / host_resource_lock 去掉了指向
-- app_host_agent_task 的外键（观测面 BackendMetrics/DashboardService 仍读这两表）；
-- agent_run 去掉了 session_id 列（仅服务已删除的 assistant 会话链路）。
--
-- PostgreSQL 专有对象不在本文件内：pgvector 扩展/embedding 列见
-- db/postgresql/V15_1，resource_lock 过期部分索引见 db/postgresql/V57_1
-- （FlywayConfig#resolvedLocations 仅在 PostgreSQL 上追加该目录）。
-- 本文件语法与旧迁移保持一致：H2（MODE=PostgreSQL）与 PostgreSQL 均可执行。
-- ============================================================================

-- ============================================================================
-- 一、IAM 与组织（原 V1/V2/V8/V9/V10，种子为最终形态）
-- ============================================================================

create table app_organization (
    id varchar(64) primary key,
    code varchar(64) not null unique,
    name varchar(128) not null
);

create table app_role (
    code varchar(64) primary key,
    name varchar(128) not null,
    description varchar(255) not null
);

create table app_permission (
    code varchar(64) primary key,
    name varchar(128) not null,
    module varchar(64) not null,
    description varchar(255)
);

create table app_role_permission (
    role_code varchar(64) not null references app_role(code) on delete cascade,
    permission_code varchar(64) not null references app_permission(code) on delete cascade,
    primary key (role_code, permission_code)
);

create table app_user_account (
    id varchar(64) primary key,
    username varchar(64) not null unique,
    display_name varchar(128) not null,
    password varchar(128) not null,
    enabled boolean not null default true,
    email varchar(128),
    avatar_url varchar(512)
);

create table app_user_role (
    user_id varchar(64) not null references app_user_account(id) on delete cascade,
    role_code varchar(64) not null references app_role(code) on delete cascade,
    primary key (user_id, role_code)
);

create table app_user_organization (
    user_id varchar(64) not null references app_user_account(id) on delete cascade,
    organization_id varchar(64) not null references app_organization(id) on delete cascade,
    is_default boolean not null default false,
    primary key (user_id, organization_id)
);

create table app_user_session (
    token varchar(128) primary key,
    user_id varchar(64) not null references app_user_account(id) on delete cascade,
    current_organization_id varchar(64) not null references app_organization(id),
    expires_at timestamp not null
);

create index idx_app_user_session_user_id on app_user_session(user_id);
create index idx_app_user_session_expires_at on app_user_session(expires_at);

insert into app_organization (id, code, name) values
('org-alpha', 'alpha', '麒麟安全研究院'),
('org-beta', 'beta', '集群运维中心'),
('org-gamma', 'gamma', '攻防演练实验室');

insert into app_role (code, name, description) values
('platform_admin', '平台管理员', '管理全局用户、组织、角色与平台配置'),
('org_admin', '组织管理员', '管理组织内用户并负责组织内安全运维'),
('security_operator', '安全运维', '负责主机、告警、审计与智能研判'),
('auditor', '审计员', '查看告警和审计记录'),
('viewer', '只读分析员', '查看组织内概览、主机与告警');

insert into app_permission (code, name, module, description) values
('overview:read', '查看系统总览', 'overview', '查看当前组织下的态势总览'),
('host:read', '查看主机数据', 'host', '查看当前组织下的主机资产'),
('alert:read', '查看告警数据', 'alert', '查看当前组织下的告警列表'),
('audit:read', '查看审计数据', 'audit', '查看当前组织下的审计记录'),
('assistant:use', '使用智能助手', 'assistant', '使用智能助手进行检索问答'),
('user:read', '查看用户', 'user', '查看当前组织成员'),
('user:write', '编辑用户', 'user', '创建和更新用户、角色与组织归属'),
('role:read', '查看角色权限', 'role', '查看角色及其权限矩阵'),
('permission:read', '查看权限定义', 'permission', '查看系统权限清单'),
('organization:read', '查看组织', 'organization', '查看所属组织信息'),
('organization:switch', '切换当前组织', 'organization', '在所属组织之间切换上下文'),
('agent:read', '查看Agent状态', 'agent', '查看Agent执行与协同状态'),
('platform:configure', '配置平台模型', 'platform', '管理平台级模型接口配置'),
('organization:write', '管理组织', 'organization', '创建和修改组织'),
('role:write', '管理角色与权限', 'role', '创建和修改角色与权限配置'),
('permission:write', '管理权限节点', 'permission', '创建和修改权限节点配置');

insert into app_role_permission (role_code, permission_code) values
('platform_admin', 'overview:read'),
('platform_admin', 'host:read'),
('platform_admin', 'alert:read'),
('platform_admin', 'audit:read'),
('platform_admin', 'assistant:use'),
('platform_admin', 'user:read'),
('platform_admin', 'user:write'),
('platform_admin', 'role:read'),
('platform_admin', 'permission:read'),
('platform_admin', 'organization:read'),
('platform_admin', 'organization:switch'),
('platform_admin', 'agent:read'),
('platform_admin', 'platform:configure'),
('platform_admin', 'organization:write'),
('platform_admin', 'role:write'),
('platform_admin', 'permission:write'),
('org_admin', 'overview:read'),
('org_admin', 'host:read'),
('org_admin', 'alert:read'),
('org_admin', 'audit:read'),
('org_admin', 'assistant:use'),
('org_admin', 'user:read'),
('org_admin', 'user:write'),
('org_admin', 'role:read'),
('org_admin', 'permission:read'),
('org_admin', 'organization:read'),
('org_admin', 'organization:switch'),
('org_admin', 'agent:read'),
('security_operator', 'overview:read'),
('security_operator', 'host:read'),
('security_operator', 'alert:read'),
('security_operator', 'audit:read'),
('security_operator', 'assistant:use'),
('security_operator', 'organization:read'),
('security_operator', 'organization:switch'),
('security_operator', 'agent:read'),
('auditor', 'overview:read'),
('auditor', 'alert:read'),
('auditor', 'audit:read'),
('auditor', 'permission:read'),
('auditor', 'organization:read'),
('auditor', 'organization:switch'),
('viewer', 'overview:read'),
('viewer', 'host:read'),
('viewer', 'alert:read'),
('viewer', 'organization:read'),
('viewer', 'organization:switch'),
('viewer', 'agent:read');

-- 口令为 pbkdf2_sm3 哈希（原 V37 最终形态）。
insert into app_user_account (id, username, display_name, password, enabled) values
('user-admin', 'admin', '平台管理员', 'pbkdf2_sm3$120000$a3lsaW5vcHMtYWRtaW4tdjM=$KZwFku74AJsWQMA6KPbZHM07myMs2TpELsXVk2pL0rM=', true),
('user-secops', 'secops', '安全运维管理员', 'pbkdf2_sm3$120000$a3lsaW5vcHMtc2Vjb3BzLXYz$MrsTOzBA13l61hZtulj7QQMC9C3TKUZdzOdRABy5LGk=', true),
('user-auditor', 'auditor', '组织审计员', 'pbkdf2_sm3$120000$a3lsaW5vcHMtYXVkaXRvci12Mw==$Uv93o4K5V0cDp3OGk63U2ALDyAS5c/kO750NQVv/AQw=', true),
('user-analyst', 'analyst', '只读分析员', 'pbkdf2_sm3$120000$a3lsaW5vcHMtYW5hbHlzdC12Mw==$siI9JEh8FLm8ntQqq+zeV0lotQ7ltoBsutzGwK8r3Kg=', true);

insert into app_user_role (user_id, role_code) values
('user-admin', 'platform_admin'),
('user-secops', 'org_admin'),
('user-auditor', 'auditor'),
('user-analyst', 'viewer');

insert into app_user_organization (user_id, organization_id, is_default) values
('user-admin', 'org-alpha', true),
('user-admin', 'org-beta', false),
('user-admin', 'org-gamma', false),
('user-secops', 'org-alpha', true),
('user-secops', 'org-beta', false),
('user-auditor', 'org-alpha', true),
('user-analyst', 'org-beta', true);

-- ============================================================================
-- 二、主机与告警/审计（原 V1/V6/V14/V25/V29/V30/V32/V33/V35 合并后的最终形态）
-- ============================================================================

create table app_managed_host (
    id bigint generated by default as identity primary key,
    organization_id varchar(64) not null references app_organization(id),
    hostname varchar(128) not null,
    ip varchar(64) not null,
    status varchar(32) not null,
    os_version varchar(128) not null,
    node_id varchar(128),
    cluster_name varchar(128),
    agent_version varchar(64),
    last_heartbeat_at timestamp,
    cpu_usage varchar(64) not null default '-',
    memory_usage varchar(64) not null default '-',
    disk_usage varchar(64) not null default '-',
    load_average varchar(64) not null default '-',
    uptime_text varchar(64) not null default '-',
    supported_tools_json varchar(4000) not null default '[]',
    grpc_connected boolean not null default false,
    grpc_connected_at timestamp,
    cert_subject varchar(512),
    cert_expires_at timestamp,
    tool_manifest_json varchar(65536),
    cert_serial_number varchar(128),
    cert_status varchar(32) not null default 'ACTIVE',
    cert_issued_at timestamp,
    cert_revoked_at timestamp,
    cert_revocation_reason varchar(512),
    native_guard_status varchar(32),
    native_guard_json varchar(65536),
    native_guard_checked_at timestamp,
    native_guard_critical_count integer not null default 0,
    native_guard_warning_count integer not null default 0,
    node_exporter_status varchar(32),
    node_exporter_json varchar(65536),
    node_exporter_checked_at timestamp,
    node_exporter_cpu_usage_percent double precision,
    node_exporter_memory_usage_percent double precision,
    node_exporter_filesystem_usage_percent double precision,
    node_exporter_load1 double precision
);

create table app_alert_event (
    id bigint generated by default as identity primary key,
    organization_id varchar(64) not null references app_organization(id),
    level varchar(32) not null,
    type varchar(64) not null,
    host varchar(128) not null,
    status varchar(32) not null,
    suggestion varchar(255) not null
);

create table app_audit_entry (
    id bigint generated by default as identity primary key,
    organization_id varchar(64) not null references app_organization(id),
    operator varchar(128) not null,
    action varchar(128) not null,
    target varchar(128) not null,
    event_time timestamp not null
);

-- Node 引导令牌（NodeExecutorV2GrpcService 存活）。
create table host_agent_bootstrap_token (
    id bigint generated by default as identity primary key,
    organization_id varchar(64) not null references app_organization(id) on delete cascade,
    token_hash varchar(128) not null unique,
    expires_at timestamp not null,
    consumed_at timestamp,
    created_at timestamp not null
);

-- 主机证书重签审批（HostService 存活）。
create table host_agent_certificate_reissue_request (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id) on delete cascade,
    node_id varchar(128) not null,
    hostname varchar(128) not null,
    requested_by varchar(128) not null,
    reason varchar(1024),
    status varchar(32) not null,
    requested_at timestamp not null,
    decided_by varchar(128),
    decided_at timestamp,
    decision_note varchar(1024),
    reissue_token_hash varchar(128),
    reissue_token_expires_at timestamp,
    reissue_token_consumed_at timestamp,
    updated_at timestamp not null
);

create index idx_host_cert_reissue_org_node_status
    on host_agent_certificate_reissue_request (organization_id, node_id, status);

create unique index uq_host_cert_reissue_active_token
    on host_agent_certificate_reissue_request (reissue_token_hash);

-- 主机任务事件流与资源锁：旧链路写入方已删除，但观测面
-- （BackendMetrics/DashboardService）仍读取计数；去掉对已删表的外键。
create table host_task_event (
    id bigint generated by default as identity primary key,
    event_id varchar(128) not null unique,
    prev_hash varchar(128) not null,
    event_hash varchar(128) not null,
    hash_algorithm varchar(32) not null,
    task_id varchar(64) not null,
    session_id varchar(64),
    node_id varchar(128) not null,
    trace_id varchar(128) not null,
    event_type varchar(64) not null,
    agent_role varchar(64),
    subtask_id varchar(128),
    risk_code varchar(8) not null,
    -- text 而不是 varchar(16777216)：后者 H2 收，PostgreSQL 直接报
    -- "length for type varchar cannot exceed 10485760"，整条 V1 基线在真实 PG 上建不起来
    -- （CI 的 postgres job 与 PostgresPgvectorMigrationIT 因此一直红）。两库的 text 语义一致。
    payload_json text not null,
    created_at timestamp not null
);

create table host_resource_lock (
    id bigint generated by default as identity primary key,
    node_id varchar(128) not null,
    task_id varchar(64) not null,
    resource_key varchar(512) not null,
    lock_type varchar(32) not null,
    expires_at timestamp,
    created_at timestamp not null,
    command_id varchar(128)
);

create index idx_host_resource_lock_command on host_resource_lock(command_id);

-- 事务外发消息箱（grpc/redis 发布链路存活）。
create table outbox_event (
    id bigint generated by default as identity primary key,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id varchar(128) not null,
    payload_json varchar(65536) not null,
    status varchar(32) not null,
    created_at timestamp not null,
    published_at timestamp,
    stream_name varchar(128),
    redis_message_id varchar(128),
    publish_attempts integer not null default 0,
    next_attempt_at timestamp,
    last_error varchar(2048),
    failed_at timestamp
);

-- Redis Stream 消费失败记录（DashboardService/BackendMetrics 存活）。
create table redis_stream_consumer_failure (
    id bigint generated by default as identity primary key,
    stream_name varchar(128) not null,
    group_name varchar(128) not null,
    message_id varchar(128) not null,
    event_type varchar(128) not null,
    payload_json varchar(65536) not null,
    attempt_count integer not null,
    last_error varchar(2048),
    status varchar(32) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    dead_lettered_at timestamp,
    constraint redis_stream_consumer_failure_uq unique (stream_name, group_name, message_id)
);

-- SafeOps 可配置策略 DSL 版本（PolicyEngine 存活）。
create table safeops_policy_version (
    id bigint generated by default as identity primary key,
    version varchar(64) not null unique,
    description varchar(512),
    rules_json varchar(65536) not null,
    enabled boolean not null default false,
    created_by varchar(64),
    created_at timestamp not null,
    activated_at timestamp
);

insert into safeops_policy_version (
    version, description, rules_json, enabled, created_by, created_at, activated_at
) values (
    'safeops-default-v1',
    'Default SafeOps configurable policy DSL rules',
    '[
      {
        "policyId": "POLICY_DSL_DISABLE_NATIVE_GUARD",
        "message": "DSL: 禁止关闭 KYSEC / SELinux / auditd / security-switch",
        "riskCode": "R5",
        "deny": true,
        "tools": ["*"],
        "containsAny": ["关闭kysec", "禁用kysec", "关闭 selinux", "禁用 selinux", "停止auditd", "禁用auditd", "security-switch off", "关闭安全开关"]
      },
      {
        "policyId": "POLICY_DSL_RESTART_REQUIRES_APPROVAL",
        "message": "DSL: 服务重启必须审批",
        "riskCode": "R3",
        "requiresApproval": true,
        "tools": ["kylin.service.restart"],
        "containsAny": ["restart", "重启"]
      }
    ]',
    true,
    'system',
    current_timestamp,
    current_timestamp
);

-- ============================================================================
-- 三、平台模型配置（原 V4/V11/V19/V51 合并后的最终形态）
-- ============================================================================

create table app_platform_llm_config (
    id varchar(64) primary key,
    provider_type varchar(32) not null,
    base_url varchar(512) not null,
    api_key varchar(512),
    model varchar(128) not null,
    enabled boolean not null default false,
    timeout_ms integer not null,
    updated_by varchar(128) not null,
    updated_at timestamp not null,
    group_name varchar(128) not null default '默认分组',
    retry_count integer not null default 0,
    thinking_levels varchar(128) not null default 'OFF',
    thinking_default varchar(16) not null default 'OFF'
);

insert into app_platform_llm_config (
    id, provider_type, base_url, api_key, model, enabled, timeout_ms, updated_by, updated_at,
    group_name, retry_count, thinking_levels, thinking_default
) values (
    'default',
    'OPENAI_COMPATIBLE',
    '',
    '',
    'gpt-4.1-mini',
    false,
    15000,
    'system',
    timestamp '2026-04-12 00:00:00',
    '默认分组',
    0,
    'OFF',
    'OFF'
);

-- ============================================================================
-- 四、RAG / 知识图谱 / 记忆（原 V15/V18/V23/V40/V41/V44/V45/V46/V47 合并）
-- ============================================================================

create table app_rag_document (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    title varchar(255) not null,
    source_type varchar(64) not null,
    content text not null,
    embedding_text text,
    created_at timestamp not null,
    external_ref varchar(128),
    updated_at timestamp,
    source_url varchar(512) not null default '',
    source_file_name varchar(255) not null default ''
);

create index app_rag_document_org_idx on app_rag_document (organization_id);

insert into app_rag_document (id, organization_id, title, source_type, content, created_at, updated_at) values
('rag-seed-host-agent-loop', 'org-alpha', 'Host Agent execution loop', 'SYSTEM',
 'Host Agent follows a closed loop: collect context, request next-step, execute approved tools, report turns and traces, then complete the task.', current_timestamp, current_timestamp),
('rag-seed-approval-audit', 'org-alpha', 'Approval and audit policy', 'SYSTEM',
 'Read-only tools may run without manual approval. State-changing tools require platform approval and must leave turn, trace, approval, and platform audit records.', current_timestamp, current_timestamp),
('rag-seed-pgvector-plan', 'org-alpha', 'pgvector integration plan', 'SYSTEM',
 'Production RAG uses PostgreSQL pgvector with app_rag_document.embedding, vector indexes, and cosine or L2 similarity search after embeddings are generated.', current_timestamp, current_timestamp);

create table app_rag_memory (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    memory_type varchar(64) not null,
    title varchar(255) not null,
    content text not null,
    importance integer not null,
    source_document_id varchar(64) references app_rag_document(id) on delete set null,
    created_at timestamp not null,
    updated_at timestamp not null,
    embedding_json text,
    memory_source varchar(32) default 'MANUAL',
    last_accessed_at timestamp,
    access_count integer default 0,
    event_ref varchar(64),
    valid boolean default true,
    source_task_id varchar(64),
    task_outcome varchar(32),
    supersedes_id varchar(64),
    verified boolean default false,
    valid_until timestamp,
    scope varchar(128)
);

create index app_rag_memory_org_idx on app_rag_memory (organization_id, updated_at desc);
create index app_rag_memory_source_idx on app_rag_memory (organization_id, memory_source);
create index app_rag_memory_supersedes_idx on app_rag_memory (organization_id, supersedes_id);
create index app_rag_memory_scope_idx on app_rag_memory (organization_id, scope);

create table app_rag_graph_edge (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    source_node varchar(255) not null,
    relation varchar(128) not null,
    target_node varchar(255) not null,
    evidence text,
    source_document_id varchar(64) references app_rag_document(id) on delete set null,
    created_at timestamp not null
);

create index app_rag_graph_edge_org_idx on app_rag_graph_edge (organization_id, source_node);

create table app_rag_config (
    id varchar(64) primary key,
    embedding_provider varchar(32) not null,
    embedding_base_url varchar(512) not null,
    embedding_api_key varchar(512),
    embedding_model varchar(128) not null,
    embedding_dimension integer not null,
    chunk_size integer not null,
    chunk_overlap integer not null,
    top_k integer not null,
    milvus_enabled boolean not null default false,
    milvus_base_url varchar(512) not null,
    milvus_collection varchar(128) not null,
    elasticsearch_enabled boolean not null default false,
    elasticsearch_base_url varchar(512) not null,
    elasticsearch_index varchar(128) not null,
    redis_enabled boolean not null default false,
    redis_base_url varchar(512) not null,
    redis_prefix varchar(128) not null,
    updated_by varchar(128) not null,
    updated_at timestamp not null,
    reranker_enabled boolean not null default false,
    reranker_base_url varchar(512) not null default '',
    reranker_api_key varchar(512) not null default '',
    reranker_model varchar(128) not null default '',
    reranker_top_n integer not null default 20,
    vision_config_id varchar(64) not null default ''
);

insert into app_rag_config (
    id, embedding_provider, embedding_base_url, embedding_api_key, embedding_model,
    embedding_dimension, chunk_size, chunk_overlap, top_k,
    milvus_enabled, milvus_base_url, milvus_collection,
    elasticsearch_enabled, elasticsearch_base_url, elasticsearch_index,
    redis_enabled, redis_base_url, redis_prefix,
    updated_by, updated_at
) values (
    'default', 'LOCAL', '', '', 'local-hash-embedding',
    384, 800, 120, 5,
    false, 'http://localhost:19530', 'kylinops_rag_chunks',
    false, 'http://localhost:9200', 'kylinops-rag-chunks',
    false, 'localhost:6379', 'kylinops',
    'system', current_timestamp
);

create table app_rag_chunk (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    document_id varchar(64) not null references app_rag_document(id) on delete cascade,
    chunk_index integer not null,
    title varchar(255) not null,
    source_type varchar(64) not null,
    content text not null,
    embedding_json text not null,
    token_count integer not null,
    created_at timestamp not null
);

create index app_rag_chunk_org_idx on app_rag_chunk (organization_id);
create index app_rag_chunk_document_idx on app_rag_chunk (document_id, chunk_index);

create table app_kg_node (
    id              varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    name            varchar(255) not null,
    normalized_name varchar(255) not null,
    node_type       varchar(48)  not null,
    description     text,
    embedding_json  text,
    salience        double precision not null default 0,
    mention_count   integer not null default 0,
    created_at      timestamp not null,
    updated_at      timestamp not null,
    constraint app_kg_node_uq unique (organization_id, normalized_name, node_type)
);

create index app_kg_node_org_type_idx on app_kg_node (organization_id, node_type);

create table app_kg_edge (
    id                 varchar(64) primary key,
    organization_id    varchar(64) not null references app_organization(id),
    src_node_id        varchar(64) not null references app_kg_node(id) on delete cascade,
    dst_node_id        varchar(64) not null references app_kg_node(id) on delete cascade,
    relation           varchar(48) not null,
    weight             double precision not null default 1.0,
    evidence           text,
    source_document_id varchar(64) references app_rag_document(id) on delete set null,
    source_chunk_id    varchar(64),
    created_at         timestamp not null,
    constraint app_kg_edge_uq unique (organization_id, src_node_id, dst_node_id, relation)
);

create index app_kg_edge_src_idx on app_kg_edge (organization_id, src_node_id);
create index app_kg_edge_dst_idx on app_kg_edge (organization_id, dst_node_id);

create table app_kg_node_chunk (
    organization_id varchar(64) not null,
    node_id         varchar(64) not null references app_kg_node(id) on delete cascade,
    chunk_id        varchar(64) not null,
    weight          double precision not null default 1.0,
    constraint app_kg_node_chunk_pk primary key (node_id, chunk_id)
);

create index app_kg_node_chunk_chunk_idx on app_kg_node_chunk (chunk_id);

create table app_memory_entity_link (
    organization_id varchar(64) not null,
    memory_id varchar(64) not null references app_rag_memory(id) on delete cascade,
    node_id   varchar(64) not null references app_kg_node(id) on delete cascade,
    weight    double precision not null default 1.0,
    constraint app_memory_entity_link_pk primary key (memory_id, node_id)
);

create index app_memory_entity_link_node_idx on app_memory_entity_link (node_id);

-- ============================================================================
-- 五、安全事件响应闭环（原 V53，incident 包存活）
-- ============================================================================

create table app_incident (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    title varchar(255) not null,
    severity varchar(16) not null,
    category varchar(64) not null,
    source_ip varchar(64),
    target_node_id varchar(128),
    data_mode varchar(16) not null,
    status varchar(32) not null,
    dedupe_key varchar(255) not null,
    source_alert_id bigint,
    task_id varchar(64),
    runbook_id varchar(64),
    occurrence_count integer not null,
    summary varchar(1024),
    evidence_json text,
    detected_at timestamp not null,
    acknowledged_at timestamp,
    contained_at timestamp,
    resolved_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index app_incident_org_status_idx on app_incident (organization_id, status, detected_at desc);
create index app_incident_dedupe_idx on app_incident (organization_id, dedupe_key, status);

create table app_incident_runbook (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    name varchar(128) not null,
    version varchar(32) not null,
    category varchar(64) not null,
    summary varchar(512),
    steps_json text not null,
    created_at timestamp not null
);

insert into app_incident_runbook (id, organization_id, name, version, category, summary, steps_json, created_at) values
('rb-auth-anomaly-100', 'org-alpha', '认证异常受控响应', '1.0.0', 'AUTH_ANOMALY',
 '只读取证 → 证据摘要 → 官方 SOP → R3 临时阻断（双人审批）→ 只读验证 → 审计与 Memory → 回滚解除',
 '[' ||
 '{"step":"triage","tool":"kylin.diagnosis.triage","risk":"R0","desc":"采集主机只读诊断快照，建立事件基线"},' ||
 '{"step":"auth-journal","tool":"kylin.log.journal_query","risk":"R0","desc":"读取认证服务近期日志，提取异常登录证据"},' ||
 '{"step":"recent-logins","tool":"kylin.user.last_logins","risk":"R0","desc":"读取最近登录记录，确认可疑账户与来源"},' ||
 '{"step":"connections","tool":"kylin.net.connections","risk":"R0","desc":"列出活动连接，定位可疑来源"},' ||
 '{"step":"audit-posture","tool":"kylin.security.auditd_status","risk":"R0","desc":"核对 auditd 审计链路，确保证据可信"},' ||
 '{"step":"block-ip","tool":"kylin.firewall.block_ip","risk":"R3","approval":true,"desc":"证据齐备并经双人审批后临时阻断来源"},' ||
 '{"step":"verify-block","tool":"kylin.firewall.rules","risk":"R0","desc":"只读校验封禁规则已生效"},' ||
 '{"step":"recover","tool":"kylin.firewall.unblock_ip","risk":"R3","approval":true,"desc":"事件解除后经回滚路径撤销临时阻断"}' ||
 ']', CURRENT_TIMESTAMP);

insert into app_incident (id, organization_id, title, severity, category, source_ip, target_node_id,
    data_mode, status, dedupe_key, occurrence_count, summary, evidence_json, runbook_id,
    detected_at, created_at, updated_at) values
('inc-demo-auth-001', 'org-alpha', 'sshd 认证异常：来自 203.0.113.66 的高频失败登录', 'HIGH', 'AUTH_ANOMALY',
 '203.0.113.66', 'node-kylin-01', 'SYNTHETIC', 'NEW', 'AUTH_ANOMALY|203.0.113.66|node-kylin-01', 1,
 '5 分钟内 sshd 记录到 42 次来自 203.0.113.66 的认证失败，疑似口令爆破。',
 '{"failedAttempts":42,"windowSeconds":300,"targetUser":"root","source":"synthetic-journal"}',
 'rb-auth-anomaly-100', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

insert into app_incident (id, organization_id, title, severity, category, source_ip, target_node_id,
    data_mode, status, dedupe_key, occurrence_count, summary, evidence_json, runbook_id,
    detected_at, acknowledged_at, contained_at, resolved_at, created_at, updated_at) values
('inc-demo-auth-000', 'org-alpha', 'sshd 认证异常：来自 198.51.100.7 的爆破（已处置）', 'MEDIUM', 'AUTH_ANOMALY',
 '198.51.100.7', 'node-kylin-01', 'SYNTHETIC', 'RESOLVED', 'AUTH_ANOMALY|198.51.100.7|node-kylin-01', 1,
 '已完成取证—临时阻断—验证—解除的完整闭环，作为时效基线样本。',
 '{"failedAttempts":30,"windowSeconds":300,"source":"synthetic-journal"}',
 'rb-auth-anomaly-100',
 TIMESTAMP '2026-08-08 09:00:00', TIMESTAMP '2026-08-08 09:02:00',
 TIMESTAMP '2026-08-08 09:06:00', TIMESTAMP '2026-08-08 09:20:00',
 TIMESTAMP '2026-08-08 09:00:00', TIMESTAMP '2026-08-08 09:20:00');

-- ============================================================================
-- 六、专家注册表与知识域（原 V54，registry 包存活）
-- ============================================================================

create table app_knowledge_domain (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    code varchar(64) not null,
    name varchar(128) not null,
    description varchar(512),
    created_at timestamp not null,
    unique (organization_id, code)
);

create table app_agent_registry (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    agent_key varchar(64) not null,
    name varchar(128) not null,
    role varchar(64) not null,
    description varchar(512),
    knowledge_domains varchar(512),
    allowed_tools text not null,
    max_risk varchar(8) not null,
    budget_tokens bigint not null,
    enabled boolean not null,
    version varchar(32) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (organization_id, agent_key)
);

create index app_agent_registry_org_idx on app_agent_registry (organization_id, enabled);

insert into app_knowledge_domain (id, organization_id, code, name, description, created_at) values
('kd-kylin-os', 'org-alpha', 'kylin-os', '麒麟操作系统', '麒麟/openKylin 版本、内核、服务与运维知识', CURRENT_TIMESTAMP),
('kd-kysec', 'org-alpha', 'kysec-security', 'KYSEC 安全', 'KYSEC/SELinux/auditd 与本地安全姿态', CURRENT_TIMESTAMP),
('kd-firewall', 'org-alpha', 'network-firewall', '网络与防火墙', 'firewalld、连接与端口、访问控制', CURRENT_TIMESTAMP),
('kd-storage', 'org-alpha', 'storage-ops', '存储与清理', '磁盘容量、大文件、可回滚清理', CURRENT_TIMESTAMP),
('kd-incident', 'org-alpha', 'incident-response', '安全事件响应', '取证、临时阻断、验证与恢复 Runbook', CURRENT_TIMESTAMP);

insert into app_agent_registry (id, organization_id, agent_key, name, role, description,
    knowledge_domains, allowed_tools, max_risk, budget_tokens, enabled, version, created_at, updated_at) values
('ag-sense', 'org-alpha', 'sense-collector', '态势采集专家', 'sense',
 '只读采集主机、日志、连接与登录，建立事件基线', 'kylin-os,network-firewall',
 '["kylin.diagnosis.triage","kylin.log.journal_query","kylin.net.connections","kylin.net.listening_ports","kylin.user.last_logins","kylin.process.list"]',
 'R0', 40000, true, '1.0.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ag-knowledge', 'org-alpha', 'knowledge-retriever', '知识检索专家', 'knowledge',
 '基于 RAG 检索官方文档/SOP，不直接操作主机', 'kylin-os,kysec-security,network-firewall,storage-ops,incident-response',
 '[]', 'R0', 40000, true, '1.0.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ag-safety', 'org-alpha', 'safety-guard', '安全护栏专家', 'safety',
 '核对安全姿态与策略，参与风险判定，不执行写操作', 'kysec-security',
 '["kylin.security.kysec_status","kylin.security.auditd_status","kylin.security.selinux_status","kylin.security.security_switch_status"]',
 'R0', 30000, true, '1.0.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ag-incident', 'org-alpha', 'incident-responder', '事件处置专家', 'executor',
 '安全事件受控处置：取证后临时阻断来源并只读验证（R3，需审批）', 'incident-response,network-firewall',
 '["kylin.log.journal_query","kylin.net.connections","kylin.user.last_logins","kylin.firewall.block_ip","kylin.firewall.unblock_ip","kylin.firewall.rules"]',
 'R3', 60000, true, '1.0.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ag-cleanup', 'org-alpha', 'cleanup-operator', '存储清理专家', 'executor',
 '可回滚的磁盘清理：只读预览→审批→执行→验证→回滚（R3，需审批）', 'storage-ops',
 '["kylin.disk.usage","kylin.disk.large_files","kylin.cleanup.plan","kylin.cleanup.execute","kylin.cleanup.rollback","kylin.result.verify"]',
 'R3', 60000, true, '1.0.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ag-verifier', 'org-alpha', 'result-verifier', '结果验证专家', 'verifier',
 '只读验证处置结果、封禁规则与服务状态', 'incident-response,storage-ops',
 '["kylin.result.verify","kylin.firewall.rules","kylin.service.status"]',
 'R0', 30000, true, '1.0.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ag-memory', 'org-alpha', 'memory-curator', '记忆沉淀专家', 'memory',
 '将经验证结论纳入 Memory 候选并整理/召回，不操作主机', 'incident-response,kylin-os',
 '[]', 'R0', 30000, true, '1.0.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 事件响应与注册表权限种子（原 V53/V54）。
insert into app_permission (code, name, module, description) values
('incident:read', '查看安全事件', 'incident', '查看安全事件响应闭环与时效指标'),
('incident:respond', '处置安全事件', 'incident', '发起事件研判与受控处置（复用 SafeOps 审批）'),
('registry:read', '查看专家注册表', 'registry', '查看专家 Agent 注册表与知识域'),
('registry:write', '维护专家注册表', 'registry', '创建/更新/启停专家 Agent 与知识域');

insert into app_role_permission (role_code, permission_code) values
('platform_admin', 'incident:read'),
('org_admin', 'incident:read'),
('security_operator', 'incident:read'),
('auditor', 'incident:read'),
('viewer', 'incident:read'),
('platform_admin', 'incident:respond'),
('org_admin', 'incident:respond'),
('security_operator', 'incident:respond'),
('platform_admin', 'registry:read'),
('org_admin', 'registry:read'),
('security_operator', 'registry:read'),
('auditor', 'registry:read'),
('platform_admin', 'registry:write'),
('org_admin', 'registry:write');

-- ============================================================================
-- 七、资源级授权 ABAC（原 V50/V52，AbacPolicyEngine 存活）
-- ============================================================================

create table app_resource_policy (
    id varchar(64) primary key,
    organization_id varchar(64) not null references app_organization(id),
    subject_type varchar(16) not null,
    subject_id varchar(128) not null,
    resource_type varchar(32) not null,
    resource_pattern varchar(255) not null,
    action varchar(32) not null,
    effect varchar(8) not null,
    condition_json varchar(1024),
    created_at timestamp not null
);

create index app_resource_policy_lookup_idx
    on app_resource_policy (organization_id, resource_type, subject_type, subject_id);

-- V50 演示策略（org-alpha）：secops 可操作生产主机（风险≤R3），但对生产数据库主机显式拒绝。
insert into app_resource_policy (id, organization_id, subject_type, subject_id, resource_type,
    resource_pattern, action, effect, condition_json, created_at) values
('rp-secops-prod-operate', 'org-alpha', 'ROLE', 'secops', 'HOST', 'prod-*', 'operate', 'ALLOW',
    '{"maxRisk":"R3"}', CURRENT_TIMESTAMP),
('rp-secops-prod-db-deny', 'org-alpha', 'ROLE', 'secops', 'HOST', 'prod-db-*', 'operate', 'DENY',
    null, CURRENT_TIMESTAMP),
('rp-secops-host-read', 'org-alpha', 'ROLE', 'secops', 'HOST', '*', 'read', 'ALLOW',
    null, CURRENT_TIMESTAMP);

-- V52 真实角色码策略：只有 platform_admin / org_admin / security_operator 获得策略，
-- 其余角色无策略 → 默认拒绝（fail-closed）。显式 DENY 压过通配 ALLOW。
insert into app_resource_policy (id, organization_id, subject_type, subject_id, resource_type,
    resource_pattern, action, effect, condition_json, created_at)
select 'rp-v52-' || o.id || '-' || p.subject_id || '-' || p.tag,
       o.id, 'ROLE', p.subject_id, 'HOST', p.resource_pattern, p.action, p.effect, p.condition_json, CURRENT_TIMESTAMP
from (values ('org-alpha'), ('org-beta'), ('org-gamma')) as o(id)
cross join (values
    ('platform_admin', 'padmin-read',    '*',        'read',    'ALLOW', cast(null as varchar(1024))),
    ('platform_admin', 'padmin-operate', '*',        'operate', 'ALLOW', '{"maxRisk":"R5"}'),
    ('org_admin',      'oadmin-read',    '*',        'read',    'ALLOW', cast(null as varchar(1024))),
    ('org_admin',      'oadmin-operate', '*',        'operate', 'ALLOW', '{"maxRisk":"R3"}'),
    ('org_admin',      'oadmin-dbdeny',  'prod-db-*','operate', 'DENY',  cast(null as varchar(1024))),
    ('security_operator', 'secop-read',    '*',        'read',    'ALLOW', cast(null as varchar(1024))),
    ('security_operator', 'secop-operate', '*',        'operate', 'ALLOW', '{"maxRisk":"R3"}'),
    ('security_operator', 'secop-dbdeny',  'prod-db-*','operate', 'DENY',  cast(null as varchar(1024)))
) as p(subject_id, tag, resource_pattern, action, effect, condition_json);

-- ============================================================================
-- 八、Agent Runtime V2（原 V55..V68 合并后的最终形态）
-- ============================================================================

-- 工具包目录（先于引用它的表创建）。
create table tool_package (
    id varchar(64) primary key,
    package_key varchar(128) not null unique,
    title varchar(256) not null,
    enabled boolean not null default true,
    created_at timestamp not null,
    updated_at timestamp not null
);

-- tool_package_version：V55 单一 manifest 形态 + V56_1 写入形态过渡后的最终列集。
-- 旧 manifest 列对新形态行（V57/V58/V65 按 input/output schema 分列插入）可空，
-- status 可空（新形态行不携带 status；check 约束对 NULL 放行）。
create table tool_package_version (
    id varchar(64) primary key,
    package_id varchar(64) references tool_package(id),
    version varchar(64) not null,
    manifest_json text,
    manifest_digest varchar(64),
    schema_digest varchar(64),
    implementation_digest varchar(64),
    signature_algorithm varchar(32) not null,
    signing_key_id varchar(128),
    signature_der text,
    status varchar(16),
    created_at timestamp not null,
    tool_package_id varchar(64),
    tool_id varchar(192),
    input_schema_json text,
    output_schema_json text,
    input_schema_digest varchar(64),
    output_schema_digest varchar(64),
    signature_hex varchar(256),
    constraint tool_package_version_status_ck check (status = 'DRAFT' or status = 'APPROVED' or status = 'REVOKED'),
    unique (package_id, version),
    unique (id, manifest_digest)
);

-- agent_run：去掉旧 assistant 会话链路的 session_id 列及其索引。
-- root_agent_instance_id / source_plan_version_id 的跨表外键在相应表建成后补齐。
create table agent_run (
    id varchar(64) primary key,
    tenant_id varchar(64) not null references app_organization(id),
    parent_run_id varchar(64),
    source_plan_version_id varchar(64),
    human_principal_id varchar(64) not null references app_user_account(id),
    objective text not null,
    execution_mode varchar(16) not null,
    target_scope_json text not null,
    target_scope_digest varchar(64) not null,
    grant_version integer not null default 1,
    grant_snapshot_json text not null,
    effective_tool_catalog_digest varchar(64),
    status varchar(16) not null,
    waiting_reason varchar(16),
    outcome varchar(16),
    input_revision bigint not null default 0,
    budget_json text not null,
    usage_json text not null,
    final_result_json text,
    wakeup_at timestamp,
    wait_activity_at timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    version bigint not null default 0,
    started_at timestamp,
    completed_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    root_agent_instance_id varchar(64),
    constraint agent_run_execution_mode_ck check (execution_mode = 'PLAN' or execution_mode = 'CAUTIOUS' or execution_mode = 'AUTO'),
    constraint agent_run_status_ck check (status = 'QUEUED' or status = 'RUNNING' or status = 'WAITING' or status = 'COMPLETED' or status = 'FAILED' or status = 'CANCELLED'),
    constraint agent_run_waiting_reason_ck check (waiting_reason is null or waiting_reason = 'APPROVAL' or waiting_reason = 'TOOL' or waiting_reason = 'SUB_AGENT' or waiting_reason = 'RETRY_TIME'),
    constraint agent_run_waiting_state_ck check (
        (status = 'WAITING' and waiting_reason is not null)
        or (status <> 'WAITING' and waiting_reason is null)
    ),
    constraint agent_run_outcome_ck check (outcome is null or outcome = 'SUCCEEDED' or outcome = 'PARTIAL' or outcome = 'NOT_ACHIEVED'),
    constraint agent_run_terminal_outcome_ck check (
        ((status = 'COMPLETED' or status = 'FAILED' or status = 'CANCELLED') and outcome is not null)
        or ((status <> 'COMPLETED' and status <> 'FAILED' and status <> 'CANCELLED') and outcome is null)
    ),
    constraint agent_run_revision_ck check (input_revision >= 0),
    constraint agent_run_current_grant_version_ck check (grant_version >= 1),
    constraint agent_run_version_ck check (version >= 0),
    unique (id, tenant_id)
);

alter table agent_run
    add constraint agent_run_parent_fk foreign key (parent_run_id) references agent_run(id);

create index agent_run_lease_idx on agent_run (status, wakeup_at, lease_until, created_at);
create index agent_run_tenant_created_idx on agent_run (tenant_id, created_at desc);

create table agent_run_event (
    id bigint generated by default as identity primary key,
    event_id varchar(64) not null unique,
    run_id varchar(64) not null,
    tenant_id varchar(64) not null,
    sequence_no bigint not null,
    event_type varchar(64) not null,
    actor_type varchar(32) not null,
    actor_id varchar(128),
    payload_json text not null,
    prev_hash varchar(64) not null,
    event_hash varchar(64) not null,
    hash_algorithm varchar(16) not null default 'SM3',
    created_at timestamp not null,
    constraint agent_run_event_sequence_ck check (sequence_no > 0),
    constraint agent_run_event_hash_algorithm_ck check (hash_algorithm = 'SM3'),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    unique (run_id, sequence_no)
);

create index agent_run_event_replay_idx on agent_run_event (run_id, sequence_no);

create table agent_definition (
    id varchar(64) primary key,
    tenant_id varchar(64) not null references app_organization(id),
    definition_key varchar(128) not null,
    display_name varchar(128) not null,
    enabled boolean not null default true,
    created_by varchar(64) not null references app_user_account(id),
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (tenant_id, definition_key),
    unique (id, tenant_id)
);

create table agent_definition_version (
    id varchar(64) primary key,
    definition_id varchar(64) not null,
    tenant_id varchar(64) not null,
    version integer not null,
    instructions text not null,
    allowed_skills_json text not null,
    default_budget_json text not null,
    content_digest varchar(64) not null,
    created_by varchar(64) not null references app_user_account(id),
    created_at timestamp not null,
    constraint agent_definition_version_number_ck check (version >= 1),
    foreign key (definition_id, tenant_id) references agent_definition(id, tenant_id),
    unique (definition_id, version),
    unique (id, tenant_id)
);

create table model_profile (
    id varchar(64) primary key,
    tenant_id varchar(64) not null references app_organization(id),
    llm_config_id varchar(64) not null references app_platform_llm_config(id),
    profile_name varchar(128) not null,
    routing_description text not null,
    cache_policy varchar(32) not null default 'AUTOMATIC_PREFIX',
    priority integer not null default 100,
    enabled boolean not null default true,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint model_profile_cache_policy_ck check (
        cache_policy = 'NONE' or cache_policy = 'AUTOMATIC_PREFIX' or cache_policy = 'EXPLICIT_BREAKPOINT'
    ),
    unique (tenant_id, profile_name)
);

create table agent_instance (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    tenant_id varchar(64) not null,
    parent_agent_instance_id varchar(64),
    definition_version_id varchar(64),
    instance_name varchar(128) not null,
    avatar_seed varchar(128) not null,
    definition_snapshot_json text not null,
    objective text not null,
    depth integer not null,
    status varchar(16) not null,
    waiting_reason varchar(16),
    model_profile_id varchar(64),
    model_config_digest varchar(64),
    reasoning_level varchar(16) not null,
    grant_snapshot_json text not null,
    grant_digest varchar(64) not null,
    budget_json text not null,
    usage_json text not null,
    started_at timestamp,
    completed_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint agent_instance_depth_ck check (depth between 0 and 2),
    constraint agent_instance_status_ck check (status = 'QUEUED' or status = 'RUNNING' or status = 'WAITING' or status = 'COMPLETED' or status = 'FAILED' or status = 'CANCELLED'),
    constraint agent_instance_waiting_reason_ck check (waiting_reason is null or waiting_reason = 'APPROVAL' or waiting_reason = 'TOOL' or waiting_reason = 'SUB_AGENT' or waiting_reason = 'RETRY_TIME'),
    constraint agent_instance_waiting_state_ck check (
        (status = 'WAITING' and waiting_reason is not null)
        or (status <> 'WAITING' and waiting_reason is null)
    ),
    constraint agent_instance_reasoning_level_ck check (reasoning_level = 'OFF' or reasoning_level = 'LOW' or reasoning_level = 'MEDIUM' or reasoning_level = 'HIGH'),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (parent_agent_instance_id) references agent_instance(id),
    foreign key (definition_version_id) references agent_definition_version(id),
    foreign key (model_profile_id) references model_profile(id),
    unique (id, tenant_id),
    unique (id, run_id, tenant_id)
);

create index agent_instance_run_idx on agent_instance (run_id, depth, created_at);

alter table agent_run
    add constraint agent_run_root_instance_fk foreign key (root_agent_instance_id) references agent_instance(id);

create table plan_version (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    tenant_id varchar(64) not null,
    version integer not null,
    content_markdown text not null,
    created_at timestamp not null,
    content_digest varchar(64) not null default '',
    created_by varchar(64) references app_user_account(id),
    constraint plan_version_number_ck check (version >= 1),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    unique (run_id, version),
    unique (id, tenant_id)
);

create index plan_version_run_created_idx on plan_version (run_id, created_at desc);

alter table agent_run
    add constraint agent_run_source_plan_fk foreign key (source_plan_version_id) references plan_version(id);

create table handoff (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    tenant_id varchar(64) not null,
    parent_agent_instance_id varchar(64) not null,
    child_agent_instance_id varchar(64) not null,
    objective text not null,
    scope_json text not null,
    input_artifact_refs_json text not null,
    context_summary text,
    pinned_skills_json text not null,
    grant_snapshot_json text not null,
    grant_digest varchar(64) not null,
    budget_json text not null,
    output_schema_json text not null,
    stop_conditions_json text not null,
    result_json text,
    created_at timestamp not null,
    completed_at timestamp,
    status varchar(16) not null default 'OPEN',
    constraint handoff_status_ck check (
        status = 'OPEN' or status = 'COMPLETED' or status = 'FAILED' or status = 'CANCELLED'
    ),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (parent_agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id),
    foreign key (child_agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id),
    unique (child_agent_instance_id)
);

create table context_frame (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    tenant_id varchar(64) not null,
    agent_instance_id varchar(64) not null,
    input_revision bigint not null,
    stable_prefix_digest varchar(64) not null,
    dynamic_suffix_digest varchar(64) not null,
    context_digest varchar(64) not null,
    segment_manifest_json text not null,
    provenance_json text not null,
    token_estimate bigint not null,
    created_at timestamp not null,
    frame_kind varchar(16) not null default 'COMPILED',
    message_role varchar(16),
    sequence_no bigint,
    compressed boolean not null default false,
    constraint context_frame_revision_ck check (input_revision >= 0),
    constraint context_frame_token_estimate_ck check (token_estimate >= 0),
    constraint context_frame_kind_ck
        check (frame_kind = 'COMPILED' or frame_kind = 'MESSAGE' or frame_kind = 'SUMMARY'),
    constraint context_frame_sequence_ck
        check (sequence_no is null or sequence_no >= 0),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id),
    unique (id, tenant_id)
);

create index context_frame_run_kind_idx on context_frame (run_id, frame_kind, sequence_no);

create table agent_invocation (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    tenant_id varchar(64) not null,
    agent_instance_id varchar(64) not null,
    context_frame_id varchar(64) not null,
    input_revision bigint not null,
    provider varchar(64) not null,
    model varchar(128) not null,
    model_config_digest varchar(64) not null,
    reasoning_level varchar(16) not null,
    status varchar(16) not null,
    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    cache_read_tokens bigint not null default 0,
    cache_write_tokens bigint not null default 0,
    cost_microunits bigint not null default 0,
    ttft_millis bigint,
    latency_millis bigint,
    retry_count integer not null default 0,
    error_code varchar(64),
    error_message text,
    started_at timestamp,
    completed_at timestamp,
    created_at timestamp not null,
    constraint agent_invocation_revision_ck check (input_revision >= 0),
    constraint agent_invocation_reasoning_ck check (reasoning_level = 'OFF' or reasoning_level = 'LOW' or reasoning_level = 'MEDIUM' or reasoning_level = 'HIGH'),
    constraint agent_invocation_status_ck check (status = 'QUEUED' or status = 'RUNNING' or status = 'SUCCEEDED' or status = 'FAILED' or status = 'CANCELLED' or status = 'STALE'),
    constraint agent_invocation_usage_ck check (
        input_tokens >= 0 and output_tokens >= 0 and cache_read_tokens >= 0
        and cache_write_tokens >= 0 and cost_microunits >= 0 and retry_count >= 0
    ),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id),
    foreign key (context_frame_id, tenant_id) references context_frame(id, tenant_id),
    unique (id, tenant_id)
);

create index agent_invocation_agent_idx on agent_invocation (agent_instance_id, created_at);

create table tool_invocation (
    id varchar(64) primary key,
    run_id varchar(64) not null,
    tenant_id varchar(64) not null,
    agent_instance_id varchar(64) not null,
    agent_invocation_id varchar(64),
    handoff_id varchar(64),
    tool_package_version_id varchar(64) not null references tool_package_version(id),
    tool_id varchar(192) not null,
    tool_version varchar(64) not null,
    executor varchar(16) not null,
    target_node_id varchar(128),
    arguments_json text not null,
    arguments_digest varchar(64) not null,
    input_schema_digest varchar(64) not null,
    output_schema_digest varchar(64) not null,
    grant_digest varchar(64) not null,
    risk_code varchar(8) not null,
    read_only boolean not null,
    always_require_approval boolean not null,
    idempotency_type varchar(16) not null,
    max_attempts integer not null default 2,
    status varchar(16) not null,
    input_revision bigint not null,
    rejection_code varchar(64),
    error_code varchar(64),
    result_json text,
    result_artifact_id varchar(64),
    queued_at timestamp not null,
    started_at timestamp,
    completed_at timestamp,
    direct_display boolean not null default false,
    constraint tool_invocation_executor_ck check (executor = 'RUNTIME' or executor = 'WORKSPACE' or executor = 'NODE' or executor = 'CONNECTOR'),
    constraint tool_invocation_risk_ck check (risk_code = 'R0' or risk_code = 'R1' or risk_code = 'R2' or risk_code = 'R3' or risk_code = 'R4' or risk_code = 'R5'),
    constraint tool_invocation_idempotency_ck check (idempotency_type = 'IDEMPOTENT' or idempotency_type = 'AT_MOST_ONCE' or idempotency_type = 'NON_RETRYABLE'),
    constraint tool_invocation_status_ck check (status = 'QUEUED' or status = 'RUNNING' or status = 'SUCCEEDED' or status = 'FAILED' or status = 'REJECTED' or status = 'CANCELLED' or status = 'UNKNOWN'),
    constraint tool_invocation_attempts_ck check (max_attempts between 1 and 2),
    constraint tool_invocation_revision_ck check (input_revision >= 0),
    constraint tool_invocation_node_target_ck check (executor <> 'NODE' or target_node_id is not null),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id),
    foreign key (agent_invocation_id, tenant_id) references agent_invocation(id, tenant_id),
    foreign key (handoff_id) references handoff(id),
    unique (id, tenant_id)
);

create index tool_invocation_run_status_idx on tool_invocation (run_id, status, queued_at);
create index tool_invocation_node_status_idx on tool_invocation (tenant_id, target_node_id, status);

create table execution_attempt (
    id varchar(64) primary key,
    tool_invocation_id varchar(64) not null,
    tenant_id varchar(64) not null,
    attempt_no integer not null,
    status varchar(16) not null,
    node_id varchar(128),
    lock_version bigint,
    approval_token_key_id varchar(128),
    approval_token_expires_at timestamp,
    request_digest varchar(64) not null,
    protocol_sequence bigint,
    ack_received_at timestamp,
    started_at timestamp,
    completed_at timestamp,
    error_code varchar(64),
    error_message text,
    retryable boolean not null default false,
    result_json text,
    created_at timestamp not null,
    constraint execution_attempt_number_ck check (attempt_no between 1 and 2),
    constraint execution_attempt_status_ck check (status = 'QUEUED' or status = 'SENT' or status = 'ACK_RECEIVED' or status = 'RUNNING' or status = 'SUCCEEDED' or status = 'FAILED' or status = 'REJECTED' or status = 'CANCELLED' or status = 'UNKNOWN'),
    constraint execution_attempt_lock_version_ck check (lock_version is null or lock_version > 0),
    foreign key (tool_invocation_id, tenant_id) references tool_invocation(id, tenant_id) on delete cascade,
    unique (tool_invocation_id, attempt_no),
    unique (id, tenant_id)
);

create table approval (
    id varchar(64) primary key,
    tool_invocation_id varchar(64) not null,
    tenant_id varchar(64) not null,
    batch_id varchar(64),
    requested_from_user_id varchar(64) references app_user_account(id),
    decided_by_user_id varchar(64) references app_user_account(id),
    decision varchar(16) not null,
    node_id varchar(128) not null,
    tool_id varchar(192) not null,
    tool_version varchar(64) not null,
    arguments_json text not null,
    arguments_digest varchar(64) not null,
    risk_ceiling varchar(8) not null,
    execution_mode varchar(16) not null,
    run_id varchar(64) not null,
    agent_instance_id varchar(64) not null,
    agent_invocation_id varchar(64),
    decided_at timestamp,
    revoked_at timestamp,
    expires_at timestamp,
    created_at timestamp not null,
    constraint approval_decision_ck check (decision = 'PENDING' or decision = 'APPROVED' or decision = 'REJECTED' or decision = 'REVOKED' or decision = 'EXPIRED'),
    constraint approval_risk_ck check (risk_ceiling = 'R0' or risk_ceiling = 'R1' or risk_ceiling = 'R2' or risk_ceiling = 'R3' or risk_ceiling = 'R4'),
    constraint approval_execution_mode_ck check (execution_mode = 'CAUTIOUS' or execution_mode = 'AUTO'),
    foreign key (tool_invocation_id, tenant_id) references tool_invocation(id, tenant_id) on delete cascade,
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id),
    foreign key (agent_invocation_id, tenant_id) references agent_invocation(id, tenant_id)
);

create index approval_pending_idx on approval (tenant_id, decision, created_at);
create index approval_batch_idx on approval (batch_id);

create table artifact (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    run_id varchar(64) not null,
    agent_instance_id varchar(64) not null,
    source_type varchar(32) not null,
    source_id varchar(64),
    artifact_type varchar(64) not null,
    file_name varchar(512),
    content_type varchar(256) not null,
    storage_key varchar(1024) not null,
    size_bytes bigint not null,
    sm3_digest varchar(64) not null,
    version integer not null default 1,
    retention_class varchar(16) not null,
    scan_status varchar(16) not null,
    published_at timestamp not null,
    expires_at timestamp,
    deleted_at timestamp,
    constraint artifact_source_type_ck check (source_type = 'USER' or source_type = 'AGENT' or source_type = 'TOOL' or source_type = 'WORKSPACE' or source_type = 'SYSTEM'),
    constraint artifact_size_ck check (size_bytes >= 0),
    constraint artifact_version_ck check (version >= 1),
    constraint artifact_retention_ck check (retention_class = 'TEMPORARY' or retention_class = 'BUSINESS' or retention_class = 'AUDIT'),
    constraint artifact_scan_status_ck check (scan_status = 'PENDING' or scan_status = 'CLEAN' or scan_status = 'REJECTED' or scan_status = 'FAILED'),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id),
    unique (id, tenant_id)
);

create index artifact_tenant_digest_idx on artifact (tenant_id, sm3_digest);

alter table tool_invocation
    add constraint tool_invocation_result_artifact_fk foreign key (result_artifact_id) references artifact(id);

create table agent_runtime_node_session (
    tenant_id varchar(64) not null references app_organization(id),
    node_id varchar(128) not null,
    protocol_version integer not null,
    executor_version varchar(64),
    tool_catalog_digest varchar(64),
    capabilities_json text not null,
    last_control_sequence bigint not null default 0,
    last_node_sequence bigint not null default 0,
    connected boolean not null default false,
    connected_at timestamp,
    last_heartbeat_at timestamp,
    updated_at timestamp not null,
    constraint agent_runtime_node_protocol_ck check (protocol_version = 2),
    constraint agent_runtime_node_sequence_ck check (last_control_sequence >= 0 and last_node_sequence >= 0),
    primary key (tenant_id, node_id)
);

create index agent_runtime_node_connected_idx
    on agent_runtime_node_session (connected, last_heartbeat_at);

create index outbox_agent_run_pending_idx
    on outbox_event (aggregate_type, status, created_at);

-- 写路径基础设施：资源锁与节点锁版本（原 V57；过期部分索引见 db/postgresql/V57_1）。
create table resource_lock (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    resource_key varchar(512) not null,
    lock_mode varchar(16) not null,
    held_by_run_id varchar(64),
    held_by_attempt_id varchar(64),
    lock_version bigint not null default 1,
    acquired_at timestamp not null,
    expires_at timestamp not null,
    constraint resource_lock_mode_ck check (lock_mode = 'READ_SHARED' or lock_mode = 'WRITE_EXCLUSIVE'),
    constraint resource_lock_expiry_ck check (expires_at > acquired_at),
    unique (tenant_id, resource_key)
);

create index resource_lock_tenant_run_idx on resource_lock (tenant_id, held_by_run_id);

create table agent_runtime_node_lock_version (
    tenant_id varchar(64) not null,
    node_id varchar(128) not null,
    lock_version bigint not null default 0,
    updated_at timestamp not null,
    primary key (tenant_id, node_id)
);

-- Agent artifact 流式存储（原 V59）。
-- 不加 status check 约束：H2 2.4 对 IN 列表 check 约束绑定建表会话，
-- 连接池跨会话写入会误报约束失败；状态取值由 ArtifactRepository 收敛。
create table agent_artifact (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    agent_instance_id varchar(64),
    name varchar(255) not null,
    content_type varchar(128) not null default 'application/octet-stream',
    size_bytes bigint not null default 0,
    sm3_hex varchar(64),
    status varchar(16) not null,
    storage_path varchar(1024),
    created_at timestamp not null,
    published_at timestamp,
    updated_at timestamp not null,
    constraint uk_agent_artifact_tenant_sm3 unique (tenant_id, sm3_hex)
);

create index idx_agent_artifact_tenant_created on agent_artifact (tenant_id, created_at);
create index idx_agent_artifact_status_updated on agent_artifact (status, updated_at);
create index idx_agent_artifact_agent_instance on agent_artifact (agent_instance_id);

-- Skill 包与版本注册（原 V60；不加 IN 列表 check 约束，理由同上）。
create table skill_package (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    skill_id varchar(128) not null,
    name varchar(255) not null,
    description varchar(2000),
    trust_level varchar(32) not null,
    status varchar(16) not null,
    created_at timestamp not null,
    constraint uk_skill_package_tenant_skill unique (tenant_id, skill_id)
);

create table skill_version (
    id varchar(64) primary key,
    skill_package_id varchar(64) not null,
    version integer not null,
    frontmatter_json text not null,
    manifest_json text not null,
    content_sm3 char(64) not null,
    signature_algorithm varchar(32),
    signature_hex varchar(256),
    allowed_tools_json text not null,
    required_commands_json text not null,
    created_at timestamp not null,
    constraint uk_skill_version_package_version unique (skill_package_id, version),
    foreign key (skill_package_id) references skill_package(id)
);

create table skill_file (
    id varchar(64) primary key,
    skill_version_id varchar(64) not null,
    relative_path varchar(1024) not null,
    content bytea not null,
    content_sm3 char(64) not null,
    size_bytes bigint not null,
    content_type varchar(128) not null,
    constraint uk_skill_file_version_path unique (skill_version_id, relative_path),
    foreign key (skill_version_id) references skill_version(id)
);

create index idx_skill_package_tenant_status on skill_package (tenant_id, status);
create index idx_skill_file_version on skill_file (skill_version_id);

-- Connector 外呼审计（原 V65）。
create table connector_invocation (
    id               varchar(64)  not null,
    tenant_id        varchar(64)  not null,
    run_id           varchar(64)  not null,
    connector_id     varchar(64)  not null,
    target           varchar(512) not null,
    request_digest   varchar(128),
    response_status  integer,
    duration_ms      bigint,
    created_at       timestamp    not null default now(),
    primary key (id)
);

create index connector_invocation_tenant_time_idx on connector_invocation (tenant_id, created_at);
create index connector_invocation_run_idx on connector_invocation (run_id);
create index connector_invocation_connector_idx on connector_invocation (connector_id, created_at);

-- Checkpoint 与重启恢复 + 多租户额度（原 V66）。
create table agent_run_checkpoint (
    run_id varchar(64) primary key,
    tenant_id varchar(64) not null,
    agent_instance_id varchar(64) not null,
    round_no integer not null default 0,
    last_tool_invocation_id varchar(64),
    checkpoint_json text not null,
    updated_at timestamp not null,
    constraint agent_run_checkpoint_round_ck check (round_no >= 0),
    foreign key (run_id, tenant_id) references agent_run(id, tenant_id) on delete cascade,
    foreign key (agent_instance_id, run_id, tenant_id) references agent_instance(id, run_id, tenant_id)
);

create index agent_run_checkpoint_updated_idx on agent_run_checkpoint (updated_at);

create table tenant_runtime_quota (
    tenant_id varchar(64) primary key references app_organization(id),
    max_concurrent_runs integer not null default 8,
    max_concurrent_subagents integer not null default 4,
    run_wait_timeout_seconds bigint not null default 86400,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint tenant_runtime_quota_runs_ck check (max_concurrent_runs >= 0),
    constraint tenant_runtime_quota_subagents_ck check (max_concurrent_subagents >= 0),
    constraint tenant_runtime_quota_wait_ck check (run_wait_timeout_seconds > 0)
);

-- 执行记忆链（原 V67）：ExecutionRecord → MemoryCandidate → VerifiedFact。
-- 红线：verified_fact 只能由人工确认/二次验证经提升 API 写入。
create table execution_record (
    id varchar(64) primary key,
    tenant_id varchar(64) not null references app_organization(id),
    run_id varchar(64) not null references agent_run(id) on delete cascade,
    agent_instance_id varchar(64),
    tool_id varchar(128) not null,
    outcome_summary text not null,
    success boolean not null,
    created_at timestamp not null
);

create index execution_record_tenant_tool_idx on execution_record (tenant_id, tool_id, created_at desc);
create index execution_record_run_idx on execution_record (run_id, created_at);

create table memory_candidate (
    id varchar(64) primary key,
    tenant_id varchar(64) not null references app_organization(id),
    source_record_id varchar(64) references execution_record(id) on delete set null,
    statement text not null,
    evidence_json text not null,
    status varchar(16) not null default 'PENDING',
    created_at timestamp not null,
    constraint memory_candidate_status_ck
        check (status = 'PENDING' or status = 'PROMOTED' or status = 'REJECTED')
);

create index memory_candidate_tenant_status_idx on memory_candidate (tenant_id, status, created_at desc);

create table verified_fact (
    id varchar(64) primary key,
    tenant_id varchar(64) not null references app_organization(id),
    statement text not null,
    verified_by varchar(64) not null,
    verification_digest varchar(64) not null,
    created_at timestamp not null,
    constraint verified_fact_verified_by_ck check (verified_by <> '')
);

create index verified_fact_tenant_idx on verified_fact (tenant_id, created_at desc);

-- ============================================================================
-- 九、工具包目录种子（原 V56/V57/V58/V63/V65 的最终形态）
-- ============================================================================

insert into tool_package (id, package_key, title, enabled, created_at, updated_at)
values ('toolpkg-core-node', 'kylin.core.node', 'Kylin Core Node Tools', true, current_timestamp, current_timestamp);

-- V56：只读工具 kylin.service.status（单一 manifest 形态，APPROVED）。
insert into tool_package_version (
    id, package_id, version, manifest_json, manifest_digest, schema_digest,
    implementation_digest, signature_algorithm, signing_key_id, signature_der, status, created_at
) values (
    'toolpkgver-service-status-v1', 'toolpkg-core-node', '1.0.0',
    '{"packageId":"kylin.core.node","version":"1.0.0","toolId":"kylin.service.status","readOnly":true,"directDisplay":true}',
    '37a500279d431d8c98da56d8de86ce67acb5ff4dde852260086e6293d5cdba80',
    '3346f6fc951d9f8960552380493f3219306f7d40e06a2140b62cad77a7de2e99',
    'f3898a5871a647c9f41962d89e2c2a3949eb96fb188af863c2a8560fa729e50a',
    'SM2_WITH_SM3', 'catalog-test-1',
    'MEYCIQDWcQg3EHJJmgWcX2JnVKCPeH1OyW3m8/pV5GWaE3gxSAIhALziAVRPCNOLHWqZjNfbXyJR7/lhWNCl8gvvlUYH4+os',
    'APPROVED', current_timestamp
);

-- V57：写工具（input/output schema 分列形态）。schema digest/签名为 'pending' 占位：
-- 目录签名为离线 SM2 管线，后端运行期无对应签名密钥；消费方按未签名 fail-closed 对待。

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-service-start-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.service.start',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string","minLength":1,"maxLength":128}},"required":["service"]}',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string"},"previousState":{"type":"string"},"newState":{"type":"string"},"message":{"type":"string"}},"required":["service","previousState","newState","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-service-stop-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.service.stop',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string","minLength":1,"maxLength":128}},"required":["service"]}',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string"},"previousState":{"type":"string"},"newState":{"type":"string"},"message":{"type":"string"}},"required":["service","previousState","newState","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-service-restart-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.service.restart',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string","minLength":1,"maxLength":128}},"required":["service"]}',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string"},"previousState":{"type":"string"},"newState":{"type":"string"},"message":{"type":"string"}},"required":["service","previousState","newState","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-service-reload-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.service.reload',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string","minLength":1,"maxLength":128}},"required":["service"]}',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string"},"previousState":{"type":"string"},"newState":{"type":"string"},"message":{"type":"string"}},"required":["service","previousState","newState","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-service-enable-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.service.enable',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string","minLength":1,"maxLength":128}},"required":["service"]}',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string"},"previousState":{"type":"string"},"newState":{"type":"string"},"message":{"type":"string"}},"required":["service","previousState","newState","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-service-disable-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.service.disable',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string","minLength":1,"maxLength":128}},"required":["service"]}',
    '{"type":"object","additionalProperties":false,"properties":{"service":{"type":"string"},"previousState":{"type":"string"},"newState":{"type":"string"},"message":{"type":"string"}},"required":["service","previousState","newState","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-firewall-block-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.firewall.block_ip',
    '{"type":"object","additionalProperties":false,"properties":{"ip":{"type":"string"},"scope":{"type":"string","enum":["ingress"]},"durationMinutes":{"type":"integer","minimum":1,"maximum":1440}},"required":["ip","scope","durationMinutes"]}',
    '{"type":"object","additionalProperties":false,"properties":{"ip":{"type":"string"},"scope":{"type":"string"},"durationMinutes":{"type":"integer"},"rule":{"type":"string"},"message":{"type":"string"}},"required":["ip","scope","durationMinutes","rule","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-firewall-unblock-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.firewall.unblock_ip',
    '{"type":"object","additionalProperties":false,"properties":{"ip":{"type":"string"},"scope":{"type":"string","enum":["ingress"]}},"required":["ip","scope"]}',
    '{"type":"object","additionalProperties":false,"properties":{"ip":{"type":"string"},"scope":{"type":"string"},"rule":{"type":"string"},"message":{"type":"string"}},"required":["ip","scope","rule","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-cleanup-execute-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.cleanup.execute',
    '{"type":"object","additionalProperties":false,"properties":{"targetDir":{"type":"string"},"olderThanDays":{"type":"integer","minimum":1,"maximum":365},"dryRun":{"type":"boolean"}},"required":["targetDir","olderThanDays"]}',
    '{"type":"object","additionalProperties":false,"properties":{"targetDir":{"type":"string"},"removedFiles":{"type":"integer"},"freedBytes":{"type":"integer"},"rollbackId":{"type":"string"},"message":{"type":"string"}},"required":["targetDir","removedFiles","freedBytes","rollbackId","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-cleanup-rollback-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.cleanup.rollback',
    '{"type":"object","additionalProperties":false,"properties":{"rollbackId":{"type":"string"}},"required":["rollbackId"]}',
    '{"type":"object","additionalProperties":false,"properties":{"rollbackId":{"type":"string"},"restoredFiles":{"type":"integer"},"message":{"type":"string"}},"required":["rollbackId","restoredFiles","message"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);

-- V58 + V63：6 个 workspace 工具；schema digest 已按 V63 回填为最终值
-- （SM3(RFC 8785 规范化(schema JSON))，与 Workspace*Tool 常量一致，
-- 由 WorkspaceToolDigestConsistencyTest 锁定）；signature_hex 保持 'pending'。

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-workspace-bash-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.workspace.bash',
    '{"type":"object","additionalProperties":false,"properties":{"script":{"type":"string","minLength":1,"maxLength":100000}},"required":["script"]}',
    '{"type":"object","additionalProperties":false,"properties":{"output":{"type":"string"},"exitCode":{"type":"integer"}},"required":["output","exitCode"]}',
    '82694e584c372981a6b793eb41da67c6373d7bc3a3a2a07e0105210c96345aa7',
    '597a77b56fd9b89efa64ae8924addfe66195994f758c938a62eb98c53088b7d2',
    'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-workspace-python-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.workspace.python',
    '{"type":"object","additionalProperties":false,"properties":{"script":{"type":"string","minLength":1,"maxLength":100000}},"required":["script"]}',
    '{"type":"object","additionalProperties":false,"properties":{"output":{"type":"string"},"exitCode":{"type":"integer"}},"required":["output","exitCode"]}',
    '82694e584c372981a6b793eb41da67c6373d7bc3a3a2a07e0105210c96345aa7',
    '597a77b56fd9b89efa64ae8924addfe66195994f758c938a62eb98c53088b7d2',
    'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-workspace-read-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.workspace.read',
    '{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string","minLength":1,"maxLength":1024}},"required":["path"]}',
    '{"type":"object","additionalProperties":false,"properties":{"content":{"type":"string"}},"required":["content"]}',
    '4c108b380ea95f9265d4a6daf3e2eadcea8ecba4609bc48a4819c6c7f4a99b8e',
    '860b5669f121eb70e811f15f6809dfec981cf26e7e0188077850e2f3a448ae6c',
    'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-workspace-write-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.workspace.write',
    '{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string","minLength":1,"maxLength":1024},"content":{"type":"string"}},"required":["path","content"]}',
    '{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string"},"sizeBytes":{"type":"integer"}},"required":["path","sizeBytes"]}',
    '11488d49a0e1ecd3bae9f4aab3a13d9628444ff6961d9dc3039113154be382a2',
    '827fce61a08b845f0c6d15455aa369d09fec4570d7fbcd0355d8999353825a05',
    'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-workspace-edit-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.workspace.edit',
    '{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string","minLength":1,"maxLength":1024},"oldText":{"type":"string"},"newText":{"type":"string"}},"required":["path","oldText","newText"]}',
    '{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string"},"replaced":{"type":"integer"}},"required":["path","replaced"]}',
    'a1beb6136b5b1c0cfb685908346b002179cfb98b04f39ba01653155beeaf0383',
    'd6b370631f41d55b611bc249f4d0c5a1687370655a7a91a3fdc8e68869d100e2',
    'SM2_WITH_SM3', 'pending', now()
);

insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-workspace-glob-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.workspace.glob',
    '{"type":"object","additionalProperties":false,"properties":{"pattern":{"type":"string","minLength":1,"maxLength":1024}},"required":["pattern"]}',
    '{"type":"object","additionalProperties":false,"properties":{"files":{"type":"array","items":{"type":"string"}}},"required":["files"]}',
    '295bbcd8d5c022277cf366f00f3c195a458c81d5b280098961fa4f4ad8e3d16a',
    'def19f8e792b286edfe0a0ac1506de0114c15f999946688040c740e188e60489',
    'SM2_WITH_SM3', 'pending', now()
);

-- V65：平台预装连接器统一外呼工具（schema digest/签名同 'pending' 口径）。
insert into tool_package_version (
    id, tool_package_id, version, tool_id, input_schema_json, output_schema_json,
    input_schema_digest, output_schema_digest, signature_algorithm, signature_hex, created_at
) values (
    'toolpkgver-connector-invoke-v1', 'toolpkg-kylin-core-node', '1.0.0', 'kylin.connector.invoke',
    '{"type":"object","additionalProperties":false,"properties":{"connector_id":{"type":"string","minLength":1,"maxLength":64},"path":{"type":"string","maxLength":2048},"query":{"type":"object"},"body":{"type":"object"}},"required":["connector_id"]}',
    '{"type":"object","properties":{"connectorId":{"type":"string"},"target":{"type":"string"},"status":{"type":"integer"},"body":{"type":"string"},"truncated":{"type":"boolean"},"durationMs":{"type":"integer"}},"required":["connectorId","status"]}',
    'pending', 'pending', 'SM2_WITH_SM3', 'pending', now()
);
