#!/usr/bin/env bash
set -euo pipefail

agent_user="${KYLIN_EXECUTION_USER:-kylin-agent}"
install_dir="${KYLIN_NODE_EXECUTOR_INSTALL_DIR:-/opt/kylin-security-ops-agent}"
binary_path="${KYLIN_NODE_EXECUTOR_BINARY:-$install_dir/kylin-node-executor}"
service_name="${KYLIN_NODE_EXECUTOR_SERVICE:-kylin-node-executor}"

os_release=""
if [[ -r /etc/os-release ]]; then
  os_release="$(cat /etc/os-release)"
fi
kernel_release="$(uname -a)"
if ! printf '%s\n%s\n' "$os_release" "$kernel_release" | grep -Eiq 'kylin|麒麟|openEuler'; then
  echo "MISSING Kylin-compatible OS marker in /etc/os-release or uname."
  exit 1
fi

required_commands=(
  systemctl
  journalctl
  firewall-cmd
  ss
  lsof
  df
  ps
  top
  free
  uptime
  who
  ip
  uname
  curl
)

missing=0
for command_name in "${required_commands[@]}"; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "MISSING command: $command_name"
    missing=1
  fi
done

if ! id "$agent_user" >/dev/null 2>&1; then
  echo "MISSING user: $agent_user"
  missing=1
fi

if [[ ! -x "$binary_path" ]]; then
  echo "MISSING executable: $binary_path"
  missing=1
fi

if [[ -f "/etc/systemd/system/$service_name.service" ]]; then
  systemctl cat "$service_name" >/dev/null
else
  echo "MISSING service unit: /etc/systemd/system/$service_name.service"
  missing=1
fi

if command -v sudo >/dev/null 2>&1 && id "$agent_user" >/dev/null 2>&1; then
  sudo -n -l -U "$agent_user" >/dev/null 2>&1 || {
    echo "WARN sudo policy is not available for $agent_user without password"
  }
fi

if [[ "$missing" -ne 0 ]]; then
  echo "Node Executor preflight failed."
  exit 1
fi

echo "Node Executor preflight passed."
