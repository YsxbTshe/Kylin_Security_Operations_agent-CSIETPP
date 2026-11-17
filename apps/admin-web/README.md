# admin-web

管理端前端，基于 Vue 3、Vite、Pinia、Vue Router、Reka UI 和 lucide-vue-next。

## 当前功能

- 登录与组织切换
- 总览、主机、告警
- 平台问答
- Host Agent 控制台
- 平台审计与 Agent 审计
- 用户、角色、权限、组织管理
- 平台 LLM 配置
- RAG 文档管理

## 运行

```bash
npm install
npm run dev
```

默认开发环境文件：

- `.env.development`
- `VITE_API_BASE_URL=/api/v1`
- `VITE_API_PROXY_TARGET=http://127.0.0.1:8080`

## 构建

```bash
npm run build
```

## 说明

- 页面依赖后端 `services/backend` 提供的 `/api/v1` 接口。
- 本地开发默认通过 Vite 代理把 `/api` 转发到 `http://127.0.0.1:8080`。
- Host Agent 控制台负责查看任务、turn、trace、approval，并对待审批动作执行人工批准。
- 本目录不是模板工程；若文档口径与根目录 `README.md` 冲突，以根目录和后端接口实现为准。
