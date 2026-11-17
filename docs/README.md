# 文档总览

> 更新时间：**2026-08-15**。**赛题要求以 `ppt/`（官方赛题解析）为最终依据；当前代码事实与决赛口径见 `08-决赛冲刺/决赛事实基线与证据索引.md`。**
>
> ⚠️ **定稿门禁**：任何对外材料改完后必须跑 `python3 scripts/verify-doc-consistency.py`，退出码非 0 即视为未定稿。它同时校验两件事：① 文档里的 `文件:行号` 证据引用能在版本库命中；② 已删除/从未实现的名词不出现在对外材料里。加这个门禁的原因是 2026-08 的教训——重构移除整套旧执行链路后，被指定为「唯一事实源」的证据索引里约 30% 的锚点指向了不存在的文件。

## 目录结构

| 目录 | 内容 | 状态 |
|---|---|---|
| `ppt/` | 官方赛题解析 PPT 截图（13 张）+ 文字版总结 | **权威依据，勿改** |
| `01-架构设计/` | 最终系统设计方案、MCP 与受控执行协议、gRPC-mTLS 通信、审计溯源与可观测、ADR-001/002/003 | 现行有效；与代码冲突时以代码和 08 的证据索引为准 |
| `02-部署运行/` | 目标部署方案（组件/端口/目录/证书流程） | **节点侧实操段落待重写**（旧提权方式与旧迁移编号已废），见 `08-决赛冲刺/决赛答辩准备.md` §八 |
| `03-项目状态/` | Agent-Runtime 新架构实施进度（2026-08-13） | 现行有效。2026-06-10 的阶段总结与零信任缺陷审计已移入 `99-归档/2026-06-10-阶段状态/` |
| `04-后续计划/` | 后续开发计划（2026-08-12） | 现行有效。2026-06-10 的《重构与删除清单》条目已执行完毕并归档 |
| `05-评估方案/` | 系统 Benchmark 蓝图及 Agent/RAG/Memory 已落地结果 | 评测入口。⚠️ 四套基准**可复现性不同**，只有对抗电池离线可复现；`bench/agent/results/*.json` 已于 2026-08-15 删除（无生成器的手工静态产物），数值以生成器产出的 `Agent*-Benchmark报告.md` 为准 |
| `06-提交文档/` | 比赛提交六件套：需求分析、功能设计、产品说明书、功能测试报告、性能测试报告、部署文档 | 已有数据；**仍需按 08 事实基线消除口径漂移**（工具计数、审批算法、迁移编号、性能数值） |
| `07-学术论文/` | 架构与评估论文材料 | 研究/答辩储备，数字以自动 Benchmark 为准 |
| `08-决赛冲刺/` | **事实与证据索引、答辩准备、演示脚本、实施计划、审查报告** | **当前决赛工作入口与唯一事实源** |
| `99-归档/` | 旧 MVP 文档（2026-06-06）、已完成改造记录（2026-06-10）、**阶段状态（2026-06-10，Phase 7 重构前）** | 仅追溯，不可作为依据 |
| `组会同步/` | 两份组会 PPT 及其生成脚本（python-pptx）。`组会同步.pptx`（2026-06）是**重构前的架构快照**，保留作对照基准；`组会同步-20260815.pptx` 讲重构变化与当前进度 | 内部材料。生成：`.tools/venv/bin/python docs/组会同步/build_slides_20260815.py` |
| `软件杯_…清单.xlsx` | 39 项交付物/26 用例/20 指标/24 页答辩 PPT 规划 | 需求基线有效；**状态列未维护**，进度以 08 为准 |

## 阅读顺序（新成员/评审准备）

1. `ppt/赛题解析.md` — 赛题与评分结构（注意：安全子权重 30/35/35、官方题面四条功能要求等细节以 PPT 原图为准）。
2. **`08-决赛冲刺/决赛事实基线与证据索引.md` — 当前事实、指标边界与对外口径。先看它的 §0「本轮被推翻的旧口径」，那张表列的说法一律不得再讲。**
3. `08-决赛冲刺/决赛答辩准备.md` — 现场话术、演示动线与评委 Q&A 标准答法。
4. `08-决赛冲刺/演示脚本-可复制命令.md` — 可直接粘贴的演示命令。
5. `08-决赛冲刺/决赛冲刺完整实施计划.md` — D-28 到 D-0 的完整候选与执行路线。
6. `01-架构设计/最终系统设计方案.md` — 架构总纲；若与事实基线冲突，以代码和 08 的证据索引为准。
7. `06-提交文档/` — 对外交付材料。

## 统一术语口径

