#!/usr/bin/env python3
"""对外材料一致性门禁。

两类检查，任一失败即非零退出：

1. **证据锚点存在性** —— 对外文档里形如 `Xxx.java:120-140`、`V5__foo.sql`、`SomeView.vue`
   的引用，必须能在版本库里命中。

2. **过期口径黑名单** —— 已删除或从未实现的能力名词，不得出现在对外材料中。
   历史包袱（`99-归档/`）、明确记录"为什么删除"的 ADR 与审查报告不在检查范围，
   见 EXEMPT_FILES；这些地方提到旧名词是正当的。

用法：
    python3 scripts/verify-doc-consistency.py            # 检查全部对外材料
    python3 scripts/verify-doc-consistency.py <文件...>   # 只检查指定文件
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 检查仓库中的项目技术文档。
OUTWARD_GLOBS = [
    "README.md",
    "docs/README.md",
    "docs/06-提交文档/*.md",
    "docs/01-架构设计/*.md",
    "docs/02-部署运行/*.md",
    "docs/05-评估方案/*.md",
    "docs/07-学术论文/*.md",
]

# 这些文件的职责就是记录「旧方案长什么样、为什么废弃」，提到黑名单词是正当的。
EXEMPT_FILES = {
    "docs/01-架构设计/ADR-001-以计划门控替代DAG中心编排.md",
    "docs/01-架构设计/ADR-002-特权分离与审批下沉.md",
    "docs/01-架构设计/ADR-003-事件响应闭环与资源级授权与专家注册表.md",
    "docs/01-架构设计/Agent执行架构-现状与设计差距.md",
}

# 引用一个已删除的文件本身没问题——决策记录本来就要讲「我们删掉了什么」。
# 有问题的是**把它当成现存证据**。所以不豁免文件，而是要求该行显式标注已删除；
# 标注了就放行，没标注就失败。这样既保住诚实，又不会把 ADR 变成噪声源。
# 与 scripts/export-submission-docx.sh 的 MARKERS 保持一致——两处检查判据必须同步，
# 否则会出现 .md 绿而 .docx 红（或反之）这种让人无所适从的情况。
DELETION_MARKERS = (
    "已删除", "已移除", "已废弃", "不存在", "已下线", "曾位于", "原位于", "整体移除",
)

# 已删除 / 从未实现的名词 → 应当改用的说法。key 为正则。
STALE_TERMS = {
    r"SafeOpsDagPlanner|SafeOpsPlanner|SafeOps DAG": "已随 commit 21acad3 删除；改述为「计划门控 + function-calling ReAct」",
    r"Rollback Manager": "类已删除；清理场景的回滚见 cleanupcore",
    r"PlanDagChart|SpanWaterfall|HostAgentConsoleView|CheckpointPanel|NaturalLanguageView": (
        "前端组件已随 commit 949b9fa 删除；当前为 AgentRunTimelineView"
    ),
    r"HostAgentCommandService|HostAgentController|HostRollbackService": "已随 commit 21acad3 删除",
    r"离线审计缓冲|Local Audit Buffer": "从未实现；审计以控制面 SM3 哈希链为准",
    r"HMAC-SM3\s*(审批|令牌|token)|(审批|令牌)\s*HMAC": (
        "审批令牌已改为 SM2-with-SM3 非对称签名（口令派生的 PBKDF2-HMAC-SM3 不在此列）"
    ),
    r"sudo\s*-n\s*白名单|sudoers\s*白名单": "sudoers 已按 ADR-002 整体移除；写操作下沉 privhelper",
    r"V1\s*[-–]\s*V41": "迁移已 squash，现为 V1__baseline.sql … V7",
    r"plugins/core-tools\.json": "已随 commit 96ca8c0 删除；权威清单见 tool-packages/core-node-tools/manifest.json",
    r"插件热加载": "运行时插件加载已随 commit 96ca8c0 删除",
    r"观察驱动\s*replan|重规划": "后端无任何 replan 实现",
    r"L1/L2/L3\s*分级根因|分级根因": "代码中从未实现",
    # 「主备模型切换」曾在黑名单里（后端确实没有 failover）。2026-08-15 起
    # SimpleModelRouter.alternatives() + AgentRoundExecutor.invokeModelWithFailover()
    # 已落地 profile 级 failover，该说法现在成立，故移出黑名单。
    r"MCP Inspector": "已随 commit a5ea259 移除",
    r"41\s*个\s*(MCP\s*)?工具|31\s*(个)?只读": "工具口径为 46（32 只读 / 14 写 / 10 强制审批）",
    r"九元组|五元组\s*(绑定|审批|令牌)": "审批令牌绑定 13 个身份字段，见 proto ApprovalClaim",
}

# 提交文档由专家独立评阅，不保留内部整改过程、缺陷盘点或口语化表述。
# 运行条件、配置前提和测试适用范围应使用中立技术语言描述。
SUBMISSION_STYLE_TERMS = {
    r"当前局限|当前限制|已知局限": "改写为运行条件、适用范围或后续演进",
    r"未实现|没有实现|未交付|从未实现": "未交付能力不得进入本版对外功能清单",
    r"悬空|欠账|弱点清单": "删除内部审查与项目管理表述",
    r"如实说明|如实声明|不做掩饰": "直接陈述可验证事实，无需元叙事",
    r"伪向量": "按配置说明具体检索实现与适用范围",
    r"胡说|瞎跑|甩锅|杀手锏|假装工作|更笨的": "改用中立技术表述",
}

# 形如 `Xxx.java:12-34` / `Xxx.vue` / `V5__foo.sql` 的代码引用。
ANCHOR = re.compile(
    r"`([A-Za-z0-9_./\-]+\.(?:java|go|vue|ts|sql|sh|ps1|py|json|yml|yaml|proto))(?::[0-9,\-]+)?`"
)


def tracked_files() -> tuple[set[str], set[str]]:
    """工作树里所有非忽略文件。

    用 ``-co --exclude-standard`` 而不是裸 ``ls-files``：新写的文件在 commit 之前
    是 untracked 的，只查已跟踪集会把「引用了一个刚加的类」误判为失效锚点。
    ``.gitignore`` 忽略的文件仍然排除——文档不该拿本地产物当证据。

    未安装 Git 或在解压后的源码包运行时，直接遍历文件系统并排除构建产物。
    """
    try:
        out = subprocess.run(
            ["git", "-c", "core.quotePath=false", "ls-files", "-co", "--exclude-standard"],
            cwd=ROOT, capture_output=True, text=True, check=True,
        ).stdout.splitlines()
    except (subprocess.CalledProcessError, FileNotFoundError):
        skip_dirs = {
            ".git", "node_modules", "dist", "target", "build", "__pycache__",
            ".gocache", "vms", ".secrets", ".tools", ".idea", ".vscode",
        }
        out = []
        for dirpath, dirnames, filenames in os.walk(ROOT):
            dirnames[:] = [d for d in dirnames if d not in skip_dirs]
            for name in filenames:
                rel = os.path.relpath(os.path.join(dirpath, name), ROOT)
                out.append(rel.replace(os.sep, "/"))
    return set(out), {os.path.basename(p) for p in out}


def collect_targets(argv: list[str]) -> list[Path]:
    if argv:
        return [Path(a) if Path(a).is_absolute() else ROOT / a for a in argv]
    found: list[Path] = []
    for pattern in OUTWARD_GLOBS:
        found.extend(sorted(ROOT.glob(pattern)))
    return found


def main(argv: list[str]) -> int:
    paths, basenames = tracked_files()
    targets = collect_targets(argv)
    anchor_failures: list[str] = []
    term_failures: list[str] = []
    anchors_checked = 0

    for path in targets:
        if not path.exists():
            continue
        rel = path.relative_to(ROOT).as_posix()
        exempt = rel in EXEMPT_FILES
        for lineno, line in enumerate(path.read_text(encoding="utf8").splitlines(), 1):
            annotated = any(marker in line for marker in DELETION_MARKERS)
            # 匹配前抹掉 markdown 行内标记：`sudo -n` 白名单 这种写法能骗过朴素正则，
            # 但 pandoc 导出 .docx 时反引号会消失，过期口径就原形毕露了。
            # 两处检查必须看同一份文本，否则 .md 绿而 .docx 红。
            plain = re.sub(r"[`*_~]", "", line)
            for match in ANCHOR.finditer(line):
                ref = match.group(1)
                anchors_checked += 1
                if ref in paths or os.path.basename(ref) in basenames or (ROOT / ref).exists():
                    continue
                if annotated:
                    continue  # 本行已说明该文件不复存在，属如实叙述
                anchor_failures.append(
                    f"{rel}:{lineno}  引用不存在的文件: {ref}"
                    f"（若在讲历史，请在同一行注明「已删除」等字样）"
                )
            # 同一行已注明「已删除/已移除…」时，出现旧名词是**否定式陈述**
            # （「sudoers 白名单已按 ADR-002 整体移除」），这正是我们希望文档写的话，
            # 不该判违规。逃生舱对锚点与口径两类检查一视同仁。
            if exempt or annotated:
                continue
            for pattern, guidance in STALE_TERMS.items():
                hit = re.search(pattern, plain)
                if hit:
                    term_failures.append(f"{rel}:{lineno}  过期口径 “{hit.group(0)}” → {guidance}")
            if rel.startswith("docs/06-提交文档/"):
                for pattern, guidance in SUBMISSION_STYLE_TERMS.items():
                    hit = re.search(pattern, plain)
                    if hit:
                        term_failures.append(
                            f"{rel}:{lineno}  非标准提交表述 “{hit.group(0)}” → {guidance}"
                        )

    print(f"检查 {len(targets)} 份对外材料，{anchors_checked} 个代码引用。")
    if anchor_failures:
        print(f"\n❌ 失效证据锚点 {len(anchor_failures)} 处：")
        for item in anchor_failures:
            print(f"   {item}")
    if term_failures:
        print(f"\n❌ 过期口径 {len(term_failures)} 处：")
        for item in term_failures:
            print(f"   {item}")
    if anchor_failures or term_failures:
        print("\n对外材料必须与代码一致。修正后重跑本脚本。")
        return 1
    print("✅ 全部通过：证据锚点均可命中，无过期口径。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
