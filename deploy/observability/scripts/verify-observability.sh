#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker/observability.yml"
GRAFANA_URL="${GRAFANA_URL:-http://127.0.0.1:3001}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://127.0.0.1:9092}"
TEMPO_URL="${TEMPO_URL:-http://127.0.0.1:3200}"
OTEL_HTTP_HOST="${OTEL_HTTP_HOST:-127.0.0.1}"
OTEL_HTTP_PORT="${OTEL_HTTP_PORT:-4318}"
GRAFANA_USER="${GRAFANA_ADMIN_USER:-admin}"
GRAFANA_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-admin}"
TRACE_ID="${TRACE_ID:-}"
EMIT_SYNTHETIC_TRACE="${EMIT_SYNTHETIC_TRACE:-true}"

compose() {
  if command -v docker >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi
  echo "docker compose is required to run the observability stack" >&2
  exit 127
}

wait_url() {
  local name="$1"
  local url="$2"
  local deadline=$((SECONDS + 90))
  until curl -fsS "$url" >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "timeout waiting for $name: $url" >&2
      return 1
    fi
    sleep 2
  done
  echo "$name ok: $url"
}

require_datasource() {
  local uid="$1"
  curl -fsS -u "$GRAFANA_USER:$GRAFANA_PASSWORD" "$GRAFANA_URL/api/datasources/uid/$uid" >/dev/null
  echo "grafana datasource ok: $uid"
}

check_tcp() {
  local name="$1"
  local host="$2"
  local port="$3"
  local deadline=$((SECONDS + 90))
  until (: >"/dev/tcp/$host/$port") >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "timeout waiting for $name: $host:$port" >&2
      return 1
    fi
    sleep 2
  done
  echo "$name ok: $host:$port"
}

emit_synthetic_trace() {
  local output
  output="$(python3 - <<'PY'
import json
import secrets
import sys
import tempfile
import time

trace_id = secrets.token_hex(16)
span_id = secrets.token_hex(8)
now = time.time_ns()
payload = {
    "resourceSpans": [
        {
            "resource": {
                "attributes": [
                    {"key": "service.name", "value": {"stringValue": "kylinops-observability-smoke"}},
                    {"key": "service.namespace", "value": {"stringValue": "kylinops"}},
                    {"key": "deployment.environment", "value": {"stringValue": "demo"}},
                ]
            },
            "scopeSpans": [
                {
                    "scope": {"name": "kylinops-observability-smoke"},
                    "spans": [
                        {
                            "traceId": trace_id,
                            "spanId": span_id,
                            "name": "observability.synthetic_trace",
                            "kind": 1,
                            "startTimeUnixNano": str(now),
                            "endTimeUnixNano": str(now + 5_000_000),
                            "attributes": [
                                {"key": "safeops.smoke", "value": {"boolValue": True}},
                                {"key": "safeops.component", "value": {"stringValue": "otel-collector-to-tempo"}},
                            ],
                        }
                    ],
                }
            ],
        }
    ]
}
with tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8") as handle:
    json.dump(payload, handle, separators=(",", ":"))
    path = handle.name
print(trace_id)
print(path)
PY
)"
  local trace_id payload_path
  trace_id="$(printf '%s\n' "$output" | sed -n '1p')"
  payload_path="$(printf '%s\n' "$output" | sed -n '2p')"
  curl -fsS \
    -H "Content-Type: application/json" \
    --data-binary @"$payload_path" \
    "http://$OTEL_HTTP_HOST:$OTEL_HTTP_PORT/v1/traces" >/dev/null
  rm -f "$payload_path"
  wait_tempo_trace "$trace_id"
  echo "synthetic OTLP trace ok: $trace_id"
}

wait_tempo_trace() {
  local trace_id="$1"
  local deadline=$((SECONDS + 90))
  until body="$(curl -fsS "$TEMPO_URL/api/traces/$trace_id" 2>/dev/null)" && printf '%s' "$body" | grep -Fq "$trace_id"; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "timeout waiting for Tempo trace: $trace_id" >&2
      return 1
    fi
    sleep 2
  done
}

compose -f "$COMPOSE_FILE" up -d

wait_url "grafana" "$GRAFANA_URL/api/health"
wait_url "prometheus" "$PROMETHEUS_URL/-/ready"
wait_url "tempo" "$TEMPO_URL/ready"
check_tcp "otel-collector-http" "$OTEL_HTTP_HOST" "$OTEL_HTTP_PORT"

require_datasource "kylinops-prometheus"
require_datasource "kylinops-tempo"

curl -fsS "$PROMETHEUS_URL/api/v1/targets" >/dev/null
echo "prometheus targets api ok"

if [ "$EMIT_SYNTHETIC_TRACE" = "true" ]; then
  emit_synthetic_trace
else
  echo "EMIT_SYNTHETIC_TRACE=false; skipped Collector to Tempo synthetic trace check."
fi

if [ -n "$TRACE_ID" ]; then
  wait_tempo_trace "$TRACE_ID"
  echo "tempo trace query ok: $TRACE_ID"
else
  echo "TRACE_ID not set; skipped Tempo trace lookup. Run a SafeOps task, then rerun with TRACE_ID=<task trace id>."
fi

cat <<SUMMARY
observability-stack-ok
Grafana: $GRAFANA_URL
Prometheus: $PROMETHEUS_URL
Tempo: $TEMPO_URL
OTel Collector HTTP: $OTEL_HTTP_HOST:$OTEL_HTTP_PORT
SUMMARY
