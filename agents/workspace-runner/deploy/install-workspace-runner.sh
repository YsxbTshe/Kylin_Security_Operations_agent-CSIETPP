#!/usr/bin/env bash
# 在被纳管节点上安装 kylin-workspace-runner（常驻守护进程 + rootless Podman 沙箱镜像）。
#
# 幂等：重复执行只会覆盖二进制/unit/镜像，不会重复建用户或重复追加 subuid 区间。
# 需要 root 执行（建用户、写 /etc、装 unit）。
#
#   sudo ./install-workspace-runner.sh [/path/to/kylin-workspace-runner]
#
# 不传二进制路径时，脚本会在本机用 go 现场编译（需要 Go 1.26+）。交叉编译产物
# （例如 loong64）请先在构建机上生成，再把路径传进来。
set -euo pipefail

RUNNER_USER=kylin-workspace
RUNNER_GROUP=kylin-workspace
PEER_USER=${KYLIN_WORKSPACE_PEER_USER:-kylin-agent}
INSTALL_DIR=/opt/kylin-security-ops-agent
STATE_DIR=/var/lib/kylin-workspace
IMAGE=${KYLIN_WORKSPACE_IMAGE:-kylin-workspace:latest}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$(cd "$HERE/.." && pwd)"

[[ $EUID -eq 0 ]] || { echo "必须以 root 运行" >&2; exit 1; }
command -v podman >/dev/null || { echo "未找到 podman，请先安装 rootless Podman" >&2; exit 1; }

echo "==> 1/6 账号与 subuid/subgid"
getent group "$RUNNER_GROUP" >/dev/null || groupadd --system "$RUNNER_GROUP"
getent passwd "$RUNNER_USER" >/dev/null || useradd --system --gid "$RUNNER_GROUP" \
    --home-dir "$STATE_DIR" --shell /usr/sbin/nologin "$RUNNER_USER"
# rootless podman 必须有 uid/gid 映射区间，否则连容器都创建不出来。
grep -q "^${RUNNER_USER}:" /etc/subuid || echo "${RUNNER_USER}:200000:65536" >> /etc/subuid
grep -q "^${RUNNER_USER}:" /etc/subgid || echo "${RUNNER_USER}:200000:65536" >> /etc/subgid
# 调用方（node-executor 的运行账号）必须能穿进 /run/kylin-workspace（0750，属组 kylin-workspace）。
# 只加组不改 node-executor 的 unit：systemd 会按 /etc/group 给服务用户带上全部附加组。
if getent passwd "$PEER_USER" >/dev/null; then
    usermod -aG "$RUNNER_GROUP" "$PEER_USER"
else
    echo "⚠️ 调用方用户 $PEER_USER 不存在；先装 node-executor，再重跑本脚本，否则 socket 连不上" >&2
fi
# 没有 linger 就没有用户级 systemd/dbus，podman 会退回 cgroupfs，内存/PID 限制形同虚设。
loginctl enable-linger "$RUNNER_USER" >/dev/null 2>&1 || \
    echo "⚠️ enable-linger 失败（无 systemd-logind？）：请确认 podman 的 cgroup 限制仍然生效" >&2

echo "==> 2/6 目录"
install -d -o "$RUNNER_USER" -g "$RUNNER_GROUP" -m 0750 "$STATE_DIR" "$STATE_DIR/audit" "$STATE_DIR/seccomp"
install -d -m 0755 "$INSTALL_DIR"

echo "==> 3/6 二进制"
BINARY=${1:-}
if [[ -z "$BINARY" ]]; then
    command -v go >/dev/null || { echo "未找到 go，且未提供预编译二进制路径" >&2; exit 1; }
    BINARY=$(mktemp)
    (cd "$SRC_DIR" && go build -o "$BINARY" ./cmd/workspace-runner)
fi
install -m 0755 -o root -g root "$BINARY" "$INSTALL_DIR/kylin-workspace-runner"

echo "==> 4/6 沙箱镜像（以 $RUNNER_USER 身份构建，rootless 存储在该用户名下才可用）"
runuser -u "$RUNNER_USER" -- env HOME="$STATE_DIR" XDG_RUNTIME_DIR="/run/user/$(id -u "$RUNNER_USER")" \
    podman build -t "$IMAGE" -f "$SRC_DIR/Containerfile" "$SRC_DIR"

echo "==> 5/6 systemd unit 与 env"
install -m 0644 -o root -g root "$HERE/kylin-workspace-runner.service" \
    /etc/systemd/system/kylin-workspace-runner.service
if [[ ! -f /etc/kylin-workspace-runner.env ]]; then
    install -m 0640 -o root -g "$RUNNER_GROUP" "$HERE/kylin-workspace-runner.env.example" \
        /etc/kylin-workspace-runner.env
fi
systemctl daemon-reload
systemctl enable --now kylin-workspace-runner.service

echo "==> 6/6 自检"
systemctl is-active --quiet kylin-workspace-runner.service || {
    echo "服务未就绪，看日志：journalctl -u kylin-workspace-runner -n 50" >&2; exit 1; }
ls -l /run/kylin-workspace/workspace.sock
echo "完成。node-executor 侧确认 KYLIN_WORKSPACE_RUNNER_SOCKET=/run/kylin-workspace/workspace.sock"
