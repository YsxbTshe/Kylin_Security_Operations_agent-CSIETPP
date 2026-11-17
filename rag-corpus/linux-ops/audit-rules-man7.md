---
title: audit.rules(7) — Audit rules file format (upstream man page)
publisher: linux-audit project / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man7/audit.rules.7.html
retrieved: 2026-06-14
language: en
license: Upstream Linux man-page (linux-audit, GPL). Used as-is in 银河麒麟 audit framework.
upstream: true
topic: 审计 audit.rules / 审计规则
note: UPSTREAM man page. /etc/audit/audit.rules and /etc/audit/rules.d/ are used unchanged in Kylin; Kylin sysadmin manual §8.1.2.5 (audit 审计规则) documents the same rule syntax.
---

# audit.rules(7) — Audit rules file format (UPSTREAM — used as-is in Kylin)

> Upstream source: man7.org. Kylin sysadmin manual §8.1.2.5 (audit 审计规则) and §8.2.5.3.5 (规则配置) document the same audit rule syntax.

## Overview
The `audit.rules` file contains audit rules loaded by the kernel audit system through the audit daemon's init script. Rules follow the same syntax as `auditctl` commands at the shell prompt.

## Rule Categories

### Control Rules
These configure the audit system itself rather than defining monitoring targets. They typically involve deleting rules, configuring kernel backlog queue sizes, setting failure modes, adjusting event rate limits, or instructing auditctl to ignore syntax errors.

### File System Rules (Watches)
These audit access to specific files or directories. When targeting a directory, rules recursively apply down the directory tree, excluding mount points. The general format is:

```
-a always,exit -F arch=b64 -F path=path-to-file -F perm=permissions -F key=keyname
```

Permission types include:
- **r** (read)
- **w** (write)
- **x** (execute)
- **a** (attribute changes)

Specifying the `arch` field improves performance by avoiding evaluation of irrelevant syscalls.

### System Call Rules
These intercept syscalls system-wide through a matching engine. They should be used sparingly due to performance implications. The basic structure is:

```
-a action,list -S syscall -F field=value -k keyname
```

Valid actions are "always" (create event) or "never" (suppress event). Valid rule lists include: task, exit, user, exclude, filesystem, and io_uring.

## Key Practices
Using descriptive key fields helps during investigations. Related rules sharing a common key enable targeted analysis with tools like `aureport` and `ausearch`.

## IO_URING Rules
These monitor syscalls performed through io_uring operations and don't require an arch field:

```
-a always,io_uring -S openat,openat2 -F key=access
```

## Hard-Wired Events
Certain events (ranges 1100-1299, 1326, 1328, 1331+) emit automatically for regulatory compliance, including login/logoff events. Use the exclude filter to suppress unwanted events.
