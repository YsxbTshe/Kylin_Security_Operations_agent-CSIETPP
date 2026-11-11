# Tool packages

`schema/tool-package-manifest-1.0.schema.json` is the immutable JSON Schema 2020-12
contract for ToolPackageManifest 1.0. Breaking changes require a new versioned schema.

`core-node-tools/manifest.unsigned.json` is the reviewed source manifest (package
`kylin.core.node` 1.1.0 covers the Go `defaultCatalog` ∪ Java node/workspace tools).
Runtime still executes the catalog compiled into the Node Executor binary; the signed
JSON is the offline SM2 contract and projection source, not a runtime loader.
Generate the signed manifest and all backend, MCP, Node Executor, and privilege-helper
projections with:

```bash
cd agents/node-executor
go run ./cmd/tool-manifest-gen --mode sign --repo-root ../.. \
  --private-key /absolute/path/to/catalog-signing-private.pem \
  --key-id catalog-production-key-id
```

The checked-in manifest is a reproducible contract fixture signed by the clearly marked
`TEST_ONLY` key under `contracts/test-vectors`. Production packaging must always supply its own
catalog-signing key through a secret reference; the fixture private key must never be trusted.
`sign` fills `implementation.digest` of `pending` tools as `SM3(id:entrypoint:version)`.
Verify with `--mode verify` (also run by `scripts/verify-agent-contracts.sh`).

