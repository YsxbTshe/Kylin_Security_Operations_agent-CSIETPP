# 可观测部署（155 后端机 + 麒麟主机）

面向资源受限节点的单机可观测栈。Tempo/OTel 因 155 内存/磁盘预算被裁掉（见根 `deploy/observability/` 为完整演示栈）。

## 拓扑

```text
麒麟主机(loongarch64)                155 后端机(Debian, 4C/3.8G)
  node_exporter :9100  ──ssh -R──▶  127.0.0.1:9101 ─┐
  node-executor ──gRPC──▶ backend :8080 /actuator/prometheus ─┤
                                     node-exporter :9100 ────┤──▶ Prometheus :9092 ──▶ Grafana :3001
```

- **backend**：Spring Boot Actuator，含控制面指标与经 gRPC 上报的麒麟 Node Executor 指标（`kylin_node_executor_*`）。
- **node-155**：155 控制面主机 OS 指标。
- **kylin-node**：麒麟主机 OS 指标，经反向 SSH 隧道（麒麟 :9100 → 155 :9101）。

## 已部署并验证（2026-06-14）

155 后端机 `/root/kylinops-observability/`：

```bash
cd /root/kylinops-observability && docker compose up -d
```

Prometheus targets 实测：`backend → up`、`node-155 → up`、`kylin-node → down`（隧道未建时）。
Grafana `http://155.117.82.8:3001`（admin / kylinops-admin），已自动加载数据源 + 看板
`Kylin SafeOps 总览`、`Kylin 主机资源 (node_exporter)`。

## 麒麟主机 node_exporter（loong64）

官方未提供 loong64 预编译包、主机无 Go，故在开发机交叉编译：

```bash
git clone --depth 1 -b v1.8.2 https://github.com/prometheus/node_exporter
cd node_exporter && CGO_ENABLED=0 GOOS=linux GOARCH=loong64 go build -ldflags="-s -w" -o node_exporter-loong64 .
scp -P 60022 node_exporter-loong64 vmuser@155.117.82.8:/tmp/node_exporter
```

在麒麟主机执行 `install-kylin-node-exporter.sh`（systemd 服务 + 反向隧道）。
反向隧道需麒麟→155 的 SSH key 认证：在麒麟主机生成密钥，公钥加入 155 `root` 的 `authorized_keys`。

> 注：麒麟主机仅经 60022 内网穿透访问，该隧道在密集连接下会出现 banner-exchange 超时；
> node_exporter 安装与隧道脚本就绪，待隧道稳定时一次性执行即可。
