---
title: systemctl(1) — Control the systemd system and service manager (upstream man page)
publisher: systemd project / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man1/systemctl.1.html
retrieved: 2026-06-14
language: en
license: Upstream systemd man-page (LGPL). systemd/systemctl are used as-is in 银河麒麟 (see Kylin sysadmin manual §5.1 使用 systemd 管理系统服务).
upstream: true
topic: 服务与进程管理 systemd / systemctl
note: UPSTREAM man page (core commands). systemd is the init/service manager in Kylin; the Kylin sysadmin manual §5.1 documents the same systemctl commands.
---

# systemctl(1) — systemd service manager (UPSTREAM — used as-is in Kylin)

> Upstream source: man7.org. Kylin sysadmin manual §5.1 (使用 systemd 管理系统服务) documents these same commands.

## Description
systemctl is used to introspect and control the state of the systemd system and service manager. It enables administrators to manage services, check their status, and control system state.

## Core Unit Commands

### Service Lifecycle Management

- **start** *PATTERN* — Activates one or more units. Glob patterns have limited usefulness here since inactive units typically aren't in memory.
- **stop** *PATTERN* — Deactivates one or more units. Fails if stopping is prohibited by unit config; the manager forcibly terminates the unit even if configured stop commands fail.
- **restart** *PATTERN* — Stops then starts units; starts them if not running. For full resource cleanup, issue explicit stop then start.
- **reload** *PATTERN* — Requests units to reload their service-specific configuration (the application config, e.g. httpd.conf — not the systemd unit file).
- **try-restart** *PATTERN* — Restarts units only if currently running; does nothing if inactive.

### Enablement Management

- **enable** *UNIT* — Creates symlinks per the [Install] section of unit files and reloads the manager. Does not start units — combine with `--now` to activate immediately.
- **disable** *UNIT* — Removes the symlinks, undoing enable.
- **is-enabled** *UNIT* — Checks whether units are enabled; exit 0 if at least one is.
- **mask** *UNIT* — Links unit files to /dev/null, making them impossible to start (including manual starts).
- **unmask** *UNIT* — Reverses masking.

### Status and Introspection

- **status** [*PATTERN*|*PID*] — Shows runtime status and recent log data: unit state, main PID, resource usage, and journal entries. With no args, shows system status.
- **is-active** *PATTERN* — Checks whether units are running; exit 0 if at least one is active.
- **list-units** [*PATTERN*] — Lists units in memory with load/active state and description. Shows active units by default; `--all` includes inactive.
- **list-unit-files** [*PATTERN*] — Lists installed unit files with enablement states, including template units (e.g. foo@.service).

### System Manager Commands

- **daemon-reload** — Reloads systemd manager configuration, reruns generators, reloads all unit files, recreates the dependency tree. Sockets stay accessible during reload.
- **is-system-running** — Returns success (0) only when fully running with no failed services. States: initializing, starting, running, degraded, maintenance, stopping, offline, unknown.

## System Control Commands

- **halt**, **poweroff**, **reboot** — Asynchronous shutdown/reboot; queue the operation without waiting. `--force` skips graceful shutdown; `--when=` schedules.
- **suspend**, **hibernate**, **hybrid-sleep**, **suspend-then-hibernate** — Trigger the corresponding sleep target asynchronously.
- **emergency**, **rescue**, **default** — Isolate emergency.target, rescue.target, or default.target respectively.

## Additional Notes
systemctl accepts unit names with or without suffixes (defaulting to .service) and supports shell-style glob patterns. Absolute paths to devices or mount points are auto-converted to appropriate unit names.
