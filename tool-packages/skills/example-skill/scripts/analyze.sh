#!/bin/bash
# 磁盘告警只读诊断脚本：只采集、不修改任何文件。
set -euo pipefail

echo "== 分区使用率 =="
df -hP

echo
echo "== inode 使用率 =="
df -iP

echo
echo "== 挂载点拓扑 =="
lsblk -o NAME,SIZE,TYPE,MOUNTPOINT,FSTYPE 2>/dev/null || findmnt

echo
echo "== 使用率最高分区的 Top 10 大目录（深度 3） =="
TARGET="$(df -P | awk 'NR>1 && $5+0 > 0 {print $6" "$5}' | sort -k2 -rn | head -1 | awk '{print $1}')"
if [ -n "${TARGET:-}" ]; then
    echo "target: $TARGET"
    du -xh -d 3 "$TARGET" 2>/dev/null | sort -rh | head -10
fi
