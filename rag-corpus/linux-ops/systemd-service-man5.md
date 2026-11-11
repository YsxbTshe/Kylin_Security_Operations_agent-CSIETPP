---
title: systemd.service(5) — Service unit configuration (upstream man page)
publisher: systemd project / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man5/systemd.service.5.html
retrieved: 2026-06-14
language: en
license: Upstream systemd man-page (LGPL). Used as-is in 银河麒麟.
upstream: true
topic: 服务与进程管理 systemd / .service unit
note: UPSTREAM man page (core reference). Service unit files are written/managed identically in Kylin; Kylin sysadmin manual §5.1 covers systemd service management.
---

# systemd.service(5) — Service unit configuration (UPSTREAM — used as-is in Kylin)

> Upstream source: man7.org. Kylin sysadmin manual §5.1 (使用 systemd 管理系统服务) covers writing and managing `.service` units.

## Overview
Service unit files (`.service`) encode information about processes controlled and supervised by systemd. Configuration spans `[Unit]`, `[Service]`, and `[Install]` sections, with service-specific options in `[Service]`.

## Type= Directive
Configures how systemd recognizes service startup completion:
- **simple** (default) — Started immediately after fork, before `execve()`. For long-running processes with socket activation.
- **exec** — Like simple but waits for `fork()` + `execve()` success. Recommended for long-running services (tracks setup errors).
- **forking** — Started after the parent exits; child continues as main process. Discouraged; pairs with `PIDFile=`.
- **oneshot** — Process exits after completion; stays active if `RemainAfterExit=yes`. Allows multiple sequential `ExecStart=`.
- **dbus** — Like simple but requires `BusName=`; started when bus name is acquired.
- **notify/notify-reload** — Service sends `READY=1` via `sd_notify()` when initialized; `notify-reload` also handles config reloads via signal + `RELOADING=1`.
- **idle** — Delays execution until active jobs dispatched (cleaner console ordering).

## Execution Directives
- **ExecStart=** — Primary command(s); must succeed unless prefixed `-`. For non-oneshot types, exactly one command required.
- **ExecStartPre= / ExecStartPost=** — Run before/after `ExecStart=`. Multiple allowed; failures block subsequent unless prefixed `-`.
- **ExecReload=** — Reload configuration; `$MAINPID` available.
- **ExecStop=** — Graceful shutdown; only runs if service started successfully; should await completion.
- **ExecStopPost=** — Runs regardless of outcome; for cleanup.

## Restart= Policy
- **no** (default) — No restart
- **on-success** — Clean exits only (code 0 or SIGHUP/SIGINT/SIGTERM/SIGPIPE)
- **on-failure** — Non-zero exits, abnormal signals, timeouts
- **on-abnormal** — Abnormal signals, timeouts
- **on-watchdog** — Watchdog timeout only
- **on-abort** — Uncaught signals
- **always** — Unconditional restart

(oneshot services reject `always` and `on-success`.)

## Command Line Prefixes
`@` custom argv[0]; `-` ignore non-zero exit; `:` suppress variable expansion; `+`/`!` elevated privileges; `|` invoke default shell. Supports `${VAR}` / `$VAR` substitution.

## Other Key Options
`RemainAfterExit=`, `PIDFile=`, `BusName=`, `TimeoutStartSec=`, `TimeoutStopSec=`, `RuntimeMaxSec=`, `WatchdogSec=`, `NotifyAccess=`, `KillMode=`, `User=`, `Group=`.
