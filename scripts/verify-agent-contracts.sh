#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
buf_version="v1.72.0"
contract_base_ref="${CONTRACT_BASE_REF:-HEAD}"
manifest_schema="tool-packages/schema/tool-package-manifest-1.0.schema.json"

generated_digest() {
  find \
    "$repo_root/services/backend/src/generated/java" \
    "$repo_root/agents/node-executor/internal/controlstream/proto/v2" \
    -type f -print0 \
    | sort -z \
    | xargs -0 shasum \
    | shasum \
    | awk '{print $1}'
}

cd "$repo_root"
go run "github.com/bufbuild/buf/cmd/buf@${buf_version}" lint
if git ls-tree -r --name-only "$contract_base_ref" proto/v2 | grep -q '\.proto$'; then
  go run "github.com/bufbuild/buf/cmd/buf@${buf_version}" breaking \
    --against ".git#ref=${contract_base_ref},subdir=proto/v2"
else
  echo "Skipping Buf breaking check: proto/v2 has no committed baseline yet."
fi
if git cat-file -e "${contract_base_ref}:${manifest_schema}" 2>/dev/null; then
  if ! git diff --quiet "$contract_base_ref" -- "$manifest_schema"; then
    echo "ToolPackageManifest schema 1.0 is immutable; add a versioned successor instead." >&2
    exit 1
  fi
else
  echo "Skipping manifest schema compatibility check: schema 1.0 has no committed baseline yet."
fi
generated_before="$(generated_digest)"
go run "github.com/bufbuild/buf/cmd/buf@${buf_version}" generate
generated_after="$(generated_digest)"
if [[ "$generated_before" != "$generated_after" ]]; then
  echo "Generated protobuf sources were stale; regenerate and commit them." >&2
  exit 1
fi

cd "$repo_root/agents/node-executor"
go run ./cmd/tool-manifest-gen --mode verify --repo-root ../..
go test ./internal/canonicaljson ./internal/signing ./internal/approvalv2 ./internal/controlstream/proto/v2

cd "$repo_root/services/backend"
mvn -q -Dtest='AgentRuntimeMigrationTest,AgentRuntimeStateTest,CanonicalJsonTest,EnvironmentFileSecretResolverTest,Sm2ApprovalTokenServiceTest' test

echo "Agent Runtime contract verification passed."
