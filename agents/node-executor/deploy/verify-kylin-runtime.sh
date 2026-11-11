#!/usr/bin/env bash
set -euo pipefail

service_name="${1:-kylin-node-executor}"

if ! systemctl is-active --quiet "$service_name"; then
  echo "$service_name is not active."
  systemctl status "$service_name" --no-pager || true
  exit 1
fi

logs="$(journalctl -u "$service_name" -n 120 --no-pager || true)"
if ! grep -q "ControlStream" <<<"$logs"; then
  echo "Missing ControlStream startup log for $service_name."
  exit 1
fi
if grep -Eiq "panic|fatal|enroll node|register node|receive control command" <<<"$logs"; then
  echo "Runtime logs contain fatal ControlStream errors."
  echo "$logs"
  exit 1
fi

echo "$service_name runtime looks healthy."
