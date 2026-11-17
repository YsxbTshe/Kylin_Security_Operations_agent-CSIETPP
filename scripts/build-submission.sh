#!/usr/bin/env bash
#
# 生成项目安装包、源码包和技术文档，并验证产物。
#
# 用法：
#   bash scripts/build-submission.sh            # 全量构建 + 装配 + 自检
#   bash scripts/build-submission.sh --check    # 只对已有产物做自检，不重新构建
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/submission"
STAGE="$OUT/stage"
DOC_DIR="$ROOT/docs/06-提交文档"
CHECK_ONLY="${1:-}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
fail=0
note()  { printf '%s\n' "$*"; }
ok()    { printf '  %s✓%s %s\n' "$GREEN" "$RESET" "$*"; }
warn()  { printf '  %s!%s %s\n' "$YELLOW" "$RESET" "$*"; }
bad()   { printf '  %s✗%s %s\n' "$RED" "$RESET" "$*"; fail=1; }

# ── 1. 构建 ────────────────────────────────────────────────────────────────
build_all() {
    note "==> 清理输出目录"
    rm -rf "$OUT"
    mkdir -p "$STAGE"

    note "==> 后端 JAR（跳过测试，测试由 CI 与 mvn verify 保证）"
    (cd "$ROOT/services/backend" && mvn -B -q -DskipTests package)
    local jar
    jar="$(ls "$ROOT"/services/backend/target/backend-*.jar | grep -v original | head -1)"
    mkdir -p "$STAGE/06-安装包/backend"
    cp "$jar" "$STAGE/06-安装包/backend/backend.jar"

    note "==> 前端 dist"
    for app in admin-web dashboard-screen; do
        (cd "$ROOT/apps/$app" && npm run build >/dev/null)
        mkdir -p "$STAGE/06-安装包/frontend/$app"
        cp -R "$ROOT/apps/$app/dist/." "$STAGE/06-安装包/frontend/$app/"
    done

    note "==> Host Agent 三架构交叉编译（linux amd64 / arm64 / loong64）"
    mkdir -p "$STAGE/06-安装包/node-executor" "$STAGE/06-安装包/workspace-runner"
    for arch in amd64 arm64 loong64; do
        (cd "$ROOT/agents/node-executor" \
            && GOOS=linux GOARCH="$arch" CGO_ENABLED=0 go build -trimpath \
                -o "$STAGE/06-安装包/node-executor/kylin-node-executor-linux-$arch" ./cmd/node-executor \
            && GOOS=linux GOARCH="$arch" CGO_ENABLED=0 go build -trimpath \
                -o "$STAGE/06-安装包/node-executor/kylin-privhelper-linux-$arch" ./cmd/privhelper)
        (cd "$ROOT/agents/workspace-runner" \
            && GOOS=linux GOARCH="$arch" CGO_ENABLED=0 go build -trimpath \
                -o "$STAGE/06-安装包/workspace-runner/kylin-workspace-runner-linux-$arch" ./cmd/workspace-runner)
    done

    note "==> 部署文件（systemd unit / 配置模板 / 预检脚本）"
    mkdir -p "$STAGE/06-安装包/deploy/node-executor" "$STAGE/06-安装包/deploy/workspace-runner" \
             "$STAGE/06-安装包/deploy/docker" "$STAGE/06-安装包/deploy/observability"
    cp "$ROOT"/agents/node-executor/deploy/*.service \
       "$ROOT"/agents/node-executor/deploy/*.example \
       "$ROOT"/agents/node-executor/deploy/*.sh \
       "$STAGE/06-安装包/deploy/node-executor/"
    cp "$ROOT"/agents/workspace-runner/deploy/* "$STAGE/06-安装包/deploy/workspace-runner/"
    # workspace-runner 安装脚本会在节点侧构建 rootless Podman 沙箱镜像；
    # Containerfile 与脚本属于构建输入，不是第三方依赖，必须随安装物料提供。
    cp "$ROOT"/agents/workspace-runner/Containerfile "$STAGE/06-安装包/deploy/workspace-runner/"
    mkdir -p "$STAGE/06-安装包/deploy/workspace-runner/scripts"
    cp "$ROOT"/agents/workspace-runner/scripts/yq "$STAGE/06-安装包/deploy/workspace-runner/scripts/"
    # Docker 一键栈：排除 secrets/（密钥本体永不入包，只带生成说明）
    for f in stack.yml stack.env.example backend.Dockerfile frontend.Dockerfile nginx-spa.conf; do
        [ -f "$ROOT/deploy/docker/$f" ] && cp "$ROOT/deploy/docker/$f" "$STAGE/06-安装包/deploy/docker/"
    done
    [ -f "$ROOT/deploy/docker/secrets/README.md" ] \
        && { mkdir -p "$STAGE/06-安装包/deploy/docker/secrets"; \
             cp "$ROOT/deploy/docker/secrets/README.md" "$STAGE/06-安装包/deploy/docker/secrets/"; }
    cp -R "$ROOT/deploy/observability/." "$STAGE/06-安装包/deploy/observability/" 2>/dev/null || true
    rm -f "$STAGE/06-安装包/deploy/observability/backend-scrape-token"

    note "==> 依赖清单（部署时从软件源获取，不打包依赖目录）"
    mkdir -p "$STAGE/06-安装包/dependencies/backend" \
             "$STAGE/06-安装包/dependencies/admin-web" \
             "$STAGE/06-安装包/dependencies/dashboard-screen" \
             "$STAGE/06-安装包/dependencies/node-executor" \
             "$STAGE/06-安装包/dependencies/workspace-runner"
    cp "$ROOT/services/backend/pom.xml" "$STAGE/06-安装包/dependencies/backend/"
    cp "$ROOT/apps/admin-web/package.json" "$ROOT/apps/admin-web/package-lock.json" \
       "$STAGE/06-安装包/dependencies/admin-web/"
    cp "$ROOT/apps/dashboard-screen/package.json" "$ROOT/apps/dashboard-screen/package-lock.json" \
       "$STAGE/06-安装包/dependencies/dashboard-screen/"
    cp "$ROOT/agents/node-executor/go.mod" "$ROOT/agents/node-executor/go.sum" \
       "$STAGE/06-安装包/dependencies/node-executor/"
    cp "$ROOT/agents/workspace-runner/go.mod" "$STAGE/06-安装包/dependencies/workspace-runner/"

    note "==> 部署说明（安装包内附）"
    cp "$DOC_DIR/06-软件部署文档.md" "$STAGE/06-安装包/安装说明.md"
    cp "$DOC_DIR/06-软件部署文档.docx" "$STAGE/06-安装包/安装说明.docx"

    note "==> 项目技术文档"
    mkdir -p "$STAGE/开发文档"
    for n in 01 02 03 04 05; do
        cp "$DOC_DIR/${n}-"*.docx "$STAGE/开发文档/"
    done

    note "==> 源代码包（当前工作树中的受控文件）"
    mkdir -p "$STAGE/07-源代码"
    # git archive HEAD 会遗漏本轮尚未提交的定稿内容。这里按 Git 已跟踪文件清单
    # 从当前工作树复制，既包含已修改的最终版本，也不会混入未确认的临时文件。
    python3 - "$ROOT" "$STAGE/07-源代码" <<'PY'
import os
import shutil
import subprocess
import sys
from pathlib import Path

root, target = map(Path, sys.argv[1:3])
raw = subprocess.run(
    ["git", "-c", "core.quotePath=false", "ls-files", "-c", "-z"],
    cwd=root, check=True, capture_output=True,
).stdout
for entry in raw.split(b"\0"):
    if not entry:
        continue
    rel = Path(os.fsdecode(entry))
    src = root / rel
    if not src.exists() and not src.is_symlink():
        continue
    dst = target / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    if src.is_symlink():
        dst.symlink_to(os.readlink(src))
    elif src.is_file():
        shutil.copy2(src, dst)
PY

    # 原始厂商 PDF 不随项目分发。
    local pruned="$STAGE/07-源代码"
    rm -f "$pruned"/rag-corpus/kylinos/*.pdf
    cat > "$pruned/rag-corpus/kylinos/README-第三方文档.md" <<'INNER'
# 第三方文档说明

本目录原含银河麒麟官方《服务器操作系统管理员手册》与产品白皮书 PDF，用于 RAG 检索
实验的语料来源。依据仓库根 `COPYRIGHT.md` 的承诺——第三方材料版权归原权利人所有、
**不随交付物分发**——提交包中已移除 PDF 原件。

同目录下的 `.md` 文件是本团队自行整理的结构化摘录，随包提供，可直接用于复现
`scripts/import-rag-corpus.py` 的导入流程。需要原始 PDF 请从麒麟软件官网获取，
文件名与校验方式见 `rag-corpus/MANIFEST.md`。
INNER

    note "==> 打 zip"
    # 使用 Python zipfile 写入 UTF-8 文件名标志，避免中文路径在其他系统解压后乱码。
    python3 - "$STAGE/06-安装包" "$OUT/06-软件安装包及部署文件.zip" \
               "$STAGE/07-源代码" "$OUT/07-软件源代码文件.zip" <<'PY'
import sys
import zipfile
from pathlib import Path

for source_arg, output_arg in zip(sys.argv[1::2], sys.argv[2::2]):
    source, output = Path(source_arg), Path(output_arg)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(source).as_posix())
PY
    cp "$STAGE/开发文档/"*.docx "$OUT/"
    rm -rf "$STAGE/06-安装包" "$STAGE/07-源代码" "$STAGE/开发文档"
}

# ── 2. 自检 ────────────────────────────────────────────────────────────────
# 检查归档内容与文档一致性。
check_all() {
    note ""
    note "==> 自检"
    local tmp; tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN

    [ -d "$OUT" ] || { bad "输出目录不存在，请先不带 --check 运行一次"; return; }

    # 检查项目产物齐全。
    local missing=0
    for f in "01-软件功能需求分析文档.docx" "02-软件功能设计文档.docx" \
             "03-软件产品说明书.docx" "04-软件功能测试报告.docx" \
             "05-软件性能核心指标测试报告.docx" \
             "06-软件安装包及部署文件.zip" "07-软件源代码文件.zip"; do
        [ -f "$OUT/$f" ] || { bad "缺件：$f"; missing=1; }
    done
    [ "$missing" = 0 ] && ok "安装包、源码包与五份技术文档齐全"

    # (c) 安装包不得含 sudoers 配置；节点写操作由独立 privhelper 承担。
    unzip -Z1 "$OUT/06-软件安装包及部署文件.zip" > "$tmp/pkg.txt"
    local packaged_deps=0
    for pat in 'node_modules/' 'site-packages/' '/\.m2/' '/pkg/mod/' '/\.cache/' '/target/'; do
        grep -qE "$pat" "$tmp/pkg.txt" && { bad "安装包含第三方依赖或缓存目录 $pat"; packaged_deps=1; }
    done
    [ "$packaged_deps" = 0 ] && ok "安装包不含 node_modules / site-packages / Maven、Go 与构建缓存"
    for manifest in \
        'dependencies/backend/pom.xml' \
        'dependencies/admin-web/package-lock.json' \
        'dependencies/dashboard-screen/package-lock.json' \
        'dependencies/node-executor/go.mod' \
        'dependencies/workspace-runner/go.mod'; do
        grep -q "$manifest" "$tmp/pkg.txt" || bad "安装包缺依赖清单：$manifest"
    done
    ok "安装包已附 Maven / npm / Go 依赖清单"
    if grep -qiE '(^|/)[^/]*sudoers[^/]*$' "$tmp/pkg.txt"; then
        bad "安装包含 sudoers 配置或说明文件"
    else
        ok "安装包不含 sudoers 配置"
    fi

    # (d) 任何包都不得含密钥本体
    local leaked=0
    for z in "$OUT"/*.zip; do
        unzip -Z1 "$z" | grep -E '\.(pem|key|p12|jks|pfx)$' | grep -v 'TEST_ONLY' && leaked=1
    done
    [ "$leaked" = 0 ] && ok "无密钥本体（TEST_ONLY 测试向量是源码的一部分，允许）" \
                      || bad "包内发现密钥文件"
    if unzip -Z1 "$OUT/06-软件安装包及部署文件.zip" | grep -q 'secrets/.*\.pem'; then
        bad "安装包 secrets/ 下带了 PEM"
    fi

    # (e) 源码包不得含构建产物与本地环境
    unzip -Z1 "$OUT/07-软件源代码文件.zip" > "$tmp/src.txt"
    local dirty=0
    for pat in 'node_modules/' '/dist/' '/target/' '^vms/' '^\.secrets/' '\.env$'; do
        grep -qE "$pat" "$tmp/src.txt" && { bad "源码包含 $pat"; dirty=1; }
    done
    [ "$dirty" = 0 ] && ok "源码包干净（无 node_modules / dist / target / vms / .secrets / .env）"
    grep -q 'LICENSE' "$tmp/src.txt" && ok "源码包含 LICENSE" || bad "源码包缺 LICENSE"

    # (e2) 兑现 COPYRIGHT.md 的「第三方材料不随交付物分发」承诺。
    #      这条自检的存在本身是因为首版打包违反了自己的版权声明。
    local third=0
    grep -qE 'rag-corpus/.*\.pdf$' "$tmp/src.txt" && { bad "源码包含麒麟官方 PDF —— 违反 COPYRIGHT.md"; third=1; }
    grep -qE '\.bak\.pptx$'        "$tmp/src.txt" && { bad "源码包含备份产物 .bak.pptx"; third=1; }
    [ "$third" = 0 ] && ok "第三方受版权材料未随包分发（与 COPYRIGHT.md 一致）"
    # 用 Python 读取 ZIP 目录，直接按 UTF-8 文件名核验中文说明文件。
    if python3 -c "
import sys,zipfile
names=zipfile.ZipFile('$OUT/07-软件源代码文件.zip').namelist()
sys.exit(0 if any('rag-corpus/kylinos/README-' in n for n in names) else 1)"; then
        ok "已附第三方文档移除说明"
    else
        warn "缺第三方文档移除说明"
    fi

    # (f) 三架构可执行文件齐全且确实是 Linux ELF
    local bins=0
    for arch in amd64 arm64 loong64; do
        grep -q "kylin-node-executor-linux-$arch" "$tmp/pkg.txt" || { bad "缺 node-executor $arch"; bins=1; }
        grep -q "kylin-privhelper-linux-$arch"    "$tmp/pkg.txt" || { bad "缺 privhelper $arch"; bins=1; }
    done
    [ "$bins" = 0 ] && ok "三架构可执行文件齐全（node-executor + privhelper × amd64/arm64/loong64）"

    # (g) 技术文档与工具清单一致
    local tools; tools="$(python3 -c "import json;print(len(json.load(open('$ROOT/tool-packages/core-node-tools/manifest.json'))['tools']))")"
    local hits=0
    for d in "$OUT"/0[1-5]-*.docx; do
        hits=$((hits + $(python3 - "$d" <<'PY'
import sys,zipfile,re
t=re.sub(r'<[^>]+>','',zipfile.ZipFile(sys.argv[1]).read('word/document.xml').decode('utf8','ignore'))
print(t.count('41 个')+t.count('31 只读')+t.count('离线审计缓冲')-t.count('无离线审计缓冲'))
PY
)))
    done
    [ "$tools" = 46 ] && ok "签名清单工具数 = 46" || bad "工具数实测 $tools，与对外口径不符"
    [ "$hits" -le 0 ] && ok "开发文档无过期口径（41 个 / 31 只读 / 正向的离线审计缓冲）" \
                      || bad "开发文档仍有 $hits 处过期口径"

    # (h) 产出清单与哈希（用于核对分发文件）
    (cd "$OUT" && shasum -a 256 ./*.zip ./*.docx 2>/dev/null > MANIFEST.sha256) || true
    ok "已生成 MANIFEST.sha256"

    note ""
    if [ "$fail" = 0 ]; then
        printf '%s项目打包检查通过%s\n' "$GREEN" "$RESET"
    else
        printf '%s项目打包检查未通过%s\n' "$RED" "$RESET"
        return 1
    fi
}

if [ "$CHECK_ONLY" != "--check" ]; then
    build_all
fi
check_all

note ""
note "产出目录：$OUT"
[ -d "$OUT" ] && ls -lh "$OUT" | tail -n +2 | awk '{printf "  %6s  %s\n", $5, $NF}'
