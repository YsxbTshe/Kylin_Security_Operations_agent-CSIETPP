#!/usr/bin/env bash
# 生成 CycloneDX SBOM（软件物料清单），覆盖后端 Java、两个 Go module、三个前端 app。
#
# 为什么是脚本而不是已提交的 SBOM 文件：
#   SBOM 必须由工具从真实依赖闭包解析生成。编制本脚本时，开发机上
#   cyclonedx-npm / syft / cdxgen 均不可用且处于离线环境，手写一份 SBOM
#   等于伪造供应链证据。因此这里留下可执行路径，请在**有网环境**执行本脚本
#   并把产物提交到 sbom/ 目录。
#
# 前置工具（缺哪个装哪个，本脚本会逐一探测并跳过缺失项）：
#   Java   : cyclonedx-maven-plugin —— 无需预装，Maven 会按坐标下载
#   Go     : go install github.com/CycloneDX/cyclonedx-gomod/cmd/cyclonedx-gomod@latest
#   npm    : npm install -g @cyclonedx/cyclonedx-npm
#   兜底/全仓: syft (https://github.com/anchore/syft) 或 cdxgen (npm i -g @cyclonedx/cdxgen)
#
# 用法：
#   ./scripts/generate-sbom.sh            # 生成到 sbom/
#   SBOM_OUT=/tmp/sbom ./scripts/generate-sbom.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${SBOM_OUT:-$ROOT/sbom}"
CDX_SPEC="${CDX_SPEC:-1.6}"
CDX_MAVEN_PLUGIN="org.cyclonedx:cyclonedx-maven-plugin:2.9.1"

mkdir -p "$OUT"
generated=0
skipped=()

have() { command -v "$1" >/dev/null 2>&1; }

echo "==> SBOM 输出目录: $OUT (CycloneDX spec $CDX_SPEC)"

# ---------- 1. 后端 Java ----------
if have mvn; then
  echo "==> [java] services/backend"
  (
    cd "$ROOT/services/backend"
    mvn -B "$CDX_MAVEN_PLUGIN:makeAggregateBom" \
      -DoutputDirectory="$OUT" \
      -DoutputName=backend-java \
      -DschemaVersion="$CDX_SPEC" \
      -DoutputFormat=json
  )
  generated=$((generated + 1))
else
  skipped+=("java: 未找到 mvn")
fi

# ---------- 2. Go modules ----------
if have cyclonedx-gomod; then
  for m in node-executor workspace-runner; do
    echo "==> [go] agents/$m"
    cyclonedx-gomod mod \
      -json \
      -licenses \
      -std \
      -output "$OUT/go-$m.cdx.json" \
      "$ROOT/agents/$m"
    generated=$((generated + 1))
  done
else
  skipped+=("go: 未找到 cyclonedx-gomod（go install github.com/CycloneDX/cyclonedx-gomod/cmd/cyclonedx-gomod@latest）")
fi

# ---------- 3. 前端 npm ----------
if have cyclonedx-npm; then
  for a in admin-web dashboard-screen concept-lab; do
    echo "==> [npm] apps/$a"
    ( cd "$ROOT/apps/$a" && npm ci --ignore-scripts )
    cyclonedx-npm \
      --spec-version "$CDX_SPEC" \
      --output-format JSON \
      --output-file "$OUT/npm-$a.cdx.json" \
      "$ROOT/apps/$a/package-lock.json"
    generated=$((generated + 1))
  done
else
  skipped+=("npm: 未找到 cyclonedx-npm（npm install -g @cyclonedx/cyclonedx-npm）")
fi

# ---------- 4. 全仓兜底（syft / cdxgen）----------
if have syft; then
  echo "==> [syft] 全仓聚合 SBOM"
  syft scan "dir:$ROOT" -o "cyclonedx-json=$OUT/repo-syft.cdx.json"
  generated=$((generated + 1))
elif have cdxgen; then
  echo "==> [cdxgen] 全仓聚合 SBOM"
  cdxgen -r -o "$OUT/repo-cdxgen.cdx.json" "$ROOT"
  generated=$((generated + 1))
else
  skipped+=("全仓聚合: 未找到 syft / cdxgen")
fi

echo
echo "==> 已生成 $generated 份 SBOM:"
ls -1 "$OUT" 2>/dev/null | sed 's/^/    /' || true

if [ ${#skipped[@]} -gt 0 ]; then
  echo
  echo "==> 跳过（工具缺失）:"
  printf '    - %s\n' "${skipped[@]}"
fi

if [ "$generated" -eq 0 ]; then
  echo
  echo "错误：没有任何 SBOM 被生成。请先按上方提示安装工具（需要网络）。" >&2
  exit 1
fi
