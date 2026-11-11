#!/usr/bin/env bash
# 后端双库验证：默认 H2（mvn test）；若提供 POSTGRES_IT_JDBC_URL 则追加 PostgreSQL + pgvector 迁移 IT。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="$ROOT/services/backend"

cd "$BACKEND"
echo "==> H2: mvn test"
mvn -B test

if [[ -n "${POSTGRES_IT_JDBC_URL:-}" ]]; then
  echo "==> PostgreSQL IT: PostgresPgvectorMigrationIT"
  mvn -B -Dtest=PostgresPgvectorMigrationIT -DfailIfNoTests=false test
else
  echo "==> skip PostgreSQL IT (POSTGRES_IT_JDBC_URL unset)"
fi
