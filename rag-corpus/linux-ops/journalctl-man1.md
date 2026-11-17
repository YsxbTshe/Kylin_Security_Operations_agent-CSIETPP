---
title: journalctl(1) — Query the systemd journal (upstream man page)
publisher: systemd project / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man1/journalctl.1.html
retrieved: 2026-06-14
language: en
license: Upstream systemd man-page (LGPL). journald/journalctl are used as-is in 银河麒麟.
upstream: true
topic: 日志管理 journald / journalctl
note: UPSTREAM man page (core options). The systemd journal is the system log store in Kylin; Kylin sysadmin manual §7.2/§7.5 (查看和管理日志文件 / 使用日志) covers the same tooling.
---

# journalctl(1) — Query the systemd journal (UPSTREAM — used as-is in Kylin)

> Upstream source: man7.org. Kylin sysadmin manual §7.2 (查看和管理日志文件) and §7.5 (使用日志) cover the systemd journal and journalctl.

## Overview
journalctl prints log entries stored in the systemd journal. Without parameters it displays all accessible entries from oldest to newest.

Basic usage: `journalctl [OPTIONS...] [MATCHES...]`

Matches use `FIELD=VALUE` (e.g. `_SYSTEMD_UNIT=httpd.service`). Multiple matches on different fields are ANDed; multiple matches on the same field are ORed. `+` combines expressions with logical OR.

## Essential Viewing Options

- **--unit=UNIT|PATTERN** — Messages for the given systemd unit(s); adds matches for unit messages, systemd events, and related coredumps. Repeatable.
- **--since= / --until=** — Filter by date range. Accepts `"2012-10-30 18:17:16"`, `yesterday`, `today`, `tomorrow`, `now`, or relative times (e.g. `-1h`).
- **--follow (-f)** — Show recent entries then continuously print new ones until interrupted (real-time monitoring).
- **--boot=[ID][±offset]|all** — Messages from a specific boot. Empty = current boot; `--boot=-1` = previous boot.
- **--priority (-p)** — Filter by syslog priority (0-7: emerg, alert, crit, err, warning, notice, info, debug). A single level shows that level and higher; ranges as FROM..TO.
- **-n / --lines=[+]N** — Limit to N most recent entries; `+` prefix shows oldest. Default 10.
- **-k / --dmesg** — Kernel messages only (adds `_TRANSPORT=kernel`, implies `--boot=0`).

## Output Formatting

- **-o / --output=FORMAT** — Common formats:
  - `short` (default): single-line, syslog-like
  - `short-full`: includes weekday, year, timezone
  - `short-iso`: RFC 3339 timestamps
  - `short-precise`: microsecond precision
  - `verbose`: full structured entry with all fields
  - `json` / `json-pretty`: JSON output
  - `cat`: message text only, no metadata

## Journal Management

- **--disk-usage** — Combined disk space of all archived + active journal files.
- **--vacuum-size=SIZE / --vacuum-time=TIMESPAN / --vacuum-files=N** — Remove archived files to meet constraints (sizes: K/M/G/T; times: s/m/h/days/weeks/months/years). Combinable; usable with `--rotate`.
- **--rotate** — Archive all active journal files and create new empty ones (run before vacuuming for max effect).
- **--sync** — Flush pending journal data to disk and synchronize.

## Other Useful Options

- **--grep (-g)** — Filter by PERL-compatible regex on MESSAGE (case-insensitive if pattern is all lowercase).
- **--reverse (-r)** — Newest entries first.
- **--no-pager** — Disable the default pager.
- **--quiet (-q)** — Suppress informational messages.
- **--header** — Show internal journal field header info instead of contents.
