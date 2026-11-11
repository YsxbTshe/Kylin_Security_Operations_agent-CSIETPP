#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/deploy/config/prod.env}"
COMPONENTS="${1:-backend,admin,screen}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing env file: $ENV_FILE" >&2
  echo "Copy deploy/config/prod.env.example to deploy/config/prod.env and fill it." >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

: "${DEPLOY_SSH_TARGET:?DEPLOY_SSH_TARGET is required}"
: "${DEPLOY_SSH_PORT:=22}"
: "${BACKEND_REMOTE_JAR:=/opt/kylinops/backend/backend.jar}"
: "${BACKEND_OWNER:=kylinops:kylinops}"
: "${BACKEND_SERVICE:=kylinops-backend}"
: "${BACKEND_HEALTH_URL:=http://127.0.0.1:8080/api/v1/system/health}"
: "${BACKEND_START_TIMEOUT_SECONDS:=90}"
: "${BACKEND_OTEL_ENABLED:=false}"
: "${BACKEND_OTEL_AGENT_LOCAL:=}"
: "${BACKEND_OTEL_AGENT_PATH:=/opt/kylinops/otel/opentelemetry-javaagent.jar}"
: "${BACKEND_OTEL_SERVICE_NAME:=kylinops-backend}"
: "${BACKEND_OTEL_EXPORTER_OTLP_ENDPOINT:=http://127.0.0.1:4318}"
: "${BACKEND_OTEL_EXPORTER_OTLP_PROTOCOL:=http/protobuf}"
: "${BACKEND_OTEL_RESOURCE_ATTRIBUTES:=service.namespace=kylinops,deployment.environment=demo}"
: "${ADMIN_REMOTE_DIR:=/opt/1panel/www/sites/kylinops-admin/index}"
: "${ADMIN_HEALTH_URL:=http://127.0.0.1:18080/}"
: "${SCREEN_REMOTE_DIR:=/opt/1panel/www/sites/kylinops-screen/index}"
: "${SCREEN_HEALTH_URL:=http://127.0.0.1:18081/}"
# Kylin host node (Node Executor) — optional `host` component.
: "${HOST_SSH_PORT:=22}"
: "${NODE_EXECUTOR_REMOTE_BIN:=/opt/kylin-security-ops-agent/kylin-node-executor}"
: "${NODE_EXECUTOR_SERVICE:=kylin-node-executor}"
: "${NODE_EXECUTOR_BINARY:=$ROOT_DIR/agents/node-executor/dist/kylin-node-executor-linux-loong64}"

SSH_OPTS=(
  -p "$DEPLOY_SSH_PORT"
  -o BatchMode=no
  -o StrictHostKeyChecking=accept-new
  -o ConnectTimeout=20
  -o LogLevel=ERROR
)
SCP_OPTS=(
  -P "$DEPLOY_SSH_PORT"
  -o BatchMode=no
  -o StrictHostKeyChecking=accept-new
  -o ConnectTimeout=20
  -o LogLevel=ERROR
)

run_expect() {
  local timeout_seconds="$1"
  shift
  if [[ -n "${DEPLOY_SSH_PASSWORD:-}" ]]; then
    EXPECT_PASSWORD="$DEPLOY_SSH_PASSWORD" expect -f - -- "$@" <<EXPECT
set timeout $timeout_seconds
log_user 0
set password \$env(EXPECT_PASSWORD)
spawn {*}\$argv
expect {
  -re {(?i)are you sure you want to continue connecting} { send "yes\\r"; exp_continue }
  -re {(?i)(password|passphrase).*:} { send -- "\$password\\r"; exp_continue }
  eof {}
  timeout { exit 124 }
}
catch wait result
exit [lindex \$result 3]
EXPECT
  else
    "$@"
  fi
}

ssh_run() {
  local remote_cmd="$1"
  local argv=(ssh "${SSH_OPTS[@]}" "$DEPLOY_SSH_TARGET" "$remote_cmd")
  run_expect 180 "${argv[@]}"
}

scp_put() {
  local local_path="$1"
  local remote_path="$2"
  local argv=(scp "${SCP_OPTS[@]}" "$local_path" "$DEPLOY_SSH_TARGET:$remote_path")
  run_expect "${SCP_TIMEOUT_SECONDS:-900}" "${argv[@]}"
}

# --- Host node (Kylin Node Executor) helpers: separate SSH identity from control plane ---
host_run_expect() {
  local timeout_seconds="$1"
  shift
  if [[ -n "${HOST_SSH_PASSWORD:-}" ]]; then
    EXPECT_PASSWORD="$HOST_SSH_PASSWORD" expect -f - -- "$@" <<EXPECT
set timeout $timeout_seconds
log_user 0
set password \$env(EXPECT_PASSWORD)
spawn {*}\$argv
expect {
  -re {(?i)are you sure you want to continue connecting} { send "yes\\r"; exp_continue }
  -re {(?i)(password|passphrase).*:} { send -- "\$password\\r"; exp_continue }
  eof {}
  timeout { exit 124 }
}
catch wait result
exit [lindex \$result 3]
EXPECT
  else
    "$@"
  fi
}

