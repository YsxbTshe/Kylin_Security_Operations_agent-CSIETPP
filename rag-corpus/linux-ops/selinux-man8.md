---
title: selinux(8) — Security-Enhanced Linux overview (upstream man page)
publisher: SELinux project / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man8/selinux.8.html
retrieved: 2026-06-14
language: en
license: Upstream SELinux man-page (public domain / GPL tooling). SELinux is the basis of Kylin's 强制访问控制 (MAC).
upstream: true
topic: 强制访问控制 SELinux / MAC
note: UPSTREAM man page. 银河麒麟's mandatory access control (强制访问控制, sysadmin manual §8.2.3) is built on SELinux base policy; Kylin adds 安全策略模式切换 and label/policy commands on top. Modes and core tools below apply.
---

# selinux(8) — Security-Enhanced Linux overview (UPSTREAM — basis of Kylin MAC)

> Upstream source: man7.org. 银河麒麟's 强制访问控制 (sysadmin manual §8.2.3) uses SELinux base policy with Kylin-specific 安全策略模式切换 (security-switch) and label/policy commands layered on top.

## What is SELinux?
Security-Enhanced Linux (SELinux) is an implementation of a flexible mandatory access control architecture in the Linux operating system. It enforces policies based on Type Enforcement, Role-Based Access Control, and Multi-Level Security concepts.

## Operating Modes
Controlled via `/etc/selinux/config`:
- **Disabled** — Removes most SELinux kernel/application code; no protection.
- **Permissive** — SELinux enabled but denied accesses are permitted and audited (troubleshooting).
- **Enforcing** — Fully enabled; policy violations blocked and audited.

Note: the `nosuid` mount flag also disables SELinux domain transitions unless specific policy permissions are configured.

## Configuration
Primary config file `/etc/selinux/config` controls:
- Whether SELinux is enabled
- Operating mode (enforcing/permissive/disabled)
- Active policy type via `SELINUXTYPE`

Policy types: **targeted** (confines only specific services) and **MLS** (Multi-Level Security, confines all processes).

## File Contexts and Labels
All files carry security labels in filesystem extended attributes. Mislabeling is a common source of problems. Relabel via the `/.autorelabel` flag file or utilities like `restorecon` and `fixfiles`.

## Mode Commands
- `getenforce` — show current mode
- `setenforce 0|1` — switch permissive/enforcing at runtime

## Related Tools
`sestatus`, `semanage`, `setsebool`/`getsebool` (booleans), `restorecon`, `chcon`, `audit2allow`, `sepolicy`, `system-config-selinux`.
