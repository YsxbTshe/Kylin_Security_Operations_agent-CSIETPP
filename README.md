# 麒麟安全智能运维 Agent

本大创项目面向银河麒麟环境的日常安全运维，重点研究如何把自然语言提出的诊断与处置需求转化为可复核的系统操作。系统以"感知—推理决策—安全护栏"三层架构，把自然语言运维意图转化为**经策略校验、人工审批、端侧最终拒绝权**约束的受控工具调用，并对全链路做 SM3 哈希链审计与分布式追踪。

> 功能范围、测试条件和部署步骤见[项目文档](docs/README.md)。
> 本 README 与全部对外文档受 `scripts/verify-doc-consistency.py` 门禁约束：引用的代码文件必须存在，已删除能力的名词不得复活。

## 核心能力

- **OS 环境深度感知 + MCP 工具体系**：签名工具清单 `tool-packages/core-node-tools/manifest.json` 注册 **46 个工具**（32 只读 / 14 写，其中 10 个 `alwaysRequireApproval`），覆盖进程、网络、日志、磁盘、服务、防火墙、软件包、账号、麒麟安全开关等采集与受控变更，以及隔离沙箱内的 workspace 脚本能力。节点侧 MCP Server（`server.go`，protocolVersion `2024-11-05`）实现 `initialize` / `tools/list` / `tools/call`。
- **安全意图校验 + 最小权限执行**：PolicyEngine 风险分级 R0–R5（按运维操作分为只读、可逆、不可逆和关键四类）+ Prompt 注入硬规则（先于可配置 DSL，不可被绕过）+ `IntentAlignmentGuard` 意图一致性护栏 + 资源级 ABAC（工具维度与主机维度串联，default-deny）；审批令牌为 **SM2-with-SM3 非对称签名**，后端私钥签发、端侧公钥验真、TTL 5 分钟。
- **特权分离**：写操作下沉 root 特权助手 **privhelper**（unix socket + `SO_PEERCRED` + 固定操作映射表 + 自解析参数 + 独立验令牌 + root 属主 `allow.conf`）；Node Executor 以非 root 运行，宿主机侧无通用 shell（脚本仅在隔离沙箱内运行），路径 / argv 白名单 + systemd 沙箱。见[软件功能设计文档](docs/06-提交文档/02-软件功能设计文档.md)。
- **抗注入 + 推理链路溯源**：审计事件溯源 + SM3 哈希链（`event_hash = SM3(prev_hash ‖ 规范化载荷)`，genesis ZERO_HASH），在线 `GET /api/v2/agent-runtime/runs/{runId}/chain` 可校验链完整或指出断裂点（`AgentRunEventRepository.java`）；全链路 W3C `traceparent` + OpenTelemetry → Tempo → Grafana。
- **受控 Agent 循环**：执行模式 PLAN / CAUTIOUS / AUTO + **DispatchGate 十二步门禁**（见下）+ 五类预算上限（模型调用数 / 工具调用数 / 墙钟 / 写次数 / 连续停滞轮数，`AgentRoundExecutor.java`）+ 证据指纹停滞判据 + 断点续跑（`RunCheckpointService.java`）+ 模型全不可用时降级为确定性只读剧本（`DeterministicFallbackPlaybook.java`）+ RAG 三通道 RRF 检索注入并写入审计。

### DispatchGate 门禁顺序

所有工具调用在下发前经过同一条判定链（`DispatchGate.java`），任一步拒绝即终止，拒绝码写入审计事件：

