#!/usr/bin/env python3
"""把 rag-corpus/ 的权威语料导入平台知识库。

为什么需要这个脚本：`rag-corpus/` 里的 22 篇麒麟/Linux 官方文档此前只被基准测试
(`RagBenchSupport.java`) 使用，没有任何生产导入路径，导致实际运行的知识库里只有
几篇测试文档，混合检索的关键词与图谱两路检不出任何候选。

切分策略：知识库单文档上限 12000 字符，而语料单文件最大 408KB，因此按 markdown
的二级标题切段；过小的段落向后合并，过大的段落按空行再切。每段都保留来源标题，
使关键词通道有可匹配的标题，图谱重建也有实体可抽。

用法：
    python3 scripts/import-rag-corpus.py --base-url http://127.0.0.1:18080 \
        --username admin --password admin123

    # 只看会导入什么，不实际写入
    python3 scripts/import-rag-corpus.py --dry-run
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

MAX_CONTENT = 12000
MAX_TITLE = 255
# 小于这个长度的段落并入下一段，避免产生大量只有一行标题的碎片文档
MIN_SECTION = 400

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
CORPUS_DIR = REPO_ROOT / "rag-corpus"

# 目录 → sourceType，便于在界面上按来源筛选并在答辩时说明权威性
SOURCE_TYPES = {
    "kylinos": "KYLIN_OFFICIAL",
    "openkylin": "OPENKYLIN_OFFICIAL",
    "kysec-security": "KYLIN_SECURITY",
    "linux-ops": "UPSTREAM_MANPAGE",
}


def parse_front_matter(text: str) -> tuple[dict[str, str], str]:
    """取出 YAML front-matter（仅需扁平的 key: value），返回 (元数据, 正文)。"""
    if not text.startswith("---"):
        return {}, text
    end = text.find("\n---", 3)
    if end == -1:
        return {}, text
    raw = text[3:end]
    body = text[end + 4 :].lstrip("\n")
    meta: dict[str, str] = {}
    for line in raw.splitlines():
        if ":" in line and not line.startswith(" "):
            key, _, value = line.partition(":")
            meta[key.strip()] = value.strip()
    return meta, body


def split_by_marker(body: str, marker: str) -> list[tuple[str, str]]:
    lines = body.splitlines()
    sections: list[tuple[str, list[str]]] = []
    current_title = ""
    current: list[str] = []
    for line in lines:
        if line.startswith(marker):
            if current:
                sections.append((current_title, current))
            current_title = line[len(marker) :].strip()
            current = [line]
        else:
            current.append(line)
    if current:
        sections.append((current_title, current))
    return [(title, "\n".join(chunk).strip()) for title, chunk in sections if "\n".join(chunk).strip()]


def split_sections(body: str) -> list[tuple[str, str]]:
    """按标题切段，返回 [(段落标题, 段落正文)]。

    优先二级标题；部分语料（如桌面版 FAQ）整篇只有一级标题下挂三级标题，
    此时退到 ### 或 #，否则整篇会被当成一段再被硬切成无标题碎片。
    """
    for marker in ("## ", "### ", "# "):
        sections = split_by_marker(body, marker)
        # 切出多段才算这一级标题有效
        if len(sections) > 1:
            return sections
    return split_by_marker(body, "## ")


def merge_small(sections: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """把过短的段落并入下一段，减少碎片。"""
    merged: list[tuple[str, str]] = []
    buffer_title, buffer_text = "", ""
    for title, text in sections:
        if buffer_text:
            candidate = f"{buffer_text}\n\n{text}"
            if len(candidate) <= MAX_CONTENT:
                buffer_title = buffer_title or title
                buffer_text = candidate
                if len(buffer_text) >= MIN_SECTION:
                    merged.append((buffer_title, buffer_text))
                    buffer_title, buffer_text = "", ""
                continue
            merged.append((buffer_title, buffer_text))
            buffer_title, buffer_text = "", ""
        if len(text) < MIN_SECTION:
            buffer_title, buffer_text = title, text
        else:
            merged.append((title, text))
    if buffer_text:
        merged.append((buffer_title, buffer_text))
    return merged


def hard_split(title: str, text: str) -> list[tuple[str, str]]:
    """段落仍超长时按空行硬切，标题加 (1/n) 后缀保持可追溯。"""
    if len(text) <= MAX_CONTENT:
        return [(title, text)]
    parts: list[str] = []
    current = ""
    for para in text.split("\n\n"):
        if len(current) + len(para) + 2 > MAX_CONTENT and current:
            parts.append(current)
            current = para
        else:
            current = f"{current}\n\n{para}" if current else para
    if current:
        parts.append(current)
    # 单个段落本身就超长（罕见，如超大表格）时按字符截断
    final: list[str] = []
    for part in parts:
        while len(part) > MAX_CONTENT:
            final.append(part[:MAX_CONTENT])
            part = part[MAX_CONTENT:]
        if part:
            final.append(part)
    total = len(final)
    return [(f"{title} ({i + 1}/{total})", part) for i, part in enumerate(final)]


def build_documents() -> list[dict[str, str]]:
    docs: list[dict[str, str]] = []
    for path in sorted(CORPUS_DIR.rglob("*.md")):
        if path.name == "MANIFEST.md":
            continue
        meta, body = parse_front_matter(path.read_text(encoding="utf-8"))
        doc_title = meta.get("title") or path.stem
        source_type = SOURCE_TYPES.get(path.parent.name, "CORPUS")
        for section_title, text in merge_small(split_sections(body)):
            # 先拼出完整标题再做硬切，否则 (1/n) 后缀会落在段落标题上，
            # 拼接后出现 "文档标题 —  (1/3)" 这种中间空一截的标题。
            full_title = f"{doc_title} — {section_title}" if section_title else doc_title
            for piece_title, piece in hard_split(full_title, text):
                docs.append(
                    {
                        "title": piece_title[:MAX_TITLE],
                        "sourceType": source_type,
                        "content": piece,
                        "_file": str(path.relative_to(REPO_ROOT)),
                    }
                )
    return docs


def request(base_url: str, path: str, method: str = "GET", token: str = "", payload: dict | None = None):
    url = f"{base_url.rstrip('/')}{path}"
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--rebuild-graph", action="store_true", help="导入后触发知识图谱重建")
    args = parser.parse_args()

    docs = build_documents()
    total_chars = sum(len(d["content"]) for d in docs)
    print(f"待导入 {len(docs)} 个文档片段，合计 {total_chars:,} 字符")
    by_type: dict[str, int] = {}
    for doc in docs:
        by_type[doc["sourceType"]] = by_type.get(doc["sourceType"], 0) + 1
    for source_type, count in sorted(by_type.items()):
        print(f"  {source_type:22} {count} 段")

    if args.dry_run:
        print("\n--dry-run：未写入。示例：")
        for doc in docs[:3]:
            print(f"  [{doc['sourceType']}] {doc['title'][:80]}  ({len(doc['content'])} 字符)")
        return 0

    login = request(args.base_url, "/api/v1/auth/login", "POST", payload={
        "username": args.username,
        "password": args.password,
    })
    token = login["data"]["token"]

    existing = request(args.base_url, "/api/v1/rag/documents", token=token)["data"]
    existing_titles = {item["title"] for item in existing}
    print(f"\n知识库已有 {len(existing)} 个文档，同名的将跳过")

    created = skipped = failed = 0
    for index, doc in enumerate(docs, 1):
        if doc["title"] in existing_titles:
            skipped += 1
            continue
        payload = {"title": doc["title"], "sourceType": doc["sourceType"], "content": doc["content"]}
        try:
            request(args.base_url, "/api/v1/rag/documents", "POST", token=token, payload=payload)
            created += 1
        except urllib.error.HTTPError as error:
            failed += 1
            print(f"  失败 [{index}/{len(docs)}] {doc['title'][:60]}: HTTP {error.code} {error.read()[:200]}")
        if index % 25 == 0:
            print(f"  进度 {index}/{len(docs)}（新增 {created} / 跳过 {skipped} / 失败 {failed}）")

    print(f"\n完成：新增 {created}，跳过 {skipped}，失败 {failed}")

    if args.rebuild_graph:
        print("触发知识图谱重建…")
        request(args.base_url, "/api/v1/rag/graph/rebuild", "POST", token=token)
        stats = request(args.base_url, "/api/v1/rag/graph/stats", token=token)["data"]
        print(f"图谱统计：{stats}")

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
