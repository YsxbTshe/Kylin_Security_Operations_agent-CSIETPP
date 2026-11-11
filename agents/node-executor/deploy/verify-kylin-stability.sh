#!/usr/bin/env bash
set -euo pipefail

service_name="${1:-kylin-node-executor}"
rounds="${2:-3}"

for i in $(seq 1 "$rounds"); do
  if ! systemctl is-active --quiet "$service_name"; then
    echo "$service_name inactive at round $i"
    systemctl status "$service_name" --no-pager || true
    exit 1
  fi
  logs="$(journalctl -u "$service_name" -n 160 --no-pager || true)"
  if grep -Eiq "panic|fatal|enroll node|register node|permission denied" <<<"$logs"; then
    echo "Stability logs contain fatal errors at round $i."
    echo "$logs"
    exit 1
  fi
  sleep 2
done

echo "$service_name ControlStream stability check passed."