| # | 判据 | 拒绝码 |
|---|---|---|
| 1 | Run grant 工具白名单 | `GRANT_EXCLUDES_TOOL` |
| 2 | Run grant 只读约束 | `GRANT_READ_ONLY` |
| 3 | `PolicyEngine.evaluate` → `IntentAlignmentGuard.align` | `POLICY_INTENT_MISMATCH` / `POLICY_DENIED` |
| 4 | R5 无条件禁止 | `R5_FORBIDDEN` |
| 5 | PLAN 模式禁写（risk ≥ R2） | `PLAN_MODE_FORBIDS_WRITE` |
| 6 | Run grant 节点白名单 | `GRANT_EXCLUDES_NODE` |
| 7 | ABAC 工具维度（`resourceType=agent-tool`） | `ABAC_DENIED` |
| 8 | ABAC 主机维度（`resourceType=HOST`，写→`operate` / 只读→`read`） | `ABAC_DENIED_HOST` |
| 9 | R3+ Native Guard 姿态新鲜度 / CRITICAL 拒绝 | `NATIVE_GUARD_*` |
| 10 | 审批：`requiresApproval \|\| alwaysRequireApproval`；AUTO 且 risk ≤ 自动上限则自动批准 | 否则 `NEEDS_APPROVAL` |
| 11 | 以上全过 | `ALLOWED` |

节点侧 `safeops.Runner` 与 privhelper 会**独立复判**签名、执行模式、风险、参数摘要与本地策略——控制面的判定不是端侧放行的依据。

## 架构

```text
apps/admin-web        ─┐  HTTPS / SSE
apps/dashboard-screen ─┤────────────────▶  services/backend（Spring Boot 控制面）
                                            AgentRunWorker（租约轮询）
                                            · AgentRoundExecutor（ReAct 循环 + 预算 + 停滞判据）
                                            · DispatchGate（策略 / ABAC / 姿态 / 审批）
                                            · ApprovalService（SM2 令牌签发）
                                            · AgentRunEventRepository（SM3 哈希链）
                                            · NodeMcpGateway → NodeExecutorV2GrpcService
                                                        │  gRPC + mTLS（ControlStream 双向流）
                                                        ▼
                                        agents/node-executor（Go 受控执行端，非 root）
                                            controlstreamv2.Client · safeops.Scheduler（并发 + 资源锁 + 幂等）
                                            · safeops.Runner（本地 guardrail）· MCP Server · executor
                                            · Kylin Native Guard · privops.Client
                                                        │  unix socket + SO_PEERCRED（写操作 + SM2 令牌）
                                                        ▼
                                        cmd/privhelper（root 特权助手，只认固定操作映射表）
```

职责边界：控制面负责自然语言、Sub Agent 招募与委派、Skill、RAG、模型循环、预算与审批；Node Executor 只消费经校验的 `SafeToolCall`，经进程内 MCP `tools/call` 执行白名单工具，并拥有**本地最终拒绝权**；写操作再下沉到 privhelper，执行前独立验 SM2 审批令牌——Agent 进程永不持有特权与审批密钥；后端不直接执行目标主机命令。

## 仓库结构

```text
apps/admin-web/           管理端前端（Vue 3 + Vite，25 个视图）
apps/dashboard-screen/    数据大屏前端（Vue 3 + ECharts）
apps/concept-lab/  交互概念原型（Vite + Three.js，对后端零调用，不参与构建与部署）
services/backend/         Spring Boot 后端（Java 17，REST + SSE + gRPC 控制面）
agents/node-executor/     Go 麒麟节点安全执行器（mTLS ControlStream 客户端 + privhelper）
agents/workspace-runner/  Go 沙箱脚本执行器（当前无部署产物，见下文的运行边界）
proto/v2/                 Node Executor gRPC V2 权威协议（Buf 生成 Java / Go）
tool-packages/            签名 ToolPackageManifest schema、源清单与多端投影
bench/                    对抗电池 / RAG / 记忆基准的用例与结果
deploy/                   发布脚本 + 可观测演示栈（OTel / Tempo / Prometheus / Grafana）
scripts/                  本地验证、文档一致性与安全扫描脚本
docs/                     功能需求 / 技术设计 / 使用说明 / 测试 / 部署
```

## 端口

