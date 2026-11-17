#!/usr/bin/env bash
# 生成审批令牌 SM2 密钥对。两个发行版（麒麟 OpenSSL 3.0.12 / Ubuntu OpenSSL 3.0.2）
# 都产出 BouncyCastle 能解析的 id-ecPublicKey + sm2 曲线 PKCS#8。
set -euo pipefail

usage() {
  echo "usage: $0 <output-dir>" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
out="$1"
mkdir -p "$out"

private="$out/approval-private.pem"
public="$out/approval.pub"

openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:SM2 -out "$private"
openssl pkey -in "$private" -pubout -out "$public"
chmod 600 "$private"
chmod 644 "$public"

oid="$(openssl asn1parse -in "$private" | awk '/OBJECT/{print; exit}')"
if ! grep -q 'id-ecPublicKey' <<<"$oid"; then
  echo "refusing to write key: expected algorithm OID id-ecPublicKey, got:" >&2
  echo "  $oid" >&2
  echo "this OpenSSL build produced a backend-incompatible PKCS#8 encoding" >&2
  rm -f "$private" "$public"
  exit 1
fi

echo "wrote $private"
echo "wrote $public"
echo "algorithm: $oid"