> 本节只列**当前代码里真实存在**的组件名。已随重构移除的旧组件名不在此列，也不得出现在任何对外材料中——完整禁用清单见事实基线 §0。

- **平台侧（控制面）**：Control Plane、`AgentRunWorker`（租约轮询器）、`AgentRoundExecutor`（ReAct 主循环）、`PolicyEngine`、`IntentAlignmentGuard`、`DispatchGate`（十二步下发门禁）、`AbacPolicyEngine` / `ResourceAccessService`（两维 ABAC）、Approval Center（`ApprovalService`）、`AgentRunEventRepository`（SM3 哈希链审计）、`RunCheckpointService`（轮次断点续跑）、`TenantQuotaService`（租户额度）、`AgentRegistryService`（专家注册表 / Agent IAM，已接入下发门禁第 8 步；招募子 Agent 必须声明已注册且启用的专家，故**全部子 Agent 都受能力封套约束**，人直接发起的根 Agent 除外）。
- **节点侧**：Node Executor Runtime、Local Guardrail、MCP Tool Host（`internal/mcp`）、**privhelper**（root 特权助手，写操作唯一通道）、Kylin Native Guard（KYSEC / SELinux / auditd / security-switch 四件套探测）。
- **沙箱侧**：`agents/workspace-runner`（隔离沙箱执行器）。⚠️ **当前零部署产物且镜像构不出**，不进演示动线。
- **协议/任务**：`ApprovalClaim` / `ApprovalToken`（SM2-with-SM3）、`ExecutionMode`（PLAN / CAUTIOUS / AUTO）、AgentRun / AgentRunEvent、ControlCommand、Audit Event。
- **前端视图**（admin-web，25 个视图）：`AgentWorkspaceView`（全屏对话工作台，独立顶层路由）、`AgentRunView` / `AgentRunTimelineView`（任务列表与**文本执行时间线**，含在线验链）、`AgentApprovalView`（审批中心）、`AgentArtifactView`、`DiagnosticsView`（侧边栏「**安全试判**」，护栏判定的唯一可视化入口）、`RegistryView`、`IncidentView`、`TopologyView`。
- **风险等级**：R0–R5，对外映射官方四级"只读（R0/R1）/可逆（R2）/不可逆（R3/R4，需审批）/关键（R5，直接拦截）"。
- **服务与路径**：`kylin-node-executor.service`、`kylin-privhelper.service`、证书目录 `/var/lib/kylin-node-executor/certs`、privhelper 白名单 `/etc/kylin-privhelper/allow.conf`（root 属主）、清理作用域配置 `/etc/kylin-privhelper/cleanup.conf`（root 属主）、默认清理目录 `/var/log/aiops-demo`、`/tmp/aiops-demo-logs`（**白名单语义：默认拒绝、精确匹配**；由 root 持有的 `cleanup.conf` 提供，文件缺失/非 root 属主/内容非法时 fail-closed 回退到这两个默认目录，且任何配置都突破不了硬禁清单）。
- **指标前缀**：`kylin_node_executor_*` / `kylin_backend_*`（旧 `aiops_*` 口径作废）。
- **数据基线**：Flyway `V1__baseline.sql` … `V8`，共 8 个迁移文件 + 2 个 PostgreSQL vendor 文件；62 张表。
- **降级路径**：`SimpleModelRouter`（模型档位按优先级逐个重试）→ `DeterministicFallbackPlaybook`（**确定性只读**剧本，永不写，照常过下发门禁）→ 诚实失败。降级写 `MODEL_UNAVAILABLE_FALLBACK` 审计事件且结果带 `degraded=true`，**不伪装成模型输出**。
- **权限码**：审批决策为 `agent:approve`（V8 补入，不再借用 `incident:respond`）；功能权限只是第一道闸，具体单据仍由 `ApprovalService.decide` 的 ABAC 判定。
- **核心边界**：平台管自然语言 / Sub Agent / RAG / 规划；节点执行器只消费经校验的受控工具调用，经标准 MCP `tools/call` 执行白名单工具，拥有本地最终拒绝权；宿主机侧禁止通用 shell，脚本能力仅存在于隔离沙箱（无网络 / 无宿主 / 只读根）。**MCP 无对外传输层**——协议只在节点执行器进程内提供，第三方客户端连不上，这是有意的安全边界。
- **确定性来源**：**执行模式门控**（PLAN / CAUTIOUS / AUTO）+ 十二步下发门禁 + 人工审批，见 `01-架构设计/计划门控说明.md` 与 ADR-001。**确定性不来自模型，也不来自任何模板兜底**。