| 端口 | 用途 |
|---|---|
| 8080 | 后端 REST / SSE / `/actuator/prometheus` |
| 9090 | gRPC ControlStream（mTLS） |
| 9091 | gRPC enroll（bootstrap-only） |
| 19100 | Node Executor `/metrics`（仅本机采集） |
| 3001 / 3200 / 9092 | Grafana / Tempo / Prometheus（演示栈） |
| 4317 / 4318 | OTel Collector OTLP gRPC / HTTP |

## 本地启动

```bash
# 后端（H2 PostgreSQL 兼容模式默认开箱即跑）
cd services/backend && mvn spring-boot:run

# 管理端 / 大屏
cd apps/admin-web && npm install && npm run dev
cd apps/dashboard-screen && npm install && npm run dev

# Node Executor（需 KYLIN_ENROLLMENT_TOKEN；enroll 还必须钉住平台身份——
#   配 KYLIN_ENROLL_CA_FILE 或 KYLIN_ENROLL_CA_SHA256，否则拒绝注册；
#   本机开发可显式 KYLIN_ENROLL_INSECURE_TOFU=true，每次连接会打安全告警）
cd agents/node-executor && go build -o kylin-node-executor ./cmd/node-executor && ./kylin-node-executor
```

部署详见 `docs/06-提交文档/06-软件部署文档.md` 与 `deploy/README.md`。

## 常用验证

- 后端单测：`mvn -B test`（目录 `services/backend`，实测 **442 用例通过 / 0 失败**）
- 后端集成测试：`mvn -B verify` —— pom 已绑定 failsafe，会执行 `src/test/java` 下的 8 个 `*IT.java`（此前 surefire 只收 `*Test`，这些 IT 从不执行）。需外部依赖的 IT（Postgres/pgvector、RAG / 记忆基准）由各自环境变量开关门控，未设置时自动跳过；Benchmark 生成器默认只跑断言不落盘，`-Dbench.regenerate=true` 才重写报告
- 双库校验：`scripts/ci-backend-dual-db.sh`
- 管理端 / 大屏：`npm run build`（各自目录）
- Go 侧：`go test ./...`、`go build ./...`（`agents/node-executor` 与 `agents/workspace-runner`，共 183 个测试函数）
- 对抗电池（完全离线可复现）：`go test ./internal/advbench/`（目录 `agents/node-executor`）
- Agent Runtime 契约：`scripts/verify-agent-contracts.sh`（CI 比较分支时设置 `CONTRACT_BASE_REF`）
- 文档一致性门禁：`python3 scripts/verify-doc-consistency.py`
- 可观测栈：`deploy/observability/scripts/verify-observability.sh`
- 综合门禁 / 安全扫描：`scripts/verify-mvp.ps1`、`scripts/security-scan.ps1`

CI（`.github/workflows/`）：`backend-tests`（H2 单测 + PostgreSQL/pgvector 迁移 IT）、`go-agents`（gofmt + build + test）、`frontend-build`（admin-web / dashboard-screen 构建）、`docs-consistency`（本文与全部对外材料的一致性门禁）。

## 口径与边界（对外表述以此为准）

**审批令牌**：SM2-with-SM3 签名覆盖 proto `ApprovalClaim` 全部 **17 个字段**；端侧 `covers()` 对其中 **13 个身份字段**逐字段复判——风险允许 ≤ 批准上限，其余（租户 / run / agent 实例 / agent 调用 / 工具调用 / 执行尝试 / 节点 / 工具 ID / 工具版本 / 参数 SM3 / 执行模式 / 锁版本）必须精确相等。旧材料把绑定字段数写成个位数，是低报，不再使用。令牌一次性消费由 privhelper 的**持久化消费台账**保证（root-only 追加日志，指纹 fsync 落盘后才执行），**重启后重放仍被拒**；台账不可用时写操作 fail-closed。

