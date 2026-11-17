# RAG Corpus Manifest — Kylin Security Operations (麒麟安全智能运维)

Authoritative documentation corpus for a RAG system about the Kylin operating system, focused on security operations / O&M (运维/安全).

- **Retrieval date:** 2026-06-14
- **Files:** 21 cleaned text docs (`.md`) + 2 source PDFs (originals of the two largest KylinSoft manuals).
- **Clean text size:** ~0.78 MB (`.md`). Total incl. PDFs: ~6.4 MB.
- **Sourcing policy:** Official / authoritative only. Kylin-specific content from openKylin official (Gitee `openkylin/docs`) and 麒麟软件 (kylinos.cn) official PDFs. Upstream Linux tool docs are from the canonical project sources (firewalld.org, man7.org Linux man-pages, libpwquality project) and are explicitly labelled `upstream: true` because those tools ship and are used as-is in Kylin. No blogs, CSDN, 知乎, forums, or AI-generated content were used.

---

## 版权与使用声明 / Copyright & Usage Declaration

**本目录下的全部语料均为第三方作品，版权归各原权利人所有；本仓库不主张任何权利，也不对其重新授权。**

- **用途限定**：这些文件**仅用于本参赛作品（"软件杯" 赛题 1 · A 组，麒麟安全智能运维 Agent）的 RAG 检索实验**——即被切分、向量化后作为检索语料，用于验证问答与规划链路的可溯源性。**不用于任何商业用途，不作为独立出版物再分发。**
- **未作实质性修改**：`.md` 文件是对应官方 PDF / 网页的**文本抽取与格式清洗**结果（去除 EULA、隐私条款、页眉页脚、目录页码等非正文内容），未改写技术内容、未增删结论。两份 `.pdf` 为**未经修改的官方原件**，保留以便逐字回溯核对。
- **溯源可查**：下表逐条记录 `source_url`（原始获取地址）、`publisher`（权利人）、`retrieval_date`（获取日期）。凡 `source_url` 标注为 `(sysadmin manual extract)` 的行，均为 `kylinos/kylin-v10-server-sysadmin-manual-2024.pdf` 一行所列官方 PDF 的**章节摘录**，其原始出处以该 PDF 行的 URL 为准，不另行编造独立链接。
- **KylinSoft / openKylin 材料**：银河麒麟系列手册、白皮书、FAQ 的著作权属**麒麟软件有限公司**；openKylin 文档属 **openKylin 开源社区**。本作品仅引用其公开发布版本用于技术验证；对外交付物（演示、答辩材料）中如引用其内容，均会标注出处。
- **上游开源文档**：`linux-ops/` 下的 man page / 项目文档来自 firewalld、systemd、linux-audit、Sudo Project、SELinux、libpwquality 等上游项目，各自许可证已在下表 "license / usage note" 列逐条标注（GPL / LGPL / ISC 等），随其原始许可证条款使用。
- **移除请求**：若任一权利人认为上述使用不当，请提出，我们将立即从本仓库移除对应文件。

**清单完整性核验**：2026-08-15 已逐条比对目录内文件与下表条目——开发仓库中文件系统 23 个文件（21 个 `.md` + 2 个 `.pdf`）与表内 23 行**一一对应，无遗漏、无多余条目**。

> **提交包口径**：依据仓库根 `COPYRIGHT.md`「第三方材料不随交付物分发」的承诺，提交的源代码包中**已移除两份官方 PDF 原件**，只保留 21 个 `.md` 文本抽取与本清单。下表中两行 `.pdf` 条目保留，用于说明 `.md` 的原始出处与获取方式；需要原件请按表内 `source_url` 自行获取。

---

## Source authority legend
- **openKylin official** — `gitee.com/openkylin/docs` (open-source community docs, license-friendly). PREFERRED.
- **KylinSoft official** — 麒麟软件有限公司, published on `kylinos.cn` (and official marketplace/CDN mirrors of the same PDFs).
- **Upstream** — canonical upstream project docs for tools used unchanged in Kylin; each file's front-matter cross-references the relevant Kylin sysadmin-manual section.

## Manifest table

