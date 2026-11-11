---
title: auditctl(8) — Linux Audit Control Utility (upstream man page)
publisher: linux-audit project / Linux man-pages (man7.org)
source_url: https://man7.org/linux/man-pages/man8/auditctl.8.html
retrieved: 2026-06-14
language: en
license: Upstream Linux man-page (linux-audit, GPL). auditd/auditctl are used as-is in 银河麒麟 (see Kylin sysadmin manual §8.1.2 审计管理 and §8.2.5.3 审计系统权限管理).
upstream: true
topic: 审计 auditd / auditctl / audit rules
note: UPSTREAM man page. The Linux Audit framework (auditd, auditctl, audit.rules, ausearch, aureport) is shipped unchanged in Kylin; the Kylin sysadmin manual documents the same commands.
author: Steve Grubb
---

# auditctl(8) — Linux Audit Control Utility (UPSTREAM)

> Upstream source: man7.org. The Linux Audit framework is used as-is in 银河麒麟; the Kylin sysadmin manual §8.1.2 (审计管理) and §8.2.5.3 (审计系统权限管理) document these same tools.

## SYNOPSIS
```
auditctl [options]
```

## DESCRIPTION
The auditctl program is used to configure kernel options related to auditing, to see status of the configuration, and to load discretionary audit rules.

## CONFIGURATION OPTIONS

- **-b** *backlog* — Sets the maximum number of outstanding audit buffers (kernel default: 64).
- **--backlog_wait_time** *wait_time* — Specifies kernel wait time when backlog limit is reached (kernel default: 60*HZ).
- **--reset_backlog_wait_time_actual** — Resets the backlog wait time counter displayed by status command.
- **-c** — Continues loading rules despite errors; summarizes results.
- **-D** — Deletes all rules and watches; accepts optional key parameter (-k).
- **-e** [0..2] — Sets enabled flag: 0=disable, 1=enable, 2=lock configuration (requires reboot to change).
- **-f** [0..2] — Sets failure mode: 0=silent, 1=printk, 2=panic (default: 1).
- **-h, --help** — Displays help information.
- **-i** — Ignores errors when reading rules from file; always returns success exit code.
- **--loginuid-immutable** — Makes loginuids unchangeable once set; prevents tampering but may cause container issues.
- **-q** *mount-point,subtree* — Tags mounted subtrees for watching when bound/moved within watched directories.
- **-r** *rate* — Sets message rate limit in messages/sec (0=none; default: 0).
- **--reset-lost** — Resets the lost record counter shown by status command.
- **-R** *file* — Reads and executes auditctl commands from file (must be owned by root, not readable by others).
- **--signal** *signal* — Sends signal to audit daemon: TERM, HUP, USR1, USR2, CONT (or: stop, reload, rotate, resume, state).
- **-t** — Trims subtrees after mount command.

## STATUS OPTIONS

- **-l** — Lists all rules one per line; accepts -k (filter by key) or -i (interpret syscall arguments).
- **-m** *text* — Sends user-space message into audit system (requires CAP_AUDIT_WRITE capability).
- **-s** — Reports kernel audit subsystem status including enabled flag, failure mode, rate limit, backlog.
- **-v** — Prints auditctl version.

## RULE OPTIONS

- **-a** [list,action | action,list] — Appends rule to end of specified list with given action.
  - Valid lists: task, exit, user, exclude, filesystem, io_uring
  - Valid actions: never (suppress generation), always (allocate context, fill at entry, write at exit)
- **-A** *list*,*action* — Adds rule to beginning of list with action.
- **-C** [f=f | f!=f] — Builds inter-field comparison rules using equal/not-equal operators on: auid, uid, euid, suid, fsuid, obj_uid, gid, egid, sgid, fsgid, obj_gid.
- **-d** *list*,*action* — Deletes rule from list (must match exactly including syscall names and all fields).
- **-F** [n=v | n!=v | n<v | n>v | n<=v | n>=v | n&v | n&=v] — Builds field rules with up to 64 fields per command line; supports 8 operators. Valid fields include: a0-a3 (syscall arguments), arch, auid, devmajor, devminor, dir, egid, euid, exe, exit, fsgid, fstype, fsuid, filetype, gid, inode, key, msgtype, obj_uid, obj_gid, obj_user, obj_role, obj_type, obj_lev_low, obj_lev_high, path, perm, pers, pid, ppid, saddr_fam, sessionid, subj_user, subj_role, subj_type, subj_sen, subj_clr, sgid, success, suid, uid.
- **-k** *key* — Sets filter key on audit rule (deprecated for watches; still valid for deleting/listing).
- **-p** [r|w|x|a] — Describes permission access types for file system watches (deprecated; convert to syscall rules).
- **-S** [Syscall name or number | all] — Specifies syscall(s) to audit; supports multiple -S options or comma-separated names.
- **-w** *path* — Places watch on path; deprecated due to poor performance (convert to syscall rules).
- **-W** *path* — Removes watch for file system object.

## PERFORMANCE TIPS

Consolidate multiple syscall rules with identical filters, actions, keys, and fields. Example: combine separate openat and truncate rules into a single rule with both syscalls specified. Filesystem auditing generally performs better than syscall rules for directory monitoring.

## EXAMPLES

Monitor all syscalls by PID:
```
auditctl -a always,exit -S all -F pid=1005
```
Monitor all syscalls by executable:
```
auditctl -a always,exit -S all -F exe=/usr/bin/ls
```
Monitor file opens by specific user:
```
auditctl -a always,exit -S openat -F auid=510
```
Monitor failed openat calls:
```
auditctl -a always,exit -S openat -F success=0
```
Watch file for changes (two methods):
```
auditctl -w /etc/shadow -p wa
auditctl -a always,exit -F arch=b64 -F path=/etc/shadow -F perm=wa
```
Monitor directory recursively:
```
auditctl -w /etc/ -p wa
auditctl -a always,exit -F arch=b64 -F dir=/etc/ -F perm=wa
```
Detect admin access to user files:
```
auditctl -a always,exit -F dir=/home/ -F uid=0 -C auid!=obj_uid
```

## DISABLED BY DEFAULT

Many systems include a `-a never,task` rule suppressing all audit processing. To use auditd, you need to remove that rule by deleting 10-no-audit.rules and adding 10-base-config.rules to the audit rules directory.

## FILES
- /etc/audit/audit.rules
- /etc/audit/audit-stop.rules

## SEE ALSO
audit.rules(7), ausearch(8), aureport(8), auditd(8)

## AUTHOR
Steve Grubb
