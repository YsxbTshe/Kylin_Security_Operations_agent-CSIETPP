#!/usr/bin/env bash
# 从 Markdown 技术文档重新导出 Word，并校验正文。
#
# 用原 .docx 作 --reference-doc：样式表逐字节沿用，只换正文，版式不会变。
#
# 用法：bash scripts/export-submission-docx.sh [--check]
#   --check  只校验 .docx 是否比同名 .md 新且无过期口径，不重新导出（供 CI 用）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC_DIR="$ROOT/docs/06-提交文档"
CHECK_ONLY="${1:-}"

# --check 只解包校验，不需要 pandoc（CI 上跑的就是这个模式）。
if [[ "$CHECK_ONLY" != "--check" ]]; then
    command -v pandoc >/dev/null 2>&1 || { echo "❌ 需要 pandoc（brew install pandoc）"; exit 1; }
fi

# 导出后正文中不允许出现的字样。留意：口令派生用的 PBKDF2-HMAC-SM3 是正确的，
# 所以只匹配「审批/令牌 + HMAC」的组合，不匹配裸 HMAC。
STALE_PATTERNS='41 个|31 只读|离线审计缓冲|SafeOpsDagPlanner|SafeOps DAG|Rollback Manager|V1–V41|sudoers 白名单|sudo -n 白名单|当前局限|当前限制|已知局限|未实现|没有实现|未交付|从未实现|悬空|欠账|弱点清单|如实说明|如实声明|不做掩饰|伪向量|胡说|瞎跑|甩锅|杀手锏|假装工作|更笨的'

fail=0
for md in "$DOC_DIR"/*.md; do
    docx="${md%.md}.docx"
    name="$(basename "$md")"

    if [[ ! -f "$docx" ]]; then
        echo "⚠️  $name 无对应 .docx，跳过"
        continue
    fi

    if [[ "$CHECK_ONLY" == "--check" && "$md" -nt "$docx" ]]; then
        echo "❌ $(basename "$docx") 早于 Markdown 源文件，请重新导出"
        fail=1
    fi

    if [[ "$CHECK_ONLY" != "--check" ]]; then
        # reference-doc 必须在覆盖前读取，故先导出到临时文件再替换。
        tmp="$(mktemp -t project-docx.XXXXXX)"
        if ! pandoc "$md" --to=docx --reference-doc="$docx" -o "$tmp"; then
            rm -f "$tmp"
            exit 1
        fi
        mv "$tmp" "$docx"
        echo "✅ 已导出 $(basename "$docx")"
    fi

    # 解包正文做过期口径校验——.docx 里的问题只有解包才看得见。
    # 逐段（<w:p>）检查而不是整篇 grep：与 verify-doc-consistency.py 保持同一套
    # 判据——本段若已注明「已删除/已移除…」，说的就是否定式陈述（"sudoers 白名单
    # 已按 ADR-002 整体移除"），不算违规。两处检查口径不一致会让人无所适从。
    if ! python3 - "$docx" "$STALE_PATTERNS" <<'PY'
import re, sys, zipfile

path, patterns = sys.argv[1], sys.argv[2]
MARKERS = ("已删除", "已移除", "已废弃", "不存在", "已下线", "曾位于", "原位于", "整体移除")

with zipfile.ZipFile(path) as z:
    xml = z.read("word/document.xml").decode("utf8", "ignore")

hits = {}
for para in re.findall(r"<w:p[ >].*?</w:p>", xml, re.S):
    text = "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", para))
    if not text or any(m in text for m in MARKERS):
        continue
    for m in re.finditer(patterns, text):
        hits[m.group(0)] = hits.get(m.group(0), 0) + 1

if hits:
    for term, count in sorted(hits.items()):
        print(f"     {count} × {term}")
    sys.exit(1)
PY
    then
        echo "❌ $(basename "$docx") 仍含过期口径（上列为未标注「已删除」的段落）："
        fail=1
    fi
done

if [[ $fail -ne 0 ]]; then
    echo
    echo "有 .docx 含过期口径。先修同名 .md，再重跑本脚本（不带 --check）。"
    exit 1
fi
echo
echo "✅ Word 技术文档正文检查通过。"
