# Workspace Runner 节点侧部署

Workspace Runner 是**被纳管节点上的常驻守护进程**，不是容器：它要用 rootless Podman
起沙箱、要通过 unix socket 被同机的 node-executor 调用，因此不放进 `deploy/docker/stack.yml`。

```
node-executor ──unix socket──> workspace-runner ──rootless podman──> 沙箱容器
 (kylin-agent)                  (kylin-workspace)                    (--network=none)
```

## 一键安装

```bash
sudo agents/workspace-runner/deploy/install-workspace-runner.sh [预编译二进制路径]
```

脚本做六件事：建 `kylin-workspace` 账号并写 `/etc/subuid`、`/etc/subgid`；把调用方
`kylin-agent` 加进 `kylin-workspace` 组；建状态目录；装二进制；以 `kylin-workspace`
身份 `podman build` 沙箱镜像；装 unit 并 `enable --now`，最后自检 socket 是否就位。

## 手工安装的等价步骤

```bash
# 1. 账号与 rootless 前提
sudo groupadd --system kylin-workspace
sudo useradd --system --gid kylin-workspace --home-dir /var/lib/kylin-workspace \
     --shell /usr/sbin/nologin kylin-workspace
echo 'kylin-workspace:200000:65536' | sudo tee -a /etc/subuid
echo 'kylin-workspace:200000:65536' | sudo tee -a /etc/subgid
sudo usermod -aG kylin-workspace kylin-agent      # 调用方要能穿进 socket 目录
sudo loginctl enable-linger kylin-workspace       # 否则 podman 退回 cgroupfs，配额失效

# 2. 沙箱镜像（必须以 kylin-workspace 身份构建：rootless 存储是按用户隔离的）
sudo runuser -u kylin-workspace -- podman build \
     -t kylin-workspace:latest -f agents/workspace-runner/Containerfile agents/workspace-runner

# 3. 二进制与 unit
(cd agents/workspace-runner && go build -o /tmp/kylin-workspace-runner ./cmd/workspace-runner)
sudo install -m 0755 /tmp/kylin-workspace-runner /opt/kylin-security-ops-agent/
sudo install -m 0644 agents/workspace-runner/deploy/kylin-workspace-runner.service \
     /etc/systemd/system/
sudo install -m 0640 -g kylin-workspace \
     agents/workspace-runner/deploy/kylin-workspace-runner.env.example \
     /etc/kylin-workspace-runner.env
sudo systemctl daemon-reload && sudo systemctl enable --now kylin-workspace-runner
```

## 验证

```bash
systemctl status kylin-workspace-runner
ls -l /run/kylin-workspace/workspace.sock      # 期望 srw-rw---- kylin-workspace:kylin-workspace
sudo runuser -u kylin-agent -- \
  python3 -c 'import socket,json;s=socket.socket(socket.AF_UNIX);s.connect("/run/kylin-workspace/workspace.sock");
s.sendall(json.dumps({"action":"exec","tenantId":"t","agentId":"a","workspaceId":"w",
"arguments":{"command":["bash","-lc","echo ok"]}}).encode());s.shutdown(socket.SHUT_WR);print(s.recv(65536))'
```

以别的账号（例如 `nobody`）重复上面这条应当被拒：socket 是 0660、目录 0750，越过这两层
还有内核侧的 SO_PEERCRED 复核（`KYLIN_WORKSPACE_PEER_USER`）。

## 两个反直觉的点

**unit 里刻意没开 `NoNewPrivileges`。** 它以及 `RestrictAddressFamilies=` /
`RestrictNamespaces=` / `RestrictSUIDSGID=` 等一批选项在 systemd 里会隐式打开
`NoNewPrivileges`（systemd.exec(5)），而 rootless Podman 建用户命名空间要执行 setuid 的
`newuidmap`/`newgidmap`——一旦打开，映射失败、容器创建不出来。privhelper 能把这些全开，
是因为它本身就是 root、不需要任何提权路径；这里是相反的情形。收敛靠别的地方：沙箱容器
一律 `--network=none`、runner 只监听一个 unix socket、镜像预先构建，运行期无需出站连接。

**镜像里没有 `ripgrep`。** UBI9 的 baseos/appstream 都不提供 `rg`（它在 EPEL），
沙箱又是 `--network=none`，构建期之外拿不到任何东西。检索用 `grep -r`；`yq` 是镜像里
自带的一份 Python 实现（YAML→JSON→jq，语义同 kislyuk/yq，不支持 `-i` 就地编辑），
见 `agents/workspace-runner/scripts/yq`。
