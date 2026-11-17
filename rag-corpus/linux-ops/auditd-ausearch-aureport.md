---
title: auditd / ausearch / aureport — Linux Audit daemon and analysis tools (upstream man pages)
publisher: linux-audit project / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man8/auditd.8.html
source_urls:
  - https://man7.org/linux/man-pages/man8/auditd.8.html
  - https://man7.org/linux/man-pages/man8/ausearch.8.html
  - https://man7.org/linux/man-pages/man8/aureport.8.html
retrieved: 2026-06-14
language: en
license: Upstream Linux man-pages (linux-audit, GPL). Used as-is in 银河麒麟.
upstream: true
topic: 审计 auditd / ausearch / aureport / 审计日志分析
note: UPSTREAM man pages consolidated. The Linux Audit framework ships unchanged in Kylin; sysadmin manual §8.1.2 (审计管理) documents auditd, auditctl, aureport, ausearch identically.
---

# Linux Audit toolset: auditd / ausearch / aureport (UPSTREAM — used as-is in Kylin)

> Upstream source: man7.org. Kylin sysadmin manual §8.1.2 (审计管理: §8.1.2.6 守护进程, §8.1.2.7 aureport, §8.1.2.8 ausearch) documents the same tools.

## auditd(8) — Audit daemon

**auditd** is the userspace component of the Linux Auditing System, responsible for writing audit records to disk. Companion utilities **ausearch** and **aureport** view logs; **auditctl** manages configuration.

At startup auditd loads rules from `/etc/audit/audit.rules` or rules compiled by **augenrules** from `/etc/audit/rules.d/`. The daemon is configured via **auditd.conf**.

### Signal handling
- **SIGHUP** — Reread config (reconfigure)
- **SIGTERM** — Stop processing, log shutdown event, exit
- **SIGUSR1** — Rotate logs immediately
- **SIGUSR2** — Resume logging/plugin processing after suspension
- **SIGCONT** — Dump internal state to `/run/audit/auditd.state`

### Key files
- `/etc/audit/auditd.conf` — daemon configuration
- `/etc/audit/audit.rules` — rules loaded at startup
- `/etc/audit/rules.d/` — individual rule sets (compiled by augenrules)
- `/etc/audit/plugins.d/` — plugin configurations
- `/etc/audit/audit-stop.rules` — rules loaded during shutdown
- `/var/log/audit/audit.log` — default audit log location

Boot parameter `audit=1` is recommended so all early-boot processes are marked auditable.

---

## ausearch(8) — Query audit logs

`ausearch` queries the audit daemon logs for events by various criteria, correlating related records by unique event ID and presenting complete events together.

- **-m, --message** — By record type (SYSCALL, PATH, AVC, ...); comma-separated or repeated.
- **-ts, --start** — Events at/after timestamp (default: midnight today). Also `boot`, `today`, `yesterday`, `now`.
- **-te, --end** — Events at/before timestamp (default: now).
- **-ui, --uid** — By user ID.
- **-ul, --loginuid** — By login session user (auid).
- **-ua, --uid-all** — Match any uid/euid/loginuid.
- **-k, --key** — By audit rule key string.
- **-p, --pid** — By process ID.
- **-f, --file** — By filename.
- **-x, --executable** — By executable name.
- **-sc, --syscall** — By syscall name/number.
- **--success** — Restrict to successful (yes) or failed (no) events.
- **-i, --interpret** — Human-readable values (UIDs→names, syscall numbers→names).

---

## aureport(8) — Summarize audit logs

`aureport` generates columnar summary reports from audit logs (with event numbers for follow-up `ausearch`).

| Option | Purpose |
|--------|---------|
| `--summary` | Aggregate totals of main report elements |
| `-au, --auth` | Authentication attempt statistics |
| `-l, --login` | Login activity analysis |
| `-f, --file` | File / AF_UNIX socket access reports |
| `-u, --user` | User-based event summaries |
| `-x, --executable` | Executable program activity |
| `-s, --syscall` | System call invocation data |
| `-m, --mods` | Account modification events |

Time filtering: `-ts/--start`, `-te/--end` (accept `now`, `boot`, `today`, `yesterday`, or dates). Modifiers: `-i/--interpret`, `-if/--input file|dir`.
