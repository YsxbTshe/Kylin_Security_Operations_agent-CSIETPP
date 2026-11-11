---
title: sudoers(5) — Default sudo security policy file format (upstream man page)
publisher: Sudo Project (sudo.ws) / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man5/sudoers.5.html
retrieved: 2026-06-14
language: en
license: Upstream sudo man-page (ISC-style license). sudo/sudoers are used as-is in 银河麒麟 (see Kylin sysadmin manual §1.6 获取特权).
upstream: true
topic: 账号与权限 sudo / sudoers / 提权
note: UPSTREAM man page (core reference). sudo is the privilege-escalation tool in Kylin; Kylin sysadmin manual §1.6 (获取特权) documents sudo usage. Kylin's 三权分立 builds on but is distinct from plain sudoers.
---

# sudoers(5) — sudo security policy file (UPSTREAM — used as-is in Kylin)

> Upstream source: man7.org. Kylin sysadmin manual §1.6 (获取特权) documents sudo. Note: Kylin's 三权分立 (separation of three administrator roles) is a Kylin-specific MAC mechanism layered on top of, and stricter than, standard sudoers.

## Overview
The **sudoers** plugin is the default security policy for `sudo`, determining user privileges. Configuration is typically `/etc/sudoers` (edit with `visudo`) or LDAP. When multiple entries match, the last match is used (not necessarily the most specific).

## File Structure
Two entry types: **Aliases** (named groups of users/hosts/commands) and **User specifications** (rules defining who can run what).

## Aliases

| Type | Syntax | Purpose |
|------|--------|---------|
| User_Alias | `NAME = user1, user2, ...` | Group users |
| Runas_Alias | `NAME = user1, user2, ...` | Target users for commands |
| Host_Alias | `NAME = host1, host2, ...` | Group hosts |
| Cmnd_Alias | `NAME = /cmd1, /cmd2, ...` | Group commands |

Names must start with an uppercase letter and contain only letters, numbers, underscore. Items can be negated with `!`.

## User Specification
Structure: **who where = (as_whom) what**

```
User_List Host_List = [Runas_Spec] [Tags] Command_List
```

- **User_List:** username, `#uid`, `%group`, `%#gid`, `+netgroup`, User_Alias, or negations.
- **Host_List:** hostname (wildcards ok), IP/network, netgroup, Host_Alias, or `ALL`.
- **Runas_Spec:** `(user_list : group_list)` — which users/groups the command runs as; empty = `root`.
- **Command_List:** fully-qualified paths, directories, or Cmnd_Alias. Supports wildcards (`*`, `?`, `[range]`), regex (`^...$`), SHA digests for integrity, built-ins `list`/`sudoedit`; `""` means no arguments allowed.

## Tags (inherited by subsequent commands until overridden)

| Tag | Effect |
|-----|--------|
| NOPASSWD / PASSWD | Bypass / require authentication |
| NOEXEC / EXEC | Prevent / allow shell escapes |
| LOG_INPUT / NOLOG_INPUT | Log / don't log input |
| LOG_OUTPUT / NOLOG_OUTPUT | Log / don't log output |
| MAIL / NOMAIL | Send / suppress mail notifications |
| INTERCEPT / NOINTERCEPT | Validate subcommands |
| FOLLOW / NOFOLLOW | Allow / deny symlink editing (sudoedit) |
| SETENV / NOSETENV | Allow / deny env var setting |

Example: `user1 host = NOPASSWD: /bin/kill, PASSWD: /bin/ls`

## Defaults Entries
```
Defaults parameter=value
Defaults@host parameter=value
Defaults:user parameter=value
Defaults!command parameter=value
Defaults>runasuser parameter=value
```
Common flags: `env_reset` (on), `authenticate` (on), `log_allowed` (on), `log_denied` (on), `fqdn`, `secure_path`. List operators `+=` / `-=`.

## Critical Security Notes
1. Negation with `ALL` to deny specific commands rarely works — attackers can exploit relative paths or env vars.
2. Wildcards don't match `/` in file names but do match in arguments.
3. `!command` + glob can be bypassed via relative paths (`./command`, `../bin/command`).
4. `env_reset` builds a minimal environment; dangerous vars (LD_PRELOAD etc.) are stripped.
5. Command digests have race conditions if the user can write the command or its directory.

## Common Examples
```
alice ALL = NOPASSWD: /bin/ls, /usr/bin/less
bob   ALL = (operator) /bin/kill, /usr/bin/top
charlie ALL = (:disk) /sbin/fdisk
Defaults env_reset
Defaults secure_path="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin"
@includedir /etc/sudoers.d
```

## Key Takeaways
Match order matters (last match wins); use aliases; prefer explicit NOPASSWD grants over ALL denials; monitor logs; always edit with `visudo`; be cautious with wildcards/relative paths in negations.
