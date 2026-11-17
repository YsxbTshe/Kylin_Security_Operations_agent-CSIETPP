# dashboard-screen

数据大屏前端，基于 Vue 3、Vite、ECharts。

## 当前功能

- 展示纳管节点、在线节点、活跃告警、执行次数
- 展示风险分布、趋势、事件流、节点健康和 Agent 状态
- 通过后端 `/api/v1/system/screen` 读取聚合数据
- 前端按 30 秒轮询刷新

## 运行

```bash
npm install
npm run dev
```

默认开发环境文件：

- `.env.development`
- `VITE_API_BASE_URL=http://127.0.0.1:8080/api/v1`
- `VITE_SCREEN_ORGANIZATION_ID=org-alpha`
- `VITE_SCREEN_TOKEN=dev-screen-token`

## 构建

```bash
npm run build
```

## 说明

- 默认开发环境已经提供 `VITE_API_BASE_URL`、`VITE_SCREEN_ORGANIZATION_ID`、`VITE_SCREEN_TOKEN`。
- 当前不是实时推送大屏，也不使用静态演示数据回填图表。
- 若后端没有主机、告警、审计和 Host Agent 任务数据，大屏会显示空态或请求失败提示。