| filename | title | source_url | publisher | retrieval_date | language | license / usage note | topic |
|---|---|---|---|---|---|---|---|
| openkylin/openkylin-system-user-manual.md | openKylin 系统用户手册 | https://gitee.com/openkylin/docs/raw/master/1入门与参与/1_4基础操作/openKylin系统用户手册.md | openKylin community (openKylin官方) | 2026-06-14 | zh | openKylin official open-source docs (Gitee). Community-published, license-friendly. | 桌面/账户/网络/系统监视器(进程·服务)/备份/终端/软件管理 |
| openkylin/openkylin-linux-network-commands.md | Linux 网络基本命令 (openKylin 基础操作) | https://gitee.com/openkylin/docs/raw/master/1入门与参与/1_4基础操作/Linux中，你一定要掌握的网络基本命令.md | openKylin community | 2026-06-14 | zh | openKylin official docs (Gitee). | 网络运维命令 (ping/arp/route/netstat/ifconfig/iptables 等) |
| kylinos/kylin-v10-server-whitepaper-2024.md | 银河麒麟高级服务器操作系统 V10 技术白皮书 (2024-03) | https://www.kylinos.cn/upload/1/kycms/20250617/1934912227602698240.pdf | 麒麟软件有限公司 (KylinSoft) | 2026-06-14 | zh | Official public product whitepaper. Text extracted from official PDF. | 内核/容器/虚拟化/安全体系(身份认证·DAC·MAC·KYSEC·国密·可信计算·安全启动·安全中心·文件保护箱)/高可用 |
| kylinos/kylin-v10-server-whitepaper-2024.pdf | (同上 — 原始 PDF) | https://www.kylinos.cn/upload/1/kycms/20250617/1934912227602698240.pdf | 麒麟软件有限公司 | 2026-06-14 | zh | Official PDF, 43 pages. Kept for fidelity. | 同上 |
| kylinos/kylin-v10-server-sysadmin-manual-2024.md | 银河麒麟高级服务器操作系统 V10 系统管理员手册 (2024-03) | (official marketplace/CDN mirror of KylinSoft PDF) | 麒麟软件有限公司 (KylinSoft) | 2026-06-14 | zh | Official sysadmin manual, 389 pages. EULA/privacy front-matter removed; full text. | 用户/权限/sudo·软件包管理·systemd·监控·日志(journald/rsyslog)·cron·防火墙·审计·KYSEC·三权分立·MAC·安全中心 |
| kylinos/kylin-v10-server-sysadmin-manual-2024.pdf | (同上 — 原始 PDF) | https://mkp-res.hc-cdn.com/marketplace/public/appv2/attachment/599/25C/B1E/000000000059925CB1E.20250120093248.88918151464731247464200944458163.pdf (and myqcloud mirror) | 麒麟软件有限公司 | 2026-06-14 | zh | Official PDF, 389 pages. Kept for fidelity. | 同上 |
| kylinos/kylin-v10-sysadmin-user-permission-privilege.md | 银河麒麟 V10 — 用户配置与获取特权 (§1.4/§1.6) | (sysadmin manual extract) | 麒麟软件有限公司 | 2026-06-14 | zh | Focused extract from official sysadmin manual. | 账号/权限/su/sudo/wheel/visudo/Kdump |
| kylinos/kylin-v10-sysadmin-software-package-management.md | 银河麒麟 V10 — 安装和管理软件 (第四章) | (sysadmin manual extract) | 麒麟软件有限公司 | 2026-06-14 | zh | Focused extract from official sysadmin manual. | 软件包管理 dnf/rpm/软件包组/操作记录/完整性 |
| kylinos/kylin-v10-sysadmin-systemd-services.md | 银河麒麟 V10 — 基础服务 (systemd 管理, §5.1) | (sysadmin manual extract) | 麒麟软件有限公司 | 2026-06-14 | zh | Focused extract from official sysadmin manual. | systemd / systemctl 服务管理 |
| kylinos/kylin-v10-sysadmin-monitoring-logs-automation.md | 银河麒麟 V10 — 监控和自动化 (第七章) | (sysadmin manual extract) | 麒麟软件有限公司 | 2026-06-14 | zh | Focused extract from official sysadmin manual. | 系统监控/日志管理(journald·rsyslog)/计划任务 cron |
| kylinos/kylin-v10-desktop-faq.md | 银河麒麟桌面操作系统 V10 (SP1) 常见问题 FAQ (2025-04) | https://www.kylinos.cn/upload/1/kycms/20250617/1934877956515139584.pdf | 麒麟软件有限公司 (KylinSoft) | 2026-06-14 | zh | Official public FAQ. Text extracted from official PDF (23 pages). | 权限/安全中心/日志查看器/生物特征认证/网络/软件商店 |
| kysec-security/kylin-v10-sysadmin-ch8-system-security.md | 银河麒麟 V10 系统安全 (系统管理员手册 第八章) | (sysadmin manual extract, official KylinSoft) | 麒麟软件有限公司 (KylinSoft) | 2026-06-14 | zh | Focused security chapter extract from official sysadmin manual. PRIMARY KYSEC doc. | 防火墙 firewalld·审计 auditd·KYSEC(安全状态/执行控制/文件保护)·数据隔离 boxadm·强制访问控制 SELinux·三权分立·安全切换工具(default/strict)·审计权限管理·安全中心·文件保护箱 |
| linux-ops/firewalld-concepts.md | firewalld Concepts | https://firewalld.org/documentation/concepts.html | The firewalld Project | 2026-06-14 | en | UPSTREAM (GPLv2). firewalld used as-is in Kylin (sysadmin §8.1.1). | 防火墙 zones/services/runtime vs permanent/rich rules |
| linux-ops/firewall-cmd-man.md | firewall-cmd man page | https://firewalld.org/documentation/man-pages/firewall-cmd.html | The firewalld Project | 2026-06-14 | en | UPSTREAM. firewall-cmd CLI used as-is in Kylin. | 防火墙 firewall-cmd 命令 |
| linux-ops/auditctl-man8.md | auditctl(8) | https://man7.org/linux/man-pages/man8/auditctl.8.html | linux-audit / Linux man-pages | 2026-06-14 | en | UPSTREAM (GPL). Audit framework used as-is in Kylin (§8.1.2). | 审计 auditctl/审计规则 |
| linux-ops/audit-rules-man7.md | audit.rules(7) | https://man7.org/linux/man-pages/man7/audit.rules.7.html | linux-audit / Linux man-pages | 2026-06-14 | en | UPSTREAM. Used as-is in Kylin. | 审计规则文件格式 |
| linux-ops/auditd-ausearch-aureport.md | auditd / ausearch / aureport | https://man7.org/linux/man-pages/man8/auditd.8.html | linux-audit / Linux man-pages | 2026-06-14 | en | UPSTREAM (consolidated 3 man pages). Used as-is in Kylin. | 审计守护进程/日志查询/审计报告 |
| linux-ops/systemctl-man1.md | systemctl(1) | https://man7.org/linux/man-pages/man1/systemctl.1.html | systemd / Linux man-pages | 2026-06-14 | en | UPSTREAM (LGPL). systemd used as-is in Kylin (§5.1). | 服务与进程管理 systemctl |
| linux-ops/systemd-service-man5.md | systemd.service(5) | https://man7.org/linux/man-pages/man5/systemd.service.5.html | systemd / Linux man-pages | 2026-06-14 | en | UPSTREAM. Used as-is in Kylin. | 服务单元文件 Type=/Restart=/Exec* |
| linux-ops/journalctl-man1.md | journalctl(1) | https://man7.org/linux/man-pages/man1/journalctl.1.html | systemd / Linux man-pages | 2026-06-14 | en | UPSTREAM. journald used as-is in Kylin (§7.2/§7.5). | 日志管理 journalctl |
| linux-ops/sudoers-man5.md | sudoers(5) | https://man7.org/linux/man-pages/man5/sudoers.5.html | Sudo Project / Linux man-pages | 2026-06-14 | en | UPSTREAM (ISC). sudo used as-is in Kylin (§1.6); Kylin 三权分立 layered on top. | 账号与权限/sudo/sudoers/提权 |
| linux-ops/pwquality-conf-man5.md | pwquality.conf(5) | https://raw.githubusercontent.com/libpwquality/libpwquality/master/doc/man/pwquality.conf.5.pod | libpwquality project | 2026-06-14 | en | UPSTREAM (project POD source). pam_pwquality is the 口令策略 backend in Kylin PAM. | 口令策略/密码复杂度 |
| linux-ops/selinux-man8.md | selinux(8) | https://man7.org/linux/man-pages/man8/selinux.8.html | SELinux project / Linux man-pages | 2026-06-14 | en | UPSTREAM. Basis of Kylin 强制访问控制 (§8.2.3). | 强制访问控制 SELinux/MAC |

## Notes & gaps
- **KYSEC primary source:** `kysec-security/kylin-v10-sysadmin-ch8-system-security.md` + the whitepaper security chapter together give the authoritative Kylin-specific description of KYSEC modes (enforce/warning/soft), security-switch (default/strict/custom), 三权分立 (secadm/sysadm/auditadm), MAC, 数据隔离 (boxadm), 安全中心, 文件保护箱.
- **V11 docs:** No official 银河麒麟 V11 whitepaper/manual PDF was retrievable from the official upload CDN at retrieval time; V10 (2024-03) is the latest authoritative server doc obtained.
- **kylinos.cn document-center HTML pages** (`/support/document/*.html`) block non-browser clients (404/anti-bot); the substantive content lives in the linked official PDFs, which were downloaded directly.
- Upstream tool docs are included because firewalld/auditd/systemd/SELinux/sudo/pwquality ship and operate identically in Kylin; each is labelled `upstream: true` and cross-referenced to the matching Kylin manual section so RAG answers can distinguish Kylin-specific from generic Linux behavior.