host_ssh_run() {
  local remote_cmd="$1"
  local argv=(ssh -p "$HOST_SSH_PORT" -o BatchMode=no -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o LogLevel=ERROR "$HOST_SSH_TARGET" "$remote_cmd")
  host_run_expect 180 "${argv[@]}"
}

host_scp_put() {
  local local_path="$1"
  local remote_path="$2"
  local argv=(scp -P "$HOST_SSH_PORT" -o BatchMode=no -o StrictHostKeyChecking=accept-new -o ConnectTimeout=20 -o LogLevel=ERROR "$local_path" "$HOST_SSH_TARGET:$remote_path")
  host_run_expect 300 "${argv[@]}"
}

deploy_host() {
  : "${HOST_SSH_TARGET:?HOST_SSH_TARGET is required for host component}"
  [[ -f "$NODE_EXECUTOR_BINARY" ]] || { echo "Missing node executor binary: $NODE_EXECUTOR_BINARY (build with GOOS=linux GOARCH=loong64 go build)" >&2; exit 1; }

  echo "==> Uploading node executor binary"
  host_scp_put "$NODE_EXECUTOR_BINARY" "/tmp/kylin-node-executor.next"

  echo "==> Installing + restarting node executor (requires passwordless sudo on the node)"
  host_ssh_run "set -e; sudo -n install -m 0755 -o root -g root /tmp/kylin-node-executor.next '$NODE_EXECUTOR_REMOTE_BIN'; rm -f /tmp/kylin-node-executor.next; sudo -n systemctl restart '$NODE_EXECUTOR_SERVICE'"

  echo "==> Verifying node executor"
  host_ssh_run "set -e; deadline=\$((SECONDS + 30)); until sudo -n systemctl is-active '$NODE_EXECUTOR_SERVICE' >/dev/null 2>&1; do if [ \$SECONDS -ge \$deadline ]; then sudo -n systemctl status '$NODE_EXECUTOR_SERVICE' --no-pager -l | sed -n '1,40p'; exit 1; fi; sleep 3; done; echo host-ok"
}

has_component() {
  [[ ",$COMPONENTS," == *",$1,"* ]]
}

shell_quote() {
  printf "'%s'" "$(printf "%s" "$1" | sed "s/'/'\\\\''/g")"
}

