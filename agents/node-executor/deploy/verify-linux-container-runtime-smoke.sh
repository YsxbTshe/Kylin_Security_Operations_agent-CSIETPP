#!/usr/bin/env bash
set -euo pipefail

binary="${1:-./kylin-node-executor}"
if [[ ! -x "$binary" ]]; then
  echo "binary not executable: $binary"
  exit 1
fi

output="$(timeout 3s "$binary" 2>&1 || true)"
if ! printf '%s\n' "$output" | grep -Eq 'ControlStream|KYLIN_ENROLLMENT_TOKEN'; then
  echo "MISSING ControlStream/bootstrap startup signal."
  printf '%s\n' "$output"
  exit 1
fi

echo "container runtime smoke completed."
