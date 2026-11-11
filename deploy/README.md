# 部署说明

本目录保存生产/演示环境部署脚本和非敏感配置模板。真实凭证不再写入 Markdown 文档，统一放到本地 env 文件。

## 1. 本地凭证文件

首次使用时复制模板：

```bash
cp deploy/config/prod.env.example deploy/config/prod.env
chmod 600 deploy/config/prod.env
```

然后填写真实值。`deploy/config/*.env` 已被 `.gitignore` 忽略，不要提交。

关键变量：

- `DEPLOY_SSH_TARGET` / `DEPLOY_SSH_PORT` / `DEPLOY_SSH_PASSWORD`：后端服务器 SSH。
- `BACKEND_REMOTE_JAR` / `BACKEND_SERVICE` / `BACKEND_HEALTH_URL`：后端 jar、systemd 服务和健康检查。
- `BACKEND_OTEL_*`：可选后端 OpenTelemetry Java Agent 部署与 systemd drop-in 配置。
- `ADMIN_REMOTE_DIR` / `ADMIN_HEALTH_URL`：后台前端静态资源目录和访问检查。
- `SCREEN_REMOTE_DIR` / `SCREEN_HEALTH_URL`：大屏静态资源目录和访问检查。
- `HOST_SSH_*`：目标节点 SSH 连接；`NODE_EXECUTOR_*`：Node Executor 产物、远端路径和服务名。

## 2. 构建

```bash
mvn -q -DskipTests package
(cd apps/admin-web && npm run build)
(cd apps/dashboard-screen && npm run build)
```

## 3. 发布

发布后端、后台前端和数据大屏：

```bash
./deploy/scripts/deploy-prod.sh backend,admin,screen
```

只发布后台前端：

```bash
./deploy/scripts/deploy-prod.sh admin
```

只发布数据大屏：

```bash
./deploy/scripts/deploy-prod.sh screen
```

只发布后端：

```bash
./deploy/scripts/deploy-prod.sh backend
```

## 4. 验证

脚本会自动检查：

- 远端部署目录存在。
- 后端 systemd 服务 active 且 health URL 可访问。
- 后台前端首页返回 HTTP 响应。
- 数据大屏首页返回 HTTP 响应。

## 5. 可观测演示栈

启动 Grafana / Tempo / OpenTelemetry Collector / Prometheus：

```bash
docker compose -f deploy/docker/observability.yml up -d
```

一键启动并检查 Grafana datasource、Prometheus、Tempo 和 OTel Collector 端口：

```bash
./deploy/observability/scripts/verify-observability.sh
```

该脚本默认还会向 OTel Collector HTTP `/v1/traces` 注入一条 synthetic trace，并等待 Tempo 可以查询到它，用来验证 Collector → Tempo 链路。只想做健康检查时可跳过：

```bash
EMIT_SYNTHETIC_TRACE=false ./deploy/observability/scripts/verify-observability.sh
```

跑完一次 SafeOps 任务后，可以带 trace id 验证 Tempo 查询：

```bash
TRACE_ID=<task-trace-id> ./deploy/observability/scripts/verify-observability.sh
```

默认端口：

- Grafana: <http://127.0.0.1:3001>
- Prometheus: <http://127.0.0.1:9092>
- Tempo HTTP: <http://127.0.0.1:3200>
- OTel Collector gRPC: `127.0.0.1:4317`
- OTel Collector HTTP: `127.0.0.1:4318`

后端推荐通过 OpenTelemetry Java Agent 接入，不在应用内绑定具体 exporter：

```bash
JAVA_TOOL_OPTIONS=-javaagent:/opt/kylinops/otel/opentelemetry-javaagent.jar
OTEL_SERVICE_NAME=kylinops-backend
OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_PROPAGATORS=tracecontext,baggage
OTEL_RESOURCE_ATTRIBUTES=service.namespace=kylinops,deployment.environment=demo
```

如果用 `deploy/scripts/deploy-prod.sh backend` 部署后端，可在 `deploy/config/prod.env` 中开启：

```bash
BACKEND_OTEL_ENABLED=true
BACKEND_OTEL_AGENT_LOCAL=/path/to/opentelemetry-javaagent.jar
BACKEND_OTEL_AGENT_PATH=/opt/kylinops/otel/opentelemetry-javaagent.jar
BACKEND_OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318
```

脚本会把 agent jar 上传到远端，并写入：

```text
/etc/systemd/system/${BACKEND_SERVICE}.service.d/otel.conf
```

然后执行 `systemctl daemon-reload` 并重启后端服务。

Node Executor 通过内置 Go OTel SDK 接入：

```bash
KYLIN_OTEL_ENABLED=true
OTEL_SERVICE_NAME=kylin-node-executor
OTEL_EXPORTER_OTLP_ENDPOINT=127.0.0.1:4317
OTEL_EXPORTER_OTLP_INSECURE=true
```

比赛版只要求单机可演示闭环：Grafana 看 dashboard，Tempo 查一次 SafeOps task trace，Prometheus scrape 后端和 Node Executor 指标。不做 HA、远程存储、多租户和复杂采样策略。

## 6. 本次部署记录

2026-06-08 本次部署中，远端 H2 数据库已有旧版 `V27` Flyway 记录，但当前 jar 的 `V27` 多扩容了 `app_assistant_message.content`，导致 checksum mismatch。处理方式：

1. 备份远端 H2 DB。
2. 补齐 `app_assistant_message.content varchar(65536)`。
3. 执行 Flyway repair，使远端 schema history 与当前 jar 对齐。
4. 启动后端并确认 `V27`、`V28` 均成功。

后续不要再修改已经发布过的 Flyway 迁移文件；新增 schema 变更应追加新的 `Vxx__*.sql`。