systemd_env_value() {
  printf "%s" "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

ensure_remote_layout() {
  echo "==> Checking remote layout"
  local checks="command -v systemctl >/dev/null"
  if has_component backend; then
    checks="$checks; test -d \"$(dirname "$BACKEND_REMOTE_JAR")\""
  fi
  if has_component admin; then
    checks="$checks; test -d \"$ADMIN_REMOTE_DIR\""
  fi
  if has_component screen; then
    checks="$checks; test -d \"$SCREEN_REMOTE_DIR\""
  fi
  ssh_run "set -e; $checks; echo remote-layout-ok"
}

configure_backend_otel() {
  if [[ "$BACKEND_OTEL_ENABLED" != "true" ]]; then
    return
  fi

  local agent_dir
  agent_dir="$(dirname "$BACKEND_OTEL_AGENT_PATH")"
  ssh_run "set -e; install -d -m 0755 $(shell_quote "$agent_dir")"

  if [[ -n "$BACKEND_OTEL_AGENT_LOCAL" ]]; then
    [[ -f "$BACKEND_OTEL_AGENT_LOCAL" ]] || { echo "Missing BACKEND_OTEL_AGENT_LOCAL: $BACKEND_OTEL_AGENT_LOCAL" >&2; exit 1; }
    echo "==> Uploading OpenTelemetry Java agent"
    scp_put "$BACKEND_OTEL_AGENT_LOCAL" "/tmp/kylinops-opentelemetry-javaagent.jar.next"
    ssh_run "set -e; install -m 0644 /tmp/kylinops-opentelemetry-javaagent.jar.next $(shell_quote "$BACKEND_OTEL_AGENT_PATH"); chown $(shell_quote "$BACKEND_OWNER") $(shell_quote "$BACKEND_OTEL_AGENT_PATH"); rm -f /tmp/kylinops-opentelemetry-javaagent.jar.next"
  else
    ssh_run "test -f $(shell_quote "$BACKEND_OTEL_AGENT_PATH") || { echo 'Missing OpenTelemetry Java agent: $BACKEND_OTEL_AGENT_PATH' >&2; exit 1; }"
  fi

  local dropin_dir="/etc/systemd/system/$BACKEND_SERVICE.service.d"
  local dropin_file="$dropin_dir/otel.conf"
  local java_tool_options="-javaagent:$BACKEND_OTEL_AGENT_PATH"
  local otel_conf
  otel_conf="[Service]
Environment=\"JAVA_TOOL_OPTIONS=$(systemd_env_value "$java_tool_options")\"
Environment=\"OTEL_SERVICE_NAME=$(systemd_env_value "$BACKEND_OTEL_SERVICE_NAME")\"
Environment=\"OTEL_EXPORTER_OTLP_ENDPOINT=$(systemd_env_value "$BACKEND_OTEL_EXPORTER_OTLP_ENDPOINT")\"
Environment=\"OTEL_EXPORTER_OTLP_PROTOCOL=$(systemd_env_value "$BACKEND_OTEL_EXPORTER_OTLP_PROTOCOL")\"
Environment=\"OTEL_TRACES_EXPORTER=otlp\"
Environment=\"OTEL_METRICS_EXPORTER=none\"
Environment=\"OTEL_LOGS_EXPORTER=none\"
Environment=\"OTEL_PROPAGATORS=tracecontext,baggage\"
Environment=\"OTEL_RESOURCE_ATTRIBUTES=$(systemd_env_value "$BACKEND_OTEL_RESOURCE_ATTRIBUTES")\""

  echo "==> Configuring backend OpenTelemetry systemd drop-in"
  ssh_run "set -e; install -d -m 0755 $(shell_quote "$dropin_dir"); cat > $(shell_quote "$dropin_file") <<'KOTELCONF'
$otel_conf
KOTELCONF
systemctl daemon-reload"
}

deploy_backend() {
  local jar="$ROOT_DIR/services/backend/target/backend-0.1.0-SNAPSHOT.jar"
  [[ -f "$jar" ]] || { echo "Missing backend jar: $jar" >&2; exit 1; }

  echo "==> Uploading backend jar"
  scp_put "$jar" "/tmp/kylinops-backend.jar.next"

  configure_backend_otel

  echo "==> Restarting backend"
  ssh_run "set -e; install -m 0644 /tmp/kylinops-backend.jar.next '$BACKEND_REMOTE_JAR'; chown '$BACKEND_OWNER' '$BACKEND_REMOTE_JAR'; rm -f /tmp/kylinops-backend.jar.next; systemctl restart '$BACKEND_SERVICE'"

  echo "==> Verifying backend"
  ssh_run "set -e; deadline=\$((SECONDS + $BACKEND_START_TIMEOUT_SECONDS)); until systemctl is-active '$BACKEND_SERVICE' >/dev/null 2>&1 && curl -fsS '$BACKEND_HEALTH_URL' >/dev/null 2>&1; do if [ \$SECONDS -ge \$deadline ]; then systemctl status '$BACKEND_SERVICE' --no-pager -l | sed -n '1,80p'; exit 1; fi; sleep 3; done; echo backend-ok"
}


deploy_screen() {
  local dist="$ROOT_DIR/apps/dashboard-screen/dist"
  [[ -f "$dist/index.html" ]] || { echo "Missing dashboard dist: $dist" >&2; exit 1; }

  local archive
  archive="$(mktemp -t kylinops-screen-dist.XXXXXX.tgz)"
  tar -C "$dist" -czf "$archive" .

  echo "==> Uploading screen dist"
  scp_put "$archive" "/tmp/kylinops-screen-dist.tgz"
  rm -f "$archive"

  echo "==> Publishing screen dist"
  ssh_run "set -e; test -d '$SCREEN_REMOTE_DIR'; find '$SCREEN_REMOTE_DIR' -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -xzf /tmp/kylinops-screen-dist.tgz -C '$SCREEN_REMOTE_DIR'; rm -f /tmp/kylinops-screen-dist.tgz; test -f '$SCREEN_REMOTE_DIR/index.html'"

  echo "==> Verifying screen"
  ssh_run "set -e; curl -fsSI '$SCREEN_HEALTH_URL' | awk 'NR==1{print}'; echo screen-ok"
}

deploy_admin() {
  local dist="$ROOT_DIR/apps/admin-web/dist"
  [[ -f "$dist/index.html" ]] || { echo "Missing admin dist: $dist" >&2; exit 1; }

  local archive
  archive="$(mktemp -t kylinops-admin-dist.XXXXXX.tgz)"
  tar -C "$dist" -czf "$archive" .

  echo "==> Uploading admin dist"
  scp_put "$archive" "/tmp/kylinops-admin-dist.tgz"
  rm -f "$archive"

  echo "==> Publishing admin dist"
  ssh_run "set -e; test -d '$ADMIN_REMOTE_DIR'; find '$ADMIN_REMOTE_DIR' -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +; tar -xzf /tmp/kylinops-admin-dist.tgz -C '$ADMIN_REMOTE_DIR'; rm -f /tmp/kylinops-admin-dist.tgz; test -f '$ADMIN_REMOTE_DIR/index.html'"

  echo "==> Verifying admin"
  ssh_run "set -e; curl -fsSI '$ADMIN_HEALTH_URL' | awk 'NR==1{print}'; echo admin-ok"
}

# Control-plane components share one SSH identity and need the remote layout check.
if has_component backend || has_component admin || has_component screen; then
  ensure_remote_layout
fi
has_component backend && deploy_backend
has_component admin && deploy_admin
has_component screen && deploy_screen
has_component host && deploy_host

echo "deploy-done components=$COMPONENTS"
