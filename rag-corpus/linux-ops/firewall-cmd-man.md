---
title: firewall-cmd — firewalld command-line client (upstream man page)
publisher: The firewalld Project (firewalld.org)
source_url: https://firewalld.org/documentation/man-pages/firewall-cmd.html
retrieved: 2026-06-14
language: en
license: Upstream firewalld project man page (GPLv2). Used as-is in 银河麒麟 (see Kylin sysadmin manual §8.1.1.2 基本命令).
upstream: true
topic: 防火墙 firewalld / firewall-cmd
note: UPSTREAM man page (common options). firewall-cmd is the firewall CLI in Kylin; sysadmin manual §8.1.1 documents these same commands (基本命令/区域管理/服务管理/端口管理/直接接口/富规则).
---

# firewall-cmd — firewalld CLI (UPSTREAM — used as-is in Kylin)

> Upstream source: firewalld.org. Kylin sysadmin manual §8.1.1.2–§8.1.1.7 documents the same firewall-cmd commands.

## Description
firewall-cmd is the command line client of the firewalld daemon. It manages both runtime (immediate, temporary) and permanent (persistent after reboot) configurations.

## General Options
| Option | Purpose |
|--------|---------|
| `--help` | Display help text |
| `--version` | Show firewalld version |
| `--quiet` | Suppress status messages |
| `--state` | Check if firewalld daemon is active |
| `--reload` | Reload firewall rules, keeping state information |
| `--complete-reload` | Full reload incl. kernel modules (terminates connections) |
| `--runtime-to-permanent` | Save current runtime config to permanent storage |
| `--permanent` | Apply changes persistently (not immediately effective) |

## Zone Management
| Option | Purpose |
|--------|---------|
| `--get-zones` | List all available zones |
| `--get-default-zone` | Display current default zone |
| `--set-default-zone=ZONE` | Set default zone for connections |
| `--get-active-zones` | Show zones with bound interfaces/sources |
| `--list-all` | Detail the (given/default) zone configuration |
| `--list-all-zones` | Detail all zones and their configurations |
| `--new-zone=ZONE` | Create permanent zone |
| `--delete-zone=ZONE` | Remove permanent zone |

## Common Configuration Options

**Services:** `--add-service=SERVICE`, `--remove-service=SERVICE`, `--list-services`

**Ports:** `--add-port=PORT/PROTOCOL`, `--remove-port=PORT/PROTOCOL`, `--list-ports`

**Sources (IP/MAC binding):** `--add-source=SOURCE`, `--remove-source=SOURCE`, `--list-sources`

**Interfaces:** `--add-interface=INTERFACE`, `--remove-interface=INTERFACE`, `--list-interfaces`

**Rich rules:** `--add-rich-rule='RULE'`, `--remove-rich-rule='RULE'`, `--list-rich-rules`

**Masquerade & forwarding:** `--add-masquerade`, `--remove-masquerade`, `--add-forward-port=`, `--remove-forward-port=`

## Key Usage Pattern
Changes require two commands for persistence:
```
firewall-cmd --add-port=443/tcp              # Runtime only
firewall-cmd --permanent --add-port=443/tcp  # Permanent
```
All zone options accept optional `[--zone=ZONE]`; without it, the default zone applies.
