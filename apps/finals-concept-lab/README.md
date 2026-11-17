# 决赛概念实验室

这是一个独立的 Vite + Vue 3 + Three.js 交互 Demo，用于快速验证决赛 UI 重构与新交互方式。它不替代现有 `admin-web` 或 `dashboard-screen`，也不改变现有后端；七个场景均可在本地离线运行。

## 运行

```bash
npm install
npm run dev
```

生产构建：

```bash
npm run build
npm run preview
```

## 七个场景

1. **Task Workspace**：九阶段任务回放、人工职责分离审批、Evidence Inspector、“可信完成五证”和原始 SM3 审计链视图。
2. **3D Topology**：Three.js 程序化机架与服务器、OrbitControls、主机点击选择、状态色、任务脉冲、相机复位，以及 WebGL 失败时自动切换的 2D 同源视图。
3. **Big Screen**：安全态势、任务直播、Benchmark 三幕；当前仅展示 `SYNTHETIC` 合成演示流与 `REPLAY` 接入形态，Presenter Mode 已预留真实 run 接口。
4. **Access Studio**：在现有 RBAC 基础之上演示主机组、结构化 ABAC、JIT 和 Host 最终拒绝边界。
5. **Memory Lab**：候选、巩固、冲突、过期的生命周期，以及“睡眠整理”、主动召回与被动召回。
6. **Agent Orchestration**：展示 Skill、专职 Agent、类型化 Handoff、预算/停止条件、Checkpoint 和安全编排链；当前属于下一开发目标的交互原型。
7. **Roadmap Explorer**：稳健、进取、极限三个可叠加开关，展示四周内全部候选方向，不提前替团队删减范围。

## 数据语义与事实边界

本 Demo 严格区分三类数据：

- **项目实测数字**：来自 `docs/08-决赛冲刺/决赛事实基线与证据索引.md`。例如安全门禁固定测试集、RAG 和 Memory Benchmark；界面同时展示适用边界，不外推为开放世界结论。
- **代码已实现能力**：如 RBAC、R0–R5、职责分离审批、HMAC-SM3 上下文绑定、SM3 审计链、gRPC + mTLS、41 个 MCP 工具。Demo 只改变展示方式。
- **演示 fixture / 概念能力**：24 个逻辑实例、R1–R4 演示分组、主机状态、任务进度、ABAC、Memory Sleep 与部分路线图交互。界面显式标注“Demo / 模拟 / 待真机采集”。当前资产模型缺少 `site / room / rack / uPosition`，因此三维场景称为“逻辑/观测拓扑”，不能称真实物理机房。

## 生产接入点

| Demo 模块 | 建议生产数据源 | 接入约束 |
|---|---|---|
| Task Workspace | Host task SSE、SafeOps DAG、Span/Trace、审批、工具事件、`verifyChain` | 统一 `CANCELLED` 状态；事件按 `traceId/runId` 聚合；五证状态由后端给出，不由前端猜测 |
| 3D / 2D Topology | CMDB 主机、心跳、任务 SSE、Guard 聚合 | 补 `site/room/rack/uPosition/source/observedAt/confidence` 后再称物理拓扑；3D 与 2D 必须消费同一 store |
| Big Screen | Dashboard 聚合 API + 任务 SSE + Benchmark JSON | 周期聚合显示采集时间；SSE 与 30 秒聚合分开标识；历史数据必须标“真实历史回放” |
| Access Studio | IAM、`AccessControlService`、Policy Engine、Approval | ABAC 决策应由服务端完成；JIT token 绑定主体/资源/动作/TTL 且一次消费；Host 必须再次校验 |
| Memory Lab | `GraphMemoryService`、实体链接、任务归档、召回日志 | 新增候选区、来源、有效期、冲突状态、整理预演与可撤销提交；按查询类型路由图增强 |
| Agent Orchestration | SafeOps DAG、Policy、Approval、Skill Registry、Agent Registry、Trace | Agent/Skill 必须版本化；Handoff 使用类型化契约；权限取真人、Agent、Skill、资源与 Host 边界的交集 |
| Roadmap | 静态决赛计划或受控配置 | D-8 后冻结；入口是否登台由真机证据、拒绝路径和降级路径共同决定 |

## Three.js 与离线策略

- 机架、服务器、指示灯、标签和任务脉冲全部由 Three.js 几何体程序化生成，不加载外部模型。
- 运行时不使用 CDN、远程字体、图片或网络资源；Vite 会把 Vue 和 Three.js 打进本地构建产物。
- WebGL 初始化异常会自动切换 2D 视图；用户也可以手动切换。正式接入时应增加 3D/2D 数据一致性测试和目标设备 FPS 记录。

## 一个月内的高上限入口

Roadmap 不把工作量当作预先舍弃方向的理由，A–R 十八个工作包完整保留 DevOps ChangeOps、分场景 RAG 路由、Memory Sleep、Native Guard/系统 Hook、ABAC/JIT、麒麟漏洞与加固复扫、等保三级自评、数据库恢复、SBOM、Agent Runtime 与专家控制面。每项是否进入主演示，由真人运维场景、不可替代性、便利性、正常/拒绝/降级证据决定，而不是由 Demo 自行裁剪。