**工具数量**：46 是签名 manifest 口径。后端 `PolicyToolCatalog` 为 41 条、两侧共有 40 条（`workspace.*` 6 条仅节点侧；`kylin.result.verify` 后端保留、节点实现已删除）。**不得表述为"两侧完全一致"。** 节点运行时目录是 Go 代码常量，与签名 manifest 的逐字段一致性由 `catalog_manifest_test.go` 在编译期钉死——运行时不加载 manifest，也**不存在运行时插件加载**（已随 commit 96ca8c0 删除）。

**RAG 检索**：生产默认三通道 **RRF 融合**（`RagService.java`：`useVector` / `useKeyword` / `useGraph` 全开，`rrfK=60`，通道权重 1.0 / 1.0 / 0.8，`candidateN=50`），贡献值为 `weight / (rrfK + rank)`。不存在"向量 0.75 + 关键词 0.25"这类加权打分。向量通道为 JVM 内存逐条余弦（`app_rag_document` 的 pgvector 列建了索引但无读写，是死列）；图通道为内存 BFS；Elasticsearch 未启用时关键词通道降级为 JVM 词项重合，但 **ES 已配置却宕机时 `/rag/query` 直接失败，不做通道互备**。LOCAL embedding 是 hash 伪向量，仅用于无模型环境的功能自检。

**MCP 边界**：只实现 `initialize` / `tools/list` / `tools/call`；`initialize` 声明 `capabilities.tools.listChanged=true`，但**未实现主动的变更通知推送**；无 `resources` / `prompts`；无对外传输层，唯一生产调用点是 `runner.go` 的进程内 `server.Handle`。

**模型不可用时的兜底**：全部 ModelProfile 不可用时，执行循环降级为**确定性只读剧本**（`DeterministicFallbackPlaybook.java`：按 objective 关键词匹配四条固定只读工具链）。三条硬约束——只读（剧本内只有只读工具，执行前再按工具目录 `readOnly` 位筛一次，且照常过 DispatchGate）、确定性（关键词 → 固定链，无模型参与）、诚实（匹配不到任何链就按原样失败，不瞎跑无关链）。降级显式可见：写 `MODEL_UNAVAILABLE_FALLBACK` 审计事件，结果带 `degraded=true`，不伪装成模型输出。

**尚未交付 / 从未实现**（不得写进任何对外材料）：观察触发的自动重新规划；节点侧本地审计续链缓存（断网期间未送达的事件以控制面链为准）；模型主备切换；前端 checkpoint UI 与图形化执行瀑布（当前只有 `AgentRunTimelineView.vue`）；WebSocket 实时推送（大屏 30s 轮询、任务详情 2.5s 轮询，Agent Run 事件走 SSE）；多 Agent 与单 Agent 的收益对照实验。

**已知欠账**：`outbox_event` 只写不读（Redis 依赖已从 pom 移除，`application.yml` 中的 Redis 配置为孤儿）；`artifact` 表为死表（活代码用 `agent_artifact`）；`APPROVAL_SIGNING_*` 未配置时后端照常启动，失败延迟到首次签发；`agents/workspace-runner` 无部署产物。

## 文档入口

- 文档总览：`docs/README.md`
- 项目技术文档：`docs/06-提交文档/`
- 版权与第三方许可：[`COPYRIGHT.md`](COPYRIGHT.md)、[`THIRD-PARTY.md`](THIRD-PARTY.md)

## 当前验证范围

当前以一套控制面和一个麒麟受管节点验证端到端流程。明确不做：SM2/TLCP 国密 TLS 栈、容器级沙箱 / 远端 runner fleet、企业 CA/OCSP 对接、Redis Sentinel/Cluster、Tempo/Grafana 高可用、多租户深化、生产级日志 shipper。证书链运行在标准 TLS/X.509 上（mTLS 校验只解析 Subject DN 的 CN/O，不读 SAN）；SM3 用于审计摘要与参数指纹，SM2-with-SM3 用于 V2 审批令牌与清单签名。
