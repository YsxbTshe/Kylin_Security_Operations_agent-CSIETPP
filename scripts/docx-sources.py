#!/usr/bin/env python3
"""Track the exact Markdown and Word bytes used by the document exporter."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


def hashes(directory: Path) -> dict[str, dict[str, str]]:
    documents = {}
    for source in sorted(directory.glob("*.md")):
        output = source.with_suffix(".docx")
        if output.is_file():
            documents[source.name] = {
                "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "docx_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            }
    if not documents:
        raise ValueError("未找到 Markdown / Word 文档对")
    return documents


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--record", action="store_true")
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    manifest = args.directory / "docx-sources.json"
    try:
        current = hashes(args.directory)
        if args.record:
            data = {"schema_version": 1, "documents": current}
            temporary = manifest.with_suffix(".json.tmp")
            temporary.write_text(
                json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )
            temporary.replace(manifest)
            print(f"✅ 已记录 {len(current)} 份 Word 文档的源文件和输出哈希")
            return 0

        saved = json.loads(manifest.read_text(encoding="utf-8"))
        if (not isinstance(saved, dict) or saved.get("schema_version") != 1
                or not isinstance(saved.get("documents"), dict)):
            raise ValueError("Word 文档哈希清单格式错误")
        recorded = saved["documents"]
        failures = []
        for name in sorted(recorded.keys() | current.keys()):
            if recorded.get(name) != current.get(name):
                failures.append(name)
        if failures:
            raise ValueError("源文件或 Word 输出已变化：" + "、".join(failures))
    except (OSError, ValueError) as exc:
        print(f"❌ Word 文档导出校验失败：{exc}", file=sys.stderr)
        print("请运行 bash scripts/export-submission-docx.sh 重新导出并提交哈希清单。", file=sys.stderr)
        return 1
    print(f"✅ {len(current)} 份 Word 文档与已记录的 Markdown 源文件一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
