---
title: 银河麒麟 V10 — 监控和自动化（系统监控/日志/计划任务）
publisher: 麒麟软件有限公司 (KylinSoft)
source_url: https://market-isv-1258344699.cos.ap-shanghai.myqcloud.com/.../银河麒麟高级服务器操作系统V10系统管理员手册.pdf
date_published: 2024-03
retrieved: 2026-06-14
language: zh
license: 官方公开发布的系统管理员手册（节选）
topic: 系统监控, 日志管理 journald/rsyslog, 自动化 cron
note: Focused extract (第七章 监控和自动化) from the official V10 sysadmin manual (2024-03). Running headers/footers stripped.
---

# 银河麒麟 V10 — 监控和自动化（系统监控/日志/计划任务）

> 节选自麒麟软件官方《银河麒麟高级服务器操作系统 V10 系统管理员手册》(2024-03)。

第七章 监控和自动化
7.1. 系统监控工具
在配置系统之外，掌握收集基本的系统信息的方法也很重要。譬如，您应该
知道如何找出空闲内存的数量、可用硬盘空间，硬盘分区方案，以及正在运行进
程的信息等等。本节将说明如何使用几个简单程序来从您的银河麒麟服务器操作
系统中检索这类信息。
7.1.1. 查看系统进程
7.1.1.1. 使用ps 命令
Ps 命令显示系统运行进程的信息。它会生成一个静态列表，列表里的进程
是当您执行命令时系统运行的进程的一个快照。如果您想持续更新实时进程列表，
您需要使用top 命令和系统监控应用。
如果想列出当前系统中的所有进程，可以在shell 里使用如下命令：
psax
对于每一个列出的进程，psax 命令会显示进程的PID，进程依附的终端，
进程状态，进程占用的CPU 时间，可执行文件的名字。例如：

如果想显示进程的所有者，使用如下命令：
psaux
除了psax 命令提供的信息外，psaux 还显示进程拥有者的名字，进程占
用CPU 和内存的百分比，以字节为单位显示虚拟内存大小和未交换物理内存的
大小，进程开始运行的时间。
您可以使用ps 命令和grep 命令的组合来查看某进程是否在运行。譬如，
要判定 Emacs 是否在运行，使用下面这个命令：
#psax|grepemacs
有关可用命令行选项的完整列表，查看ps(1)man 手册。
7.1.1.2. 使用top 命令
Top 命令显示系统运行中进程的实时列表。它还会显示附加信息包括系统更
新时间，当前CPU 和内存的使用率，运行的进程总数。这样您可以对进程做更
进一步的操作，例如可以给进程排序或者杀死一个进程。
运行top 命令，在shell 里输入如下命令：
top
对于列出的进程，top 命令会显示进程的PID,进程所有者的名字，进程优
先级，进程NICE 值，进程使用的虚拟内存，进程使用的未交换的物理内存，进
程使用的共享内存，进程的状态，进程使用内存和CPU 的百分比，进程占用CPU
的时间和可执行文件的名字。
下表可以和top 一起使用的互动命令：

表 7-1 交互 top 命令
命令 描述
[Space] 立即刷新显示。
[h] 显示帮助屏幕。
[k]
杀死某进程。您会被提示输入进程ID 以及要发送给它的信
号。
[n] 改变要显示的进程数量。您会被提示输入数量。
[u] 按用户排序。
[M] 按内存用量排序。
[P] 按CPU 使用率排序。
[q] 退出top。
7.1.1.3. 使用系统监控工具
用户可以在系统监控工具的图形界面上查看和查找进程，改变进程的优先级
以及杀死进程。开始->所有程序->系统工具->系统监视器点击打开，如下图：

图 7-1 进程
对于列出的进程，系统监控工具会显示进程的名字、状态、进程NICE 值、
进程使用内存和CPU 的百分比、进程的PID、进程等待的通道和进程会话的其
它细节。通过点击进程名字栏可以将进程显示的信息进行升序或降序排列。
默认系统监控工具会显示当前登录用户的进程列表，通过选择查看菜单下的
不同选项，您可以做如下事情：
➢ 查看活动的进程；
➢ 查看所有进程；
➢ 查看当前登录用户的进程；
➢ 查看进程依赖；
另外，有两个按钮有如下作用：
➢ 刷新进程列表；
➢ 选中进程后杀死进程。

7.1.2. 查看内存使用情况
7.1.2.1. 使用free 命令
使用free 命令可以查看系统空闲和已经使用的内存，在shell 下输入如下
命令:
free
Free 命令可以提供物理内存和交换空间的信息。它可以显示内存总量，使
用的内存大小，空闲内存大小，共享内存大小，buff 的大小和cache 的大小以
及是否可用。例子如下：
#free
默认free 以字节显示大小，如果想以兆为单位可以加-m 选项，如下：
#free-m
7.1.2.2. 使用系统监控工具
系统监控工具的资源标签页可以查看系统中空闲的内存大小和已经使用的
大小，点击资源标签页查看系统内存使用情况。

图 7-2 资源
在内存和交换分区章节，系统监控工具显示内存和交换分区的历史使用图表
和物理内存和交换分区的总大小以及使用率。
7.1.3. 查看 CPU 使用
7.1.3.1. 使用系统监控工具
点击资源标签页查看系统的CPU 使用情况，在CPU 章节，系统监控工具显
示CPU 的历史使用图表和CPU 当前使用率的图表查看块设备和文件系统。
7.1.3.2. 使用lsblk 命令
lsblk 命令可以显示系统可以使用的块设备。它比blkid 命令提供更多的信
息和输出格式控制。它从udev 读取信息，因此非root 用户都可以使用。显示
块设备列表需要在shell 输入如下命令：
lsblk
每一块输出的块设备，lsblk 都显示设备名，主次设备号，设备是否可以删

除，设备文件大小，设备是否是只读，设备类型，设备挂载路径。例子如下：
#lsblk
NAMEMAJ:MINRMSIZEROTYPEMOUNTPOINT
sr011:011024M0rom
vda252:0020G0rom
|-vda1252:10500M0part/boot
`-vda2252:2019.5G0part
|-vg_kvm-lv_root(dm-0)253:0018G0lvm/
`-vg_kvm-lv_swap(dm-1)253:101.5G0lvm[SWAP]
默认lsblk 命令以树形结构输出块设备，如果想获取更多的设备信息添加-l
参数，例如：
#lsblk-l
NAMEMAJ:MINRMSIZEROTYPEMOUNTPOINT
sr011:011024M0rom
vda252:0020G0rom
vda1252:10500M0part/boot
vda2252:2019.5G0part
vg_kvm-lv_root(dm-0)253:0018G0lvm/
vg_kvm-lv_swap(dm-1)253:101.5G0lvm[SWAP]
有关可用命令行选项的完整列表，查看lsblk(8)man 手册。
7.1.3.3. 使用blkid 命令
blkid 命令可以显示可用块设备底层信息。它需要root 权限，因此非root
用户只能使用lsblk 命令，以root 身份在shell 输入如下命令：
blkid
每一个列出的块设备，blkid 会显示它的可用属性，例如UUID,文件系统类
型，卷标签。例子如下：

#blkid
/dev/vda1: UUID="7fa9c421-0054-4555-b0ca-b470a97a3d84"
TYPE="ext4"
/dev/vda2:UUID="7IvYzk-TnnK-oPjf-ipdD-cofz-DXaJ-gPdgBW"
TYPE="LVM2_member"
/dev/mapper/vg_kvm-lv_root:
UUID="a07b967c-71a0-4925-ab02-aebcad2ae824"
TYPE="ext4"
/dev/mapper/vg_kvm-lv_swap:
UUID="d7ef54ca-9c41-4de4-ac1b-4193b0c1ddb6"
TYPE="swap"
默认blkid 命令会显示所有可用的块设备。如果想显示某一块设备，需要在
命令后面加设备名：
blkiddevice_name
例如，想显示设备/dev/vda1 的信息，以root 用户权限输入命令：
#blkid/dev/vda1
/dev/vda1: UUID="7fa9c421-0054-4555-b0ca-b470a97a3d84"
TYPE="ext4"
在上面的命令中加入-p 和-o 选项可以获取更多细节信息，命令同样需要
root 权限。
blkid-poudev/dev/vda1
例如：
#blkid-poudev/dev/vda1
ID_FS_UUID=7fa9c421-0054-4555-b0ca-b470a97a3d84
ID_FS_UUID_ENC=7fa9c421-0054-4555-b0ca-b470a97a3d84
ID_FS_VERSION=1.0
ID_FS_TYPE=ext4
ID_FS_USAGE=filesystem

有关可用命令行选项的完整列表，查看blkid(8)man 手册。
7.1.3.4. 使用findmnt 命令
findmnt 命令显示系统当前挂载的文件系统，在shell 里输入如下命令：
findmnt
每一个列出的文件系统，findmnt 命令显示挂载点，原设备，文件系统类
型和有关的挂载选项。
例如：
默认findmnt 以树型结构输出文件系统，如果想获取更多的设备信息添加
-l 参数，例如：
#findmnt-l
您可以选择只输出某种类型的文件系统，使用-t 选项，后面写文件系统类型，
例如：

findmnt-t{type}
例如，显示所有的xfs 文件系统:
有关可用命令行选项的完整列表，查看findmnt(8)man 手册。
7.1.3.5. 使用df 命令
df 命令输出系统磁盘空间的使用报告，在shell 里输入如下命令:
df
每一个列出的文件系统，df 命令都会显示文件系统的名字，大小（以K 字
节为单位），磁盘空间使用率和使用大小，磁盘空间剩余大小和文件挂载点。例
子如下：
#df
Filesystem1K-blocksUsedAvailableUse%Mountedon
/dev/mapper/vg_kvm-lv_root 18618236 4357360 13315112
25%/
tmpfs3803762883800881%/dev/shm
/dev/vda14958447702939321517%/boot
默认df 命令显示分区大小（以K 字节为单位），磁盘空间使用总量，磁盘
剩余空间可用量（以K 字节为单位）。想以M 字节和G 字节显示需要使用-h 选
项，该选项可以以易读方式的格式显示磁盘空间大小。
例如：
#df-h
FilesystemSizeUsedAvailUse%Mountedon

/dev/mapper/vg_kvm-lv_root18G4.2G13G25%/
tmpfs372M288K372M1%/dev/shm
/dev/vda1485M76M384M17%/boot
有关可用命令行选项的完整列表，查看df(1)man 手册。
7.1.3.6. 使用du 命令
du 命令可以查看文件的大小。du 命令不加参数可以显示每个子目录中文件
的大小。例如：
#du
14972./Downloads
4./.mozilla/extensions
4./.mozilla/plugins
12./.mozilla
15004.
默认情况下，du 命令以千字节为单位显示磁盘的使用情况。如果想以易读
方式（MB 和GB）显示大小，可以添加参数-h。例如;
#du-h
15M./Downloads
4.0K./.mozilla/extensions
4.0K./.mozilla/plugins
12K./.mozilla
15M.
在列表末尾du 命令会显示出当前目录所有文件大小的和，如果只显示目录
中文件的总大小可以添加参数-s,例如：
#du-sh
15M.
有关可用命令行选项的完整列表，查看du(1)man 手册。

7.1.3.7. 使用系统监控工具
在系统监控工具的文件系统标签页可以以图表的方式查看文件系统和磁盘
空间的使用情况。
图 7-3 文件系统
每一个列出的文件系统，系统监控工具都会显示原设备，目标挂载点，文件
系统类型和大小，磁盘空间使用大小和未使用大小。
7.1.4. 查看硬件信息
7.1.4.1. 使用lspci 命令
lspci 命令可以显示PCI 总线上的设备信息，显示所有的PCI 设备在shell
里输入如下命令：
#lspci
您可以使用-v 选项输出设备详细信息，使用-vv 选项输出设备更详细的信息。

#lspci-v
有关可用命令行选项的完整列表，查看lspci(8)man 手册。
7.1.4.2. 使用lsusb 命令
lsusb 命令可以显示usb 设备信息。显示所有的usb 设备信息在shell 里
输入如下命令：
#lsusb
您可以添加-v 参数显示设备的详细信息。
#lsusb-v
有关可用命令行选项的完整列表，查看lsusb(8)man 手册。
7.1.4.3. 使用lscpu 命令
lscpu 命令显示当前系统的cpu 信息，信息包括cpu 数目，架构，型号，
家族，模式，cpu 高速缓存等等，在shell 输入命令：
#lscpu
有关可用命令行选项的完整列表，查看lscpu(1)man 手册。
7.1.5. 检查硬件错误
银河麒麟高级服务器操作系统引入了新的硬件事件报告机制。这个机制会为
DIMMs 收集内存错误和错误检查和纠错机制报告的错误，并且把错误向用户空
间报告。用户空间的守护进程rasdaemon 会捕获和处理这些由内核机制跟踪

的 有 可 靠 性 可 用 性 可 维 护 性 的 错 误 事 件 ， 并 记 录 它 们 。 这 个 功 能 以 前 由
edac-utils 提供，现在由守护进程rasdaemon 提供。
安装rasdaemon 需要root 权限。
#dnfinstallrasdaemon
启动服务命令如下
#systemctlstartrasdaemon
输入下面的命令查看命令的各种选项
#rasdaemon--help
命令在rasdaemon(8)man 手册里也有描述。
ras-mc-ctl 工具提供了一种可以使用EDAC 驱动的方法，输入如下命令可
以查看命令的选项。
#ras-mc-ctl--help
命令在ras-mc-ctl(8)man 手册里也有描述。
7.1.6. 使用 Net-SNMP 监控性能
银河麒麟高级服务器操作系统包括Net-SNMP 软件套件，其中包括一个灵
活的可扩展的简单网络管理协议（SNMP）代理。该代理及其关联的实用程序可
以被用于从大量的系统中提供性能数据给一系列工具，这些工具都支持SNMP
协议。
本节提供了关于配置Net-SNMP 代理通过网络安全地提供性能数据，使用
SNMP 协议检索数据和扩展SNMP 代理以提供自定义的性能指标。

7.1.6.1. 安装Net-SNMP
Net-SNMP 软件套件可作为银河麒麟高级服务器操作系统V10 发行版的一
组RPM 软件包。表7-2 可用Net-SNMP 软件包概述了每个包和它们的内容。
表 7-2 可用 Net-SNMP 软件包
软件包 提供商
net-snmp SNMP 代理守护进程和文档。输出性能数据需要这个包。
net-snmp-libs
netsnmp 库和捆绑的管理信息库（MIBs）。输出性能数
据需要这个包。
net-snmp-utils
SNMP 客户端例如snmpget 和snmpwalk。需要该软件
包以查询SNMP 系统的性能数据。
net-snmp-perl
mib2c 程序和NetSNMPPerl 模块。此包是由可选通道
提供的。
net-snmp-python Python 的SNMP 客户端库。此包是由可选通道提供的。
安装任何一个软件包按如下方式使用dnf 命令：
dnfinstallpackage…
例如，安装在本节的其余部分中使用的SNMP 代理守护进程和SNMP 客户
端，以root 身份在shell 键入如下命令：
#dnfinstallnet-snmpnet-snmp-libsnet-snmp-utils

7.1.6.2. 运行Net-SNMP 守护进程
net-snmp 软件包中包含SNMP 代理守护进程snmpd。本节提供有关如何
启动，停止和重新启动snmpd 服务的信息。有关在银河麒麟高级服务器操作系
统V10 系统的管理服务的详细信息请参阅5.1 使用systemd 管理系统服务。
启动服务
运行snmpd 服务以root 身份在shell 键入如下命令：
#systemctlstartsnmpd.service
配置服务使其在系统启动后自动开始运行使用如下命令：
#systemctlenablesnmpd.service
停止服务
停止snmpd 服务以root 身份在shell 键入如下命令：
#systemctlstopsnmpd.service
配置服务使其在系统启动后并不开始运行，使用如下命令：
#systemctldisablesnmpd.service
重启服务
重启snmpd 服务以root 身份在shell 键入如下命令：
#systemctlrestartsnmpd.service
该命令停止服务并快速重启服务。要仅重新加载配置，而无需停止服务，运
行以下命令：

#systemctlreloadsnmpd.service
这会运行snmp 服务去重新加载配置。
7.1.6.3. 配置Net-SNMP
要修改Net-SNMP 代理守护进程的配置，编辑/etc/snmp/snmpd.conf 配
置文件。银河麒麟高级服务器操作系统V10 包含的默认的snmpd.conf 文件被
大量注释，并可以作为一个很好的代理配置文件的起点。
本节主要介绍两种常见的任务：设置系统信息并配置认证。有关可用的配置
指令的详细信息，请参阅snmpd.conf(5)手册页。此外，在net-snmp 软件包
里有一个名为snmpd.conf 的实用程序，可以用来以交互方式生成代理配置。
需 要 注意 的是net-snmp-util 软件 包必 须被 安装才 能使 用本 节所 述的
snmpwalk 实用程序。
#systemctlreloadsnmpd.service
设置系统信息
Net-SNMP 通 过 系 统 树 提 供 了 一 些 基 本 的 系 统 信 息 。 例 如 ， 下 面 的
snmpwalk 的命令显示具有默认代理配置系统树。
#snmpwalk-v2c-cpubliclocalhostsystem
缺省情况下，sysName 对象被设置为主机名。sysLocation 和sysContact
对 象 可 以 在/etc/snmp/snmpd.conf 文 件 通 过 改 变 syslocation 和
syscontact 的值被配置。例如:
syslocationDatacenter,Row4,Rack3
syscontactUNIX Admin<admin@example.com>

更改配置文件后，重新加载配置，并通过再次运行snmpwalk 命令测试以
下配置是否生效。
#systemctlreloadsnmp.service
#snmpwalk-v2c-cpubliclocalhostsystem
配置权限
Net-SNMP 代理守护程序支持所有三个版本的SNMP 协议。前两个版本（1
和2c）通过使用字符串提供简单的身份验证。该字符串是代理人和任何客户端
实用程序之间共享的秘密。该字符串在网络上用明文传递，这认为是不安全的。
SNMP 协议第3 版支持使用多种协议的用户认证和信息加密。Net-SNMP 代理
还支持SSH 通道，使用X.509 证书的TLS 认证和Kerberos 认证。
配置 SNMP 版本 2c
要配置SNMP 版本2c 的社区，在/etc/snmp/snmpd.conf 配置文件下使
用RO 社区或RW 社区指令。指令的格式是如下：
directivecommunity[source[OID]]
其中，community 要使用社区字符串，source 是IP 地址或子网，OID 对
SNMP 树提供访问属性。例如，下面的指令对客户端提供只读的系统树访问，该
客户端使用本地计算机上的社区字符串“kylin”。
rocommunitykylin127.0.0.1.1.3.6.1.2.1.1
使用snmpwalk 命令并添加-v 和-c 参数可以测试配置。
#snmpwalk-v2c-ckylinlocalhostsystem

配置 SNMP 版本 3
要配置SNMP 版本3，使用net-snmp-create-v3-user 命令。此命令添
加条目到/var/lib/net-snmp/snmpd.conf 和/etc/snmp/snmpd.conf 文件，
这两个文件可以创建用户并授权访问。注意，net-snmp-create-v3-user 命令
只 有 代 理 没 有 运 行 时 才 能 运 行 。 下 面 的 示 例 创 建 了“admin” 用 户 ， 密 码
“kylinsnmp”：
#systemctlstopsnmpd.service
#net-snmp-create-v3-user
net-snmp-create-v3-user 命令添加rwuser 指令（或当提供的-ro 命令
行选项使用rouser）到/etc/snmp/snmpd.conf 中和添加rwcommunity 和
rocommunity 的格式相同：
directiveuser[noauth|auth|priv][OID]
其中，user 是用户名，OID 是SNMP 树提供访问。默认情况下，Net-SNMP
代理仅允许已通过授权的请求（auth 选项）。noauth 选项可以允许未经授权
的请求，priv 选项强制使用加密,authpriv 选项指定的请求必须通过授权和回复
被加密。
例如，下面一行表示授予用户“admin”读写访问整个树：
rwuseradminauthpriv.1
为了测试配置，在您的用户目录下创建一个snmp 目录，在该目录下创建
snmp.conf 文件（~/.snmp/snmp.conf），使用如下命令：
defVersion3
defSecurityLevelauthPriv

defSecurityNameadmin
defPassphrasekylinsnmp
snmpwalk 命令在查询代理时会使用这些授权设置。
#snmpwalk-v3localhostsystem
SNMPv2-MIB::sysDescr.0 = STRING: Linux
localhost.localdomain3.10.0-
123.el7.x86_64#1SMPMonMay511:16:57EDT2014x86_64
[outputtruncated]
7.1.6.4. 检索数据性能
银河麒麟高级服务器操作系统的Net-SNMP 代理在SNMP 协议上提供了多
种性能信息。此外，可以通过代理查询系统中安装RPM 包的列表，系统当前运
行的进程列表和系统的网络配置。
本 节 提 供 了 关 于OIDs 在SNMP 上 的 性 能 概 述 。 它 假 定 系 统 已 安 装
net-snmp-utils 软件包，并且用户被授予访问SNMP 树，如7.1.7.3 配置权限
中描述的。
硬件配置
包括Net-SNMP 的HostResourcesMIB 提供了主机客户端程序的硬件和
软件配置。表7-3 可用的OIDs 总结了在MIB 下可以获取的不同的OID。
表 7-3 可用的 OIDs
OID 描述
HOST-RESOURCES-MIB::hrSystem
包含一般系统信息，如运行时间，用
户的数目，和运行的进程的数目。
HOST-RESOURCES-MIB::hrStorage 包含内存和文件系统使用情况数据。

HOST-RESOURCES-MIB::hrDevices
包含所有的处理器，网络设备和文件
系统的列表。
HOST-RESOURCES-MIB::hrSWRun 包含所有正在运行的进程的列表。
HOST-RESOURCES-MIB::hrSWRunP
erf
包含进程表中的内存和CPU 统计信
息 ， 进 程 表 来 自 于
HOST-RESOURCES-MIB::hrSWRu
n。
HOST-RESOURCES-MIB::hrSWInstal
led
包含RPM 数据库的列表。
在HostResourcesMIB 中，还有一些可用于检索的可用信息的SNMP 表。
下面的例子列出了HOST-RESOURCES-MIB::hrFSTable:
关 于 HOST-RESOURCES-MIB 的 更 多 信 息 ， 可 以 查 看
/usr/share/snmp/mibs/HOST-RESOURCES-MIB.txt 文件。
CPU 和内存信息
UCD-SNMP-MIB 的大多数系统性能数据是可用的。systemStatsOID 提
供了一些处理器的使用计数器：
#snmpwalklocalhostUCD-SNMP-MIB::systemStats
特 别 是 ， ssCpuRawUser,ssCpuRawSystem,ssCpuRawWait 和

ssCpuRawIdleOIDs 提供的计数器在确定系统是否要花费了大量的处理器时
间 在 内 核 空 间 或 用 户 空 间 或 I/O 时 非 常 有 用 。ssRawSwapIn 和
ssRawSwapOut 可确定系统内存是否耗尽时非常有用。
UCD-SNMP-MIB::memoryOID 下很多可用的内存信息和free 命令类似：
#snmpwalklocalhostUCD-SNMP-MIB::memory
UCD-SNMP-MIB 的 负 载 均 衡 是 可 用 的 。 SNMP 的
UCD-SNMP-MIB::laTable 会列出1 分钟，5 分钟和15 分钟的负载均衡值。
文件系统和磁盘信息
HostResourcesMIB 提供文件系统的大小和使用信息。每个文件系统（以
及各内存池）在HOST-RESOURCES-MIB::hrStorageTable 表中有一个入口：
HOST-RESOURCES-MIB::hrStorageSize 和

HOST-RESOURCESMIB::hrStorageUsed 中的ODI 被用来计算挂载文件系统
的剩余容量。
I/O 数 据 在 UCD-SNMP-MIB::systemStats 和
UCD-DISKIO-MIB::diskIOTable 表中都是可用的。后者提供了更详细的数据，
后者的OID 有diskIONReadX 和diskIONWrittenX 其提供计数器，用于在系
统启动时记录读取和写入块设备的字节数。
网络信息
InterfacesMIB 提供网络设备信息。IF-MIB::ifTable 提供了一个SNMP 表，
系统上每个接口及其配置和各种数据包计数器在这个表里都一个条目。下面的例
子显示ifTabel 的部分列，它显示了系统上的两个物理网络接口：
#snmptable-CblocalhostIF-MIB::ifTable
网络流量是根据IF-MIB::ifOutOctets 和IF-MIB::ifInOctets 提供。下面
SNMP 查询将检索此系统上的每一个接口的网络流量。
#snmpwalklocalhostIF-MIB::ifDescr
扩展的Net-SNMP
Net-SNMP 代理可以扩展到提供除原生系统度量的应用度量。这使得容量

规划和性能问题的故障排除。例如，在测试中知道邮件系统每15 分钟有5 分钟
处在负载均衡状态是有帮助的，但更有用的是知道邮件系统在每秒处理8000 个
消息时它15 分钟负载均衡是多少。当应用程序负载的度量可通过相同的接口作
为系统的度量，这也允许在不同的负载影响的情况下对系统性能可视化（例如，
每增加10,000 条信息会增加平均负载线至100,000）。
包括银河麒麟高级服务器操作系统V10 的一些应用程序通过SNMP 扩展
Net-SNMP 代理以提供应用度量。有几种方法来扩展代理定制应用程序。本节
介绍通过shell 脚本和可选的Perl 插件来扩展代理。假设系统中net-snmp-utils
和net-snmp-perl 包已经安装，同时用户被授权访问SNMP 树。
使用shell 脚本扩展Net-SNMP
在Net-SNMP 代理提供了一个扩展MIB（NET-SNMP-EXTEND-MIB），
可 以 用 来 查 询 任 意 的shell 脚 本 。 要 指 定 的shell 脚 本 来 运 行 ， 可 以 在
/etc/snmp/snmpd.conf 文件使用extend 指令。一旦定义，代理将提供退出
码和在SNMP 上命令的任何输出。下面的例子演示了这种机制，该脚本确定在
进程表中httpd 进程的数量。
注意:
Net-SNMP 代理提供了一个内建的机制通过proc 命令检查进程表。更多的
信息可以查看snmpd.conf(5)手册页。
下面shell 脚本的退出码是在给定时间点的系统上运行的httpd 进程数。
#!/bin/sh
NUMPIDS=`pgrephttpd|wc-l`
exit$NUMPIDS

为了使这个脚本在SNMP 上可用，将脚本复制到系统路径上的某个位置，
设置可执行位，并添加extend 指令到/etc/snmp/snmp.cof 文件。添加extend
指令的格式如下：
extendnameprogargs
name 是标识extend 的字符串，prog 是要运行的程序，args 是传入程序
的参数。例如，如果上面的shell 脚本复制到/usr/local/bin/check_apache.sh，
下面的指令会添加脚本到SNMP 树。
extendhttpd_pids/bin/sh/usr/local/bin/check_apache.sh
脚本可以在NET-SNMP-EXTEND-MIB::nsExtendObjects 中查询到。
请注意，退出码设置为整型（示例中退出码为8），其它任何输出为字符串
类型。为了显示整数的多重度量，extend 指令提供了不同的参数。例如，下面
的脚本可以被用于确定匹配任意字符串的进程数，并且也将输出一个文本字符串
来表示进程数:
#!/bin/sh
PATTERN=$1

NUMPIDS=`pgrep$PATTERN|wc-l`
echo"Thereare$NUMPIDS$PATTERNprocesses."
exit$NUMPIDS
当 上 面 的 脚 本 被 拷 贝 到/usr/local/bin/check_proc.sh 下 ， 执 行 下 面
/etc/snmp/snmpd.conf 文件中的指令会显示出httpd 和snmpd 的进程数。
extendhttpd_pids/bin/sh/usr/local/bin/check_proc.shhttpd
extendsnmpd_pids/bin/sh/usr/local/bin/check_proc.shsnmpd
下面的例子显示snmpwalk 命令对nsExtendObjectsOID 的输出：
警告：
整数退出码被限制在0-255 的范围。对于超过256 的值，既可以使用脚本
的标准输出（被认为是字符串）或扩展代理的不同方法。
7.2. 查看和管理日志文件
日志文件是包含系统信息的文件，包括内核，服务及其上运行的应用程序。
不同的日志文件包含不同的信息，例如，有一种默认系统日志文件，一种只是用
于安全信息的日志文件，还有用于后台任务的日志文件。
当试图解决系统问题的时候，如试图载入内核驱动程序或寻找未授权的登录

系统时，日志文件是非常有用的。本章讨论在哪里可以找到日志文件，如何查看
日志文件，以及在日志文件中查看什么。
一些日志文件被一个名为rsyslogd 的守护进程控制。该rsyslogd 守护进
程是替代sysklogd 的一种增强型进程，并提供了扩展的过滤器，消息加密保护，
各种配置选项，输入输出模块，通过TCP 或UDP 协议进行传输。需要注意的是
rsyslogd 与sysklogd 是兼容的。
日志文件还可以由journald 守护进程进行管理，该守护进程是systemd
的组件。journald 守护进程捕获系统日志消息，内核日志消息，初始RAM 磁盘
和早期启动消息和写到标准输出的消息，以及所有服务的标准错误输出，把这些
消息进行索引，并提供给用户。本地日志文件格式，它是一种结构化和索引二进
制文件，改进了搜索，并提供更快的操作，而且它也存储像时间戳或用户ID 的
元数据信息。由journald 生成的日志文件在默认情况下不是一直存在的，日志
文件只保存在内存或/run/log/journal/目录下的环形缓冲区。记录的数据量取决
于可用内存，当您达到容量极限时，最早的记录将被删除。但是，设置可以被改
变-请参阅7.5.5 使能持续存储。有关期刊详细信息，请参阅7.5 使用日志。
默认情况下，这两个记录工具并存在您的系统。journald 守护进程是解决
问题的主要工具。它也提供了必要的用于创建结构化日志消息的附加数据。通过
journald 获取的数据被转发到/run/systemd/journal/syslog 套接字，可以由
rsyslogd 进一步处理数据。然而，rsyslogd 默认通过imjournal 输入模块，从
而避免了上述的套接字。您还可以使用omjournal 模块从rsyslogd 到journald
在相反方向上传输数据。参见7.2.7Syslogd 服务和日志的交互。集成使基于

文本的日志文件使用一致的格式方便用来维护，以确保可能的应用程序或配置依
赖于rsyslogd 的兼容性。另外，您可以用结构一致化的格式维护rsyslogd 日
志。（参见7.3Syslogd 日志结构）
7.2.1. 日志文件的位置
许多由rsyslogd 维护的日志文件在/etc/rsyslog.conf 配置文件里记录。
大多数的日志文件在/var/log/目录下。一些应用程序例如httpd 和samba 在
/var/log/目录下有自己的日志文件。
您可能会注意到在/var/log 目录下有多个日志文件其名字后的数字不同（例
如，cron-20100906）。这些数字代表添加轮替日志文件里的时间戳。日志文
件被轮替，所以其大小不会太大。Logrotate 包括了一个后台任务，它可以根据
/etc/logrotate.conf 配 置 来 自 动 轮 替 日 志 文 件 ， 该 配 置 文 件 在
/etc/logrotate.d/目录下。
7.2.2. Rsyslog 的基本配置
Rsyslog 的主要配置文件是/etc/rsyslog.conf。在这里，您可以指定全局
指令，模块和过滤器的规则和动作部分。此外，您还可以以文本形式评论添加解
释（以#号开头）。
7.2.2.1. 过滤器
规则是过滤器的一部分，它选择系统日志消息的一个子集，以及行动的一部
分，它指定对选中的消息做什么。要在您的/etc/rsyslog.conf 的配置文件定义
规则，在一行同时定义过滤器和动作，用一个或多个空格或制表符分隔。
根据所选属性，rsyslog 现在提供了不同的方式过滤系统日志消息。可用的

过滤方法，可分为设置/基于优先级的，基于属性的，和基于正则表达式的过滤
器。
设置/基于优先级的过滤器
最常用的和众所周知的方式来过滤系统日志消息是使用基于设置/基于优先
级的过滤器，其过滤syslog 消息基于两个条件：由点号分隔设置和优先级。要
创建一个选择器，请使用以下语法
FACILITY.PRIORITY
➢ FACILITY 指定一个特定的子系统来生成日志消息。例如，邮件子系统处
理所有与邮件相关的系统日志消息。FACILITY 可以由以下关键字之一（或由数
字代码）来表示：
kern(0),user(1),mail(2),daemon(3),auth(4),syslog(5),lpr(6),news(7),u
ucp(8),cron(9),authpriv(10),ftp(11),和local0throughlocal7(16-23).
➢ PRIORITY 指定系统日志消息的优先级。PRIORITY 可以由以下关键字
之一（或由数字代码）来表示：
debug(7),info(6),notice(5),warning(4),err(3),crit(2),alert(1),和
emerg(0).
上述语法根据定义或更高优先级选择系统日志消息。通过比较等号（=）两
边的优先级，您指定的优先级的系统日志消息将被选中，所有其他优先级将被忽
略。相反，优先关键字之前有一个（！），选择除了该优先级的所有系统日志消
息。
除上述指定的关键字，您也可以使用星号（*）来定义所有设置或优先级（这

取决于您在哪里放置星号，逗号之前或之后）。没有给出优先级设备的优先级指
定其关键字为none。设置和优先条件是不区分大小写。
要定义多个设置和优先级，用逗号（，）将它们分开。要在一行中定义多个
选择，用分号（；）分隔它们。注意，在选择器在其字段能够覆盖前面的部分，
它可以排除来自模式一些优先次序。
实例：设置/基于优先级的过滤器
以下是可在/etc/rsyslog.conf 中指定设置/基于优先级的过滤器的几个简
单例子。要选择所有优先级的所有内核系统日志消息，添加下面的文字到配置文
件：
kern.*
选择所有的邮件系统日志消息，使用优先级为crit 或更高，使用如下形式：
mail.crit
选择所有的cron 日志消息除了优先级为info 和debug 的。使用如下格式：
cron.!info,!debug
基于属性的过滤器
基 于 属 性 的 过 滤 器 可 让 您 通 过 任 何 属 性 过 滤 系 统 日 志 消 息 ， 如
timegenerated 或syslogtag。您可以将每个指定属性和值比较，这些值和说
明参见表7-4 基于属性的比较操作。属性名和比较操作都是区分大小写的。
基于属性的过滤器以冒号（：）开始，定义该过滤器，使用如下语法：
:PROPERTY,[!]COMPARE_OPERATION,"STRING"

➢ PROPERTY 定义需要过滤的属性；
➢ 可选的感叹号（！）反向输出比较操作。基于属性的过滤器目前不支持
其他布尔运算符；
➢ COMPARE_OPERATION 属性定义了比较操作，参考表7-4 基于属性的
比较操作基于属性的比较操作；
➢ 字符串属性指定比较值，其由属性的文字字符串提供。该值必须用引号
括起来。为了使用某些字符的字符串中（例如一个引号（“）），用反斜杠字符
（\）。
表 7-4 基于属性的比较操作
Compare-o
peration
description
Contains
检查提供的字符串是否有任何部分和所提供的文本属性字符
串相匹配。要执行大小写敏感的比较，使用contains_i。
isequal
比较提供的字符串和文本提供的属性字符串。这两个值必须
匹配。
startswith
检查提供字符串和文本的属性字符串开头的匹配。要执行不
区分大小写的比较，使用startswith_i。
regex
比较提供的POSIXBRE（基本正则表达式）和文本所提供的
属性字符串。
ereregex
比较提供的POSIXERE（扩展正则表达式）和文本所提供的
属性字符串。

isempty
检查该属性是否为空。值被丢弃。在使用归一化的数据时，
某一些字段可能被填充，这个操作则特别有用。
实例：基于属性的过滤器
以下是可在/etc/rsyslog.conf 指定基于属性的过滤器的几个例子。在消息
文本里要选择包含error 字符串的日志，如下所示：
:msg,contains,"error"
下面的过滤器从主机名为host1 条件选择日志消息。
:hostname,isequal,"host1"
选择的日志消息不包含fatal 和error 已经其之间的文本。
:msg,!regex,"fatal.*error"
基于表达式的过滤器
基于表达式过滤器根据定义的算术，布尔或字符串操作选择系统日志消息。
基于表达式过滤器使用rsyslog 自己的脚本语言调用RainerScript 脚本，可以
构建复杂的过滤器。
基本的基于表达式的过滤器使用如下：
ifEXPRESSIONthenACTIONelseACTION
➢ EXPRESSION 属性表示要计算的表达式。例如：下面的表达式
$msgstartswith'DEVNAME'or$syslogfacility-text=='local0'您
可以定义多个表达式，使用and 或者or 操作符；
➢ ACTION 属性代表如果表达式返回为真要执行的动作。这可以是一个单

一的动作，或包含在大括号的任意复杂的脚本；
➢ 在一个新行的开始，基于表达式过滤器由关键字if 指示。关键字then
分开EXPRESSION 和ACTION。或者，也可以用关键字else 来指定哪些动作
是如果条件得不到满足将要执行。
基于表达式的过滤器，可以使用大括号嵌套使用条件就像例如：基于表达式
的过滤器。该脚本可以让您在表达式中使用设备/基于优先级的过滤器。另一方
面，基于属性的过滤器，不建议在这里。RainerScript 支持特定函数re_match
（）和re_extract（）的正则表达式。
实例：基于表达式的过滤器
下面的表达式包含两个嵌套条件。prog1 程序创建的日志文件被分成基于
消息中的“test”字符串的两个文件。
更多的关于基于表达式的过滤器参考在线文档。RainerScript 脚本是
rsyslog 新配置格式的基础，请参考7.2.3 使用新的配置格式。
7.2.2.2. 行为
行为指定对已经过滤的消息做什么。下面是一些可以在您的规则中定义的操
作：
存储 syslog 消息到日志文件
主要行为指明syslog 消息被保存哪个日志文件。这是由您指定文件路径完

成。
FILTERPATH
FILTER 代表着用户选择的目标文件的路径。例如，下面的规则会选择所有
的cornsyslog 消息然后将其存储到/var/log/cron 文件中。
cron.*/var/log/cron
默认情况下，日志文件每次生成一个系统日志消息的时间同步。使用破折号
标记（-）作为文件路径的前缀指定省略同步：
FILTER-PATH
请注意，您可能会失去信息，如果系统终止发生在写之前。但是，这种设置
可以提高性能，特别是如果您运行的程序会产生非常详细的日志信息。
您指定的文件路径可以是静态或动态的。静态文件由一个固定文件路径，正
如上所示的例子中表示的。动态文件路径根据所接收的消息是不同的。动态文件
路径是由一个问号（？）的前缀代表表示：
FILTER?DynamicFile
DynamicFile 是一个名称。相当于预定义的模板其用于修改输出路径。您
可以使用破折号做前缀（-）来禁用同步，您也可以使用一个冒号分隔的多个模
板（;）。
当您使用的是XWindow 系统的时候，如果您指定的文件是存在的终端或
/dev/console 设备，系统日志消息发送到标准输出（使用特殊的终端处理）或
控制台（使用特殊的/dev/console 的-处理）。

通过网络发生syslog消息
Rsyslog 允许您可以通过网络发送和接收系统日志消息。此功能允许您在一
台机器管理多个主机的系统日志消息。要转发syslog 消息到远程计算机，请使
用以下语法：
@[(zNUMBER)]HOST:[PORT]
➢ “@”符号表示syslog 消息被转发到使用UDP 协议的主机；
➢ “@@”符号表示syslog 消息被转发到使用TCP 协议的主机；
➢ 可选项zNUMBER 设置对系统日志消息的zlib 压缩。该NUMBER 属性
指定的压缩级别（从1-最低到9-最大）。压缩增益自动由rsyslogd 检查，如
果有任何压缩增益消息会被压缩，如果消息小于60 字节则不会被压缩；
➢ HOST 属性定义哪个主机选择syslog 消息；
➢ PORT 属性定义主机端口；
➢ 当主机定义IPV6 地址，用方括号括地址（[]）。
实例：通过网络发送 syslog 消息
以下是该通过网络（请注意，所有操作都前面带有一个选择器，其选择任何
优先级的所有消息）转发系统日志信息的操作的一些例子。要通过UDP 协议，
将消息转发到192.168.0.1。
*.*@192.168.0.1
使用6514 端口和TCP 协议将消息转发到example.com：
*.*@@example.com:6514

下面压缩信息以zlib（9 级压缩），并将其转发给[2001:db8::1],传输使用
UDP 协议。
*.*@(z9)[2001:db8::1]
输出通道
输出通道主要用于指定日志文件可以增长到的最大大小。这对日志文件轮替
非常有用的（有关详细信息，请参见7.2.2.4 日志轮替）。输出通道基本上是关
于 输 出 操 作 的 信 息 的 集 合 。 输 出 通 道 由$outchannel 指 令 定 义 。 要 定 义
/etc/rsyslog.conf 输出通道，请使用以下语法：
$outchannelNAME,FILE_NAME,MAX_SIZE,ACTION
➢ NAME 属性指定了输出通道的名称；
➢ FILE_NAME 属性指定了输出文件名。输出通道只能写入到文件中，没
有管道，终端，或其他类型的输出；
➢ MAX_SIZE 属性指定了文件可以增长的最大大小，以字节为单位；
➢ ACTION 属性定义当文件到最大大小的行为；
➢ 要使用定义的通道作为规则里的一个行为，如下：
FILTER:omfile:$NAME
实例：输出通道日志轮替
下面通过使用一个输出通道显示了一个简单的日志轮替。输出通道经由
$outchannel 指令定义：

$outchannellog_rotation,/var/log/test_log.log,104857600,
/home/joe/log_rotation_script
使用规则选择所有优先级的所有syslog 消息，获取消息后执行预先定义的
输出通道。
*.*:omfile:$log_rotation
一旦限制（在本例中100MB）被达到，/home/joe/log_rotation_script
会被执行。该脚本可以包含任何从文件移动到其他文件夹内容，编辑具体的内容，
或者干脆删除它。
发送 syslog 消息给特定的用户
rsyslog 通过指定用户名将syslog 消息发送到指定的用户（如本章实例：
指定多行为）。要指定多个用户，用逗号（，）分隔每个用户名。将消息发送给
当前登录的所有用户，使用星号（*）。
执行一个程序
rsyslog 可以为选定的系统日志消息执行程序，并使用system（）调用在
shell 中执行的程序。要指定一个程序来执行，用（^）字符前缀该命令。因此，
指定一个模板其接收的消息的格式，并将其传递到指定的可执行文件作为一个行
参数。
FILTER^EXECUTABLE;TEMPLATE
这里过滤器状态的输出通过由EXECUTABLE 指定的程序进行处理。这个程
序可以是任何有效的可执行文件。用格式模板的名称替换TEMPLATE。
实例：执行程序

在下面的例子中，被选择的任何优先级的任何系统日志消息，其格式经过与
template 模板匹配并作为参数传递给test-program 程序。
*.*^test-program;template
警告：
从任何主机接收消息时以及使用shell 执行操作，可能会受到命令注入。攻
击者可以尝试将命令注入到您指定的行为要执行的程序。为了避免任何可能的安
全威胁，充分考虑使用的shell 执行行为。
存储 syslog 消息到数据库
通过使用数据库写操作，被选择的系统日志消息可以直接写入到数据库表。
数据库写入使用的语法如下：
:PLUGIN:DB_HOST,DB_NAME,DB_USER,DB_PASSWORD;[TEM
PLATE]
➢ PLUGIN 调用特定的插件，这些插件可以处理数据库写操作；（例如，
ommysql 插件）。
➢ DB_HOST 属性指定数据库的主机名；
➢ DB_NAME 属性指定数据库名；
➢ DB_USER 属性指定数据库用户；
➢ DB_PASSWORD 属性指定数据库用户的密码；
➢ TEMPLATE 属性指定一个修改syslog 信息的模板。
重要：
目前，rsyslog 只支持MySQL 和PostgreSQL 数据库。为了使用MySQL

和PostgreSQL 数据库写入功能，安装rsyslog-mysql 和rsyslog-pgsql 包。
此外，请确保您在您的/etc/rsyslog.conf 配置文件加载相应的模块：
$ModLoadommysql#OutputmoduleforMySQLsupport
$ModLoad ompgsql #Output module for PostgreSQL
support
更多关于rsyslog 的信息参考7.2.6 使用Rsyslog 模块。
另外，您也可以使用由omlibdb 模块提供通用的数据库接口（支持：
Firebird/Interbase,MSSQL,Sybase,SQLLite,Ingres,Oracle,mSQL）。
丢弃 syslog 消息
想丢弃选择好的消息使用波形符（〜）.
FILTER~
丢弃行为主要是用来在进行任何进一步的处理之前，以筛选出的消息。如果
您想省略一些重复的消息，这是非常有效的，否则重复消息将填充日志文件。丢
弃操作的结果取决于所在的配置文件中的指定配置的位置，为得到最好的结果把
这些行为放到行为列表的前面。请注意，一旦消息被丢弃在后面的配置文件中的
行则没有办法来检索它。
例如，下面的规则丢弃了任何cron 的syslog 消息。
cron.*~
定义多行为
对于每一个选择，您被允许指定多个行为。对一个选择要指定多个操作，每
一个行为写在单独一行，并用符号它前面（＆）字符：

FILTERACTION
&ACTION
&ACTION
指定多个操作提高了所希望结果的整体性能，因此选择器需要被重新评估。
7.2.2.3. 全局指令
全局指令是适用于rsyslogd 守护进程的配置选项。他们通常会指定特定预
定义变量的值，该值会影响rsyslogd 守护进程的行为或遵循的规则。所有全局
指令必须以美元符号（$）。只有一个指令是每行指定。下面是一个全局指令的
例子，其指定系统日志消息队列的最大大小：
$MainMsgQueueSize50000
这个指令默认大小是50000 个消息，可以修改数字重新定义。
您可以在/etc/rsyslog.conf 配置文件中定义多个指令。一个指令影响的所
有配置选项的行为，直到同一个指令的另一事件被检测到。全局指令可以用来配
置行为、队列和调试。所有可用的配置指令的完整列表可以参考在线文档。目前，
一种新的配置格式已经开发出其可以替代$基础的语法（7.2.3 使用新的配置格
式）。然而，经典的全局指令仍然为旧格式提供支持。
7.2.2.4. 日志轮替
下面是一个简单的/etc/logrotate.conf 配置文件例子：
#rotatelogfilesweekly
weekly
#keep4weeksworthofbacklogs
rotate4
#uncommentthisifyouwantyourlogfilescompressed

compress
示例配置文件中所有的行的定义全局选项，其适用于每一个日志文件。在我
们的例子，日志文件每周轮替，轮替的日志文件保留四个星期，所有的轮替日志
文件由gzip 压缩的成.gz 的格式。以#号开头的任何行是注释，不会被处理。
您可以为特定的日志文件定义配置选项，并将其放置在全局选项的下面。然
而，最好是在/etc/logrotate.d/directory 目录下为特定的日志文件创建单独的
配置文件，并在配置文件里配置选项。
下面是一个在/etc/logrotate.d/目录下配置文件的例子：
/var/log/messages{
rotate5
weekly
postrotate
/usr/bin/killall-HUPsyslogd
endscript
}
该文件中的配置选项只为/var/log/messages 日志文件配置。在可能的情
况下，在此指定的设置将覆盖全局设置。因此，轮替的/var/log/messages 日
志文件将保留五周，而不是全局选项定义的四周。
下面是一些指令的列表，您可以在您的日志轮替配置文件里修改。
➢ weekly-指定日志轮替的周期为每周，类似的命令还包括如下：
daily
monthly
yearly
➢ compress-使能轮替文件的压缩功能，类似的命令还包括如下：
nocompress

compresscmd–指定用于压缩的命令。
uncompresscmd
compressext–指定用于压缩的扩展。
compressopttions–指定用于传给程序的压缩选项。
delaycompress–退出压缩直到下一个轮替周期。
➢ rotateINTEGER-指定了一个日志文件被轮替的最大数目，超过数目的
日志文件被删除或邮寄到一个特定的地址。如果指定值0，旧的日志文件被删除，
而不会轮替。
➢ mailADDRESS-此选项，已轮替多次的日志文件的邮件地址。类似的
指令包括：
nomail
maifirst-指定只是轮替的日志文件被邮寄，到期的日志文件不被邮寄。
Maillast-指定到期的日志文件被邮寄，轮替的日志文件不邮寄。当邮寄
功能打开时，此选项是默认选项。
关于个配置选项的完整的指令列表参考logrotata(5)手册页。
7.2.3. 使用新的配置格式
银河麒麟高级服务器操作系统安装的rsyslog 包默认情况下是第8 版，介
绍其新的配置语法。这种新的配置格式的目标是更强大，更直观，通过不允许某
些无效的结构防止常见的错误。语法增强的使能是通过RainerScript 脚本配置
处理器完成。遗留格式仍然得到支持，它默认在/etc/rsyslog.conf 配置文件中
使用。
RainerScript 是一种脚本语言，旨在用于处理网络事件和配置事件处理器，

例如rsyslog。RainerScript 最早是用来定义基于表达式的过滤器，见7.2.2.1
过滤器中有关基于表达式过滤器的章节。RainerScript 在rsyslog7 的版本中实
现了input（）和ruleset（），其允许所述/etc/rsyslog.conf 配置文件被写入
新的语法。新的语法不同之处主要在于，它是更结构化的;参数作为参数传递给
语句，如输入，行为，模板，模块加载。选项的范围由块限制。这增强可读性，
并降低所造成由错误配置的错误的数量。还有一个显著的性能优势。有些功能在
新旧语法都可以用，有的只在新语法可以用。
与旧风格的参数配置比较：
$InputFileName/tmp/inputfile
$InputFileTagtag1:
$InputFileStateFileinputfile-state
$InputRunFileMonitor
同样的配置使用新的配置语句如下：
input(type="imfile"file="/tmp/inputfile"tag="tag1:"
statefile="inputfile-state")
这显著减少在配置中使用的参数的数目，提高了可读性，并且还提供了更高
的运行速度。有关RainerScript 语句和参数的详细信息，请参见在线文档。
7.2.3.1. 规则集
除特殊指令之外，rsyslog 处理的消息由规则定义。规则由过滤条件和如果
条件为真要执行的操作组成。在/etc/rsyslog.conf 文件中的旧规则，所有的规
则以每个输入消息的出场顺序来评估。此过程开始于第一规则，并继续，直到所
有的规则都已经被处理或直到消息被其中某一规则丢弃。
然而，规则可被分成不同序列称为规则集。在规则集中，您可以限制某些规

则的影响，只选择输入或通过定义一组绑定到特定的输入的行为提升rsyslog
的性能。换句话说，某些类型的消息不可避免的被评估为假的，类似这样的过滤
条件可以被跳过。在/etc/rsyslog.conf 中旧规则集定义如下所示：
$RuleSetrulesetname
rule
rule2
当另一个规则被定义，上一个规则就结束了，或者默认规则集被调用，如下：
$RuleSetRSYSLOG_DefaultRuleset
rsyslog8 的新的配置格式，input（）和ruleset（）语句执行被保留。新
格式的规则集在/etc/rsyslog.conf 中定义，如下所示：
ruleset(name="rulesetname"){
rule
rule2
callrulesetname2
…
}
用您规则集的标识符替换rulesetname。该规则集名称不能以RSYSLOG_
开 始 ， 因 为 这 个 名 称 空 间 是 保 留 的 ， 供 rsyslog 使 用 。
RSYSLOG_DefaultRuleset 定义缺省设置的规则集，如果消息没有分配其他规
则集将被执行。在上面提到的rule 和rule2 下，您可以定义过滤-操作的格式规
则。对于调用参数，您可以嵌套规则集由内部或者规则集块调用它们。
创建规则集后，您需要指定它适用于什么输入：
input(type="input_type"port="port_num"
ruleset="rulesetname");
这里可以通过input_type 识别输入消息，这是所收集的信息，或者通过

port_num- 端口号。其他参数，如file 或tag 可以用于指定input（）。用规
则集的名称替换rulesetname。万一某个输入消息未明确在规则集中指定，默
认规则集会被触发。
7.2.3.2. Sysklogd 服务的兼容性
rsyslog 第5 版通过-c 选项指定兼容模式，但在第8 版不支持，同样，
Sysklogd 风格的命令行选项已过时，应避免通过这些命令行选项配置rsyslog。
但是，您可以使用多个模板和指令配置rsyslogd 来模仿像sysklogd 似的行为。
更多关于不同的rsyslogd 选项的信息参考rsyslogd（8）手册页。
7.2.4. 使用 Rsyslog 队列
队列用于在rsyslog 的组件之间传递内容，传递的主要内容是系统日志消息。
在队列机制下，rsyslog 能够同时处理多个消息，并可以用多个行为对单个消息
立刻反应。rsyslog 的数据流说明如下：
图 7-4Rsyslog 中消息流
无论rsyslog 什么时候收到消息，它都把这个消息给预处理，然后将其放置

到主消息队列。消息等在那里待出队并传递到规则处理器。
规则处理器是一个解析和过滤引擎。在此，在/etc/rsyslog.conf 定义的规
则被应用。基于这些规则，该规则处理器评估哪些操作被执行。每个行为都有它
自己的执行队列。消息通过队列传递到行为处理器最终产生相应的输出。注意，
在这一点上，几个行为可以同时对一个消息运行。为了这个目的，一个消息被复
制并传递到多个行为处理器。
只有每个行为一个队列是可能的。根据配置的不同，该消息可以正确发送到
行为处理器而不通过执行队列。这是直接队列（见下文）的行为。在输出操作失
败的情况下，行为处理器通知执行队列，一段时间间隔后，行为处理器又会去取
未处理的消息，再次尝试。
综上所述，rsyslog 队列有两个位置：无论是对与规则处理器作为一个主消
息队列的前面或在各种类型的输出行为作为动作队列的前面。队列有两个主要优
点既都会提高消息处理性能：
➢ 它们充当缓冲器，在rsyslog 的结构中分离生产者和消费者；
➢ 它们允许并行执行消息。
除此之外，队列可以用几种不同的指令被配置来为系统提供最佳的性能。这
些配置选项在以下各节介绍。
警告：
如果输出插件无法传送一个消息，它被存储在前面的消息队列。如果在队列
满时，输入阻塞，直到它不再满。这将阻止新的消息进入被阻塞的消息队列。在
没有单独的执行队列的情况下，这会产生严重的后果，例如阻止SSH 日志记录，

这反过来又阻止SSH 访问。因此，建议为那些通过网络转发到数据库的输出使
用专用的行为的队列。
7.2.4.1. 定义队列
根据消息在哪里存储，分为几种类型的队列：direct，in-memory，disk
和disk-assisted,最广泛使用的队列是in-memory。您可以选择这些类型的其
中 之 一 作 为 主 要 消 息 队 列 ， 也 可 以 作 为 执 行 队 列 。 将 下 面 的 语 句 添 加 到
/etc/rsyslog.conf 文件：
$objectQueueTypequeue_type
在这里，您可以为主消息队列（用MainMsg 替换object）或执行队列（用
action 代 替 object ） 申 请 设 置 。 用 direct ，linkedlist 或 fixedarray
（in-memory 队列），或disk 更换queue_type。
默认设置主消息队列是FixedArray 队列,最大存储10000 个消息。执行队
列默认设置为Direct 队列。
Direct 队列
对于许多简单的操作，例如，输出写入到本地文件，在执行前建立一个队列
是不需要的。为了避免排队，使用如下：
$objectQueueTypeDirect
使用MainMsg 或者Action 替代object,这个选项可以用在主消息队列或
者执行消息队列。对于direct 队列，消息直接传送，它会很快从生产者传送到
消费者。
Disk 队列

disk 队列存储消息到硬盘驱动器，这使得它们高度可靠的，但也是所有可
能的排队模式中最慢的。此模式可用于防止高度重要的日志数据的丢失。但是，
大多数用例中不建议使用disk 队列。要设置disk 队列，在/etc/rsyslog.conf
中键入以下内容：
$objectQueueTypeDisk
使用MainMsg 或者Action 替代object,这个选项可以用在主消息队列或
者执行消息队列。disk 队列写入部分，默认大小为10MB。此默认大小可以通
过下面的配置指令进行修改：
$objectQueueMaxFileSizesize
其中，size 表示disk 队列的大小。定义的大小限制不是强制性的，rsyslog
总是写入一个完整的队列条目，即使违反的大小限制。disk 队列的每个部分有
单独的文件相匹配。这些文件的命名指令如下所示：
$objectQueueFilenamename
这为要设置的文件设置了文件名前缀。其文件名以7 个数字开始，每增加
一个文件数字也增加。
In-memory 队列
在in-memory 队列中，排队的消息被保存在内存中，这使得这个过程非常
快 。 如 果电 脑 重新 加 电或 关机, 则 排队 的数 据 将丢 失 。但 是， 您 可以 使 用
$ActionQueueSaveOnShutdown 设 置 关 机 前 保 存 数 据 。 有 两 种 类 型 的
in-memory 队列：

➢ FixedArrayqueue–默认的主要消息队列，最大10,000 个元素。
这种类型的队列的使用固定预分配的数组保存队列元素的指针。由于这些指
针，即使队列为空也会消耗一定量的存储器。然而，FixedArray 提供了最
佳的运行时性能，当您期望排队的消息相对较少和高性能，此队列是最佳的；
➢ LinkedListqueue–这里，一个链表里所有结构都是动态分配的，
因此，存储器仅在需要时分配。LinkedList 的队列处理偶尔的突发消息非常
好。
在一般情况下，当使用LinkedList 队列相比于FixedArray 队列，它消耗
更少的内存，并降低了处理开销。
配置in-memory 队列使用下面的语法：
$objectQueueTypeLinkedList
$objectQueueTypeFixedArray
使用MainMsg 或者Action 替代object,这个选项可以用在主消息队列或
者执行消息队列。
disk-assisted in-memory 队列
disk 和in-memory 队列都有各自的优点,rsyslog 让您可以将其合并为
disk-assistedin-memory 队列。为此，配置一个正常的in-memory 队列然
后添加$bjectQueueFileName 指令可定义为磁盘援助的文件名。这个队列就
变成disk-assisted，这意味着in-memory 队列与disk 队列通力协作。
如果in-memory 队列已满或者需要关机后消息不丢的disk 队列被激活。
在disk-assisted 队列中，可以同时设置disk-specific 或in-memoryspecific
配置参数。这种类型的队列可能是最常用的，它对可能长时间运行的和不可靠的

操作特别有用。
指定disk-assistedin-memory 队列使用so-called 水印：
$objectQueueHighWatermarknumber
$objectQueueLowWatermarknumber
使用MainMsg 或者Action 替代object,这个选项可以用在主消息队列或
者执行消息队列。使用允许入队的最大消息数替代number。当in-memory
队 列 达 到 了 高 水 位 定 义 的 数 字 ， 它 开 始 将 消 息 写 入 磁 盘 ， 并 一 直 持 续 到
in-memory 队列大小下降到与低水印定义的数量。正确设置水印尽量减少不必
要的磁盘写操作，同时也留下了消息的内存空间，因为写入磁盘文件是相当慢的。
因此，高水位必须比整个队列容量设置$objectQueueSize 低。高水位和整体
队列大小之间的差是专供消息突发而备用的存储器缓冲区。在另一方面，设定高
水位过低会不必要的频繁的打开磁盘援助。
7.2.4.2. 管理队列
所有类型的队列可以进一步配置以满足您的要求。您可以使用几种不同的指
令来修改执行队列和主消息队列。目前，有20 多个队列参数可用，见在线文档。
其中的一些设置是常见的，其他诸如工作线程管理，提供对队列行为多的控制，
保留给高级用户使用。在高级设置中，您可以优化rsyslog 的性能，调度排队，
或修改系统关闭队列的行为。
限制队列大小
您可以限制队列可以包含消息的数量，使用如下设置：
$objectQueueHighWatermarknumber

使用MainMsg 或者Action 替代object,这个选项可以用在主消息队列或
者执行消息队列。用队列可以包含的消息数目替代number。可以设置队列的
大小，而不是作为它们实际存储器的大小。主要消息队列和规则集队列默认队列
大小为10000，执行队列默认大小为1000。
Diskassisted 队列在默认情况下是无限制的，不能用指令来强制大小，但
可以以字节为单位保留他们的物理磁盘空间，使用以下设置：
$objectQueueMaxDiscSpacenumber
使用MainMsg 或者Action 替代object。当队列被填满时，新消息被丢弃，
直到队列释放了足够的空间。
丢弃消息
当队列消息达到一定的数量，为了节省空间，您可以在队列中丢弃不太重要
的信息，保留空间给优先级更高的消息。启动丢弃处理的阈值可设定discard
mark：
$objectQueueDiscardMarknumber
使用MainMsg 或者Action 替代object,这个选项可以用在主消息队列或
者执行消息队列。这里，number 表示要启动丢弃消息进程的消息数目。定义
哪些消息要被丢弃，使用如下：
$objectQueueDiscardSeveritypriority
用下面的关键字之一（或一些）替代priority：debug（7），info（6），
notice（5），warning（4），err（3），crit（2），alert（1）和emerg

（0）。使用此设置，当达到丢弃数目后，比定义优先级较低的新来的和已排队
消息从队列中删除。
使用时限
可以配置rsyslog 在一个特定的时间段处理队列。有了这个选项，您可以转
移部分工作到非高峰时段。要定义一个时间范围，请使用以下语法：
$objectQueueDequeueTimeBeginhour
$objectQueueDequeueTimeEndhour
您可以指定绑定时间的框架，以小时为单位。使用24 小时制，不支持设置
分。
配置工作线程
工作线程执行消息入队的指定操作。例如，在主消息队列，一个工作线程的
任务将滤波器逻辑应用到每个输入消息，并将其排队到相关执行队列。当消息到
达时，一个工作线程将自动启动。当消息的数量达到一定数量时，另一个工作线
程被启动。要指定此数，使用如下：
$objectQueueWorkerThreadMinimumMessagesnumber
用消息的数量替代number 将触发补充工作线程。例如，number 置为100，
当100 多个消息到达时，一个新的工作线程开始。当超过200 个消息到达时，
第三个工作线程启动等。然而，并行运行太多的工作线程是低效的，所以您可以
限制它们的最大数量，如下所示：
$objectQueueWorkerThreadsnumber
number 代表可并行运行的工作线程的最大数目。对于主消息队列，默认

限制为1 个线程。一旦一个线程工作已经开始，它会一直运行直到超时出现。
要设置超时时长，如下所示：
$objectQueueWorkerTimeoutThreadShutdowntime
用持续时间（以毫秒为单位）替换time。如果没有设置time，零超时会被
应用,当它没有消息可以处理时，工作线程立即终止。如果您指定的时间为-1，
没有线程将被关闭。
批量出队列
为了提高性能，您可以配置rsyslog 同时多队列的消息数。设定出队列消息
数目的上限，则使用如下命令：
$objectQueueDequeueBatchSizenumber
用可被同时出队列的消息的最大数目替换number。请注意，出队列消息
数目较高的设置和很多的工作线程同时运行会导致较大的内存消耗高。
终止队列
当要终止的队列中仍然包含信息，您可以尝试通过指定时间间隔使工作线程
来完成队列处理，以减少数据丢失：
$objectQueueTimeoutShutdowntime
指定的time 以毫秒为单位。如果一段时间后仍然有一些排队的消息，工作
线程完成当前的数据处理，然后终止队列。因此，未处理的消息都将丢失。另一
个时间间隔可以让工作线程完成最后的数据处理：
$objectQueueTimeoutActionCompletiontime

在设置的time 超时的情况下，任何工作线程都被关闭。在关机时要保存数
据，可以使用：
$objectQueueTimeoutSaveOnShutdowntime
如果按上面那样设置，所有队列的数据在rsyslogd 终止前都会被存储到磁
盘。
7.2.4.3. 使用rsyslog 队列新语法
从rsyslog7 开始rsyslog 提供了新的语法，队列对象在action（）内部
定义，既可以单独使用或在/etc/rsyslog.conf 中一个规则集使用。执行队列的
格式如下：
action(type="action_type"queue.size="queue_size"
queue.type="queue_type"queue.filename="file_name")
用要执行的操作的模块名称替换action_type，用消息队列可以容纳的最大
数 目 替 代 的 queue_size 。 对 于 queue_type ， 可 以 选 择 disk 队 列 或 从
in-memory 队列中选择一个：direct，LinkedList 的或fixedarray。对于
file_name 仅指定一个文件名，而不是路径。请注意，如果创建一个新的目录来
保存日志文件，SELinux 必须被设置。
7.2.5. 在日志服务器上配置 rsyslog
rsyslog 服务提供的设施可用用来运行日志服务器和配置单个系统来发送
日志文件到日志服务器。请参见实例“可靠的转发日志消息到服务器”，查看客户
端rsyslog 配置信息。
rsyslog 服务必须安装在您打算作为日志服务器的系统上，并且将所有的系

统配置为将日志发送给日志服务器。银河麒麟高级服务器操作系统V10 默认情
况下会安装rsyslog，如果需要，以确保系统确实安装了rsyslog，以root 身
份输入以下命令：
#dnfinstallrsyslog
Syslog 流量默认的协议和端口是UDP 和514，其在/etc/services 文件中
列出。然而，rsyslog 默认使用TCP 在端口514。在配置文件/etc/rsyslog.conf
中，TCP 是由@@声明。
其它的端口在示例有时会用到，然而SELinux 的默认配置为仅允许发送和
接收在下面列出的端口中：
#semanageport-l|grepsyslog
semanage 实用程序由policycoreutils-python-utils 包的一部分提供。
如果需要的话，安装包如下：
#dnfinstallpolicycoreutils-python-utils
此外，在默认情况下SELinux 的rsyslog 类型是rsyslogd_t，其被配置为
允许发送和接收类型是rsh_port_t 的远程shell（RSH）端口，其TCP 默认端
口是514。因此，这是没有必要使用semanage 的明确的允许TCP 端口为514。
例如，要检查SELinux 允许什么程序在端口514 通信，输入命令如下：
#semanageport-l|grep514
请在您打算做日志服务器的系统上，使用下列过程中的步骤。在这些过程中
的所有步骤必须以root 身份来执行命令。

7.2.5.1. 在日志服务器上使用模板
rsyslog 具有多种不同的模板样式。字符串模板最接近旧格式。用字符串格
式生成上面例子中的模板，如下所示：
template(name="TmplAuthpriv"type="string"
string="/var/log/remote/auth/%HOSTNAME%/%PROGRAMNAME:::s
ecpathreplace%.log")
template(name="TmplMsg"type="string"
string="/var/log/remote/msg/%HOSTNAME%/%PROGRAMNAME:::s
ecpathreplace%.log")
这些模板也可以写成下面这样的格式列表：
template(name="TmplAuthpriv"type="list"){
constant(value="/var/log/remote/auth/")
property(name="hostname")
constant(value="/")
property(name="programname"SecurePath="replace")
constant(value=".log")
}
template(name="TmplMsg"type="list"){
constant(value="/var/log/remote/msg/")
property(name="hostname")
constant(value="/")
property(name="programname"SecurePath="replace")
constant(value=".log")
}
对这些rsyslog 新规则来说，这个模板文本格式可能会更容易阅读，因此可
以更容易应用。
要完成新语法的更改，我们需要重新生成模块加载命令，添加规则集，然后
绑定协议，端口和规则集的规则：

module(load="imtcp")
ruleset(name="remote1"){
authpriv.*action(type="omfile"DynaFile="TmplAuthpriv")
*.info;mail.none;authpriv.none;cron.none
action(type="omfile"
DynaFile="TmplMsg")
}
input(type="imtcp"port="10514"ruleset="remote1")
7.2.6. 使用 Rsyslog 模块
由于采用模块化设计，rsyslog 提供各种模块，这些模块提供额外的功能。
需要注意的是模块可以由第三方开发。大多数模块提供额外的输入（见下文输入
模块）或输出（见下文输出模块）。其他模块提供具体到每个模块的特定功能。
加载一个模块后，其可提供可用的附加配置指令。要加载模块，请使用以下语法：
$ModLoadMODULE
其中$ModLoad 是加载指定的模块的全局指令。MODULE 表示您所需的模
块。例如，如果您要加载的文本文件输入模块（imfile），使rsyslog 将任何标
准的文本文件转换成系统日志信息，在/etc/rsyslog.conf 配置文件中指定以下
行：
$ModLoadimfile
rsyslog 提供了许多模块，这些模块被分为以下类别：
➢ 输入模块-输入模块收集来自各种来源的消息。输入模块的名称总是
以im 前缀开始，如imfile 和imjournal。
➢ 输出模块-输出模块提供便利给各种目标发出消息，如通过网络发送，
存储在数据库中，或者加密等消息。输出模块的名称总是以om 前缀，如

omsnmp，omrelp 等等。
➢ 解析模块-这些模块在创建自定义解析规则，或者解析异常的消息是
有用的。使用C 语言的中间层，您可以创建自己的消息解析器。解析器模块
的名称总是以pm 前缀开始，如pmrfc5424，pmrfc3164，等。
➢ 消息修改模块-消息修改模块修改系统日志消息的内容。这些模块的
名称以mm 的前缀开始。消息修改的模块如mmanon，mmnormalize，
或mmjsonparse 其用于匿名或消息的正常化。
➢ 字符串生成模块-串生成模块生成基于消息内容的字符串，并与
rsyslog 提供的模板功能相互协同。串生成模块的名称总是以sm 前缀开始，
如smfile 或smtrad 文件。
➢ 库模块-库模块为其他可加载模块提供功能。这些模块在rsyslog 需
要时自动被加载，不能由用户配置。
所 有 可 用 的 模 块 和 它 们 的 详 细 描 述 的 完 整 列 表 可 以 在
http://www.rsyslog.com/doc/rsyslog_conf_modules.html 找到。
警告：
需要注意的是rsyslog 加载的模块，这使他们可以访问自己的函数和数
据。这会带来潜在的安全威胁。为了最大限度地减少安全风险，仅使用可信
赖模块。
7.2.6.1. 导入text 文件
文本文件输入模块，简称imfile，使rsyslog 将任意文本文件转化成系统日
志消息流。您可以使用imfile 从创建自己的文本文件日志的应用程序中导入日

志消息。要加载imfile，在/etc/rsyslog.conf 添加以下内容：
$ModLoadimfile
$InputFilePollIntervalint
它足以加载imfile 一次，导入多个文件时也是如此。$InputFilePollInterval
全局指令指定rsyslog 检查被连接的文本文件变化的频率。默认的时间间隔为
10 秒，要改变它，用以秒为单位的时间间隔替换int。
为了确定文本文件导入，在/etc/rsyslog.conf 文件中使用的语法如下：
#File1
$InputFileNamepath_to_file
$InputFileTagtag:
$InputFileStateFilestate_file_name
$InputFileSeverityseverity
$InputFileFacilityfacility
$InputRunFileMonitor
#File2
$InputFileNamepath_to_file2
...
需要4 个设置来指定一个输入文件：
➢ 用文本文件路径替代path_to_file
➢ 用消息的tag 名替代tag
➢ 用唯一的状态文件名替代state_file_name。状态文件都存储在
rsyslog 的工作目录，为监控的文件保持标记，标记已经被处理完的分区。
如果删除它们，整个文件将被重新读入。请确保您指定了一个不存在的名称。
➢ 添加$InputRunFileMonitor 指令使能文件监控。没有这个设置，
文本文件会被忽略。

除了所要求的指令，存在可以应用到文本输入的几个其它设置。通过用适当
的关键字替换关键字severity 来设置导入消息的严重性。用关键字替换facility
来定义生成消息的子系统。
7.2.6.2. 从数据库导出消息
在数据库中，处理日志数据比处理文本文件可以更快，更方便。基于所使用
的DBMS 的类型，选择各种输出模块，例如ommysql，ompgsql，omoracle，
或ommongodb。作为替代，使用依赖于libdbi 库的通用omlibdbi 输出模块。
omlibdbi 模块支持的数据库系统有Firebird/Interbase，MSSQL，Sybase，
SQLite，ingres，甲骨文，mSQL，MySQL 和PostgreSQL。
7.2.6.3. 使能加密传输
网络传输的机密性和完整性可以通过TLS 或GSSAPI 加密协议来提供。
传输层安全性（TLS）是旨在提供通过网络通信的安全性的加密协议。当使
用TLS，rsyslog 消息被发送之前加密，发送者和接收者之间的需要相互认证。
通用安全服务API（GSSAPI）是一种为程序访问安全服务的应用程序编程
接口。为了在与rsyslog 的连接中使用，您必须有一个正常的Kerberos 环境。
7.2.7. Syslogd 服务和日志的交互
如上所述，rsyslog 和journal，您系统上的两个日志应用程序，有几个鲜
明的特点，使它们适用于特定的用例。在很多情况下（见7.3Syslogd 日志结构），
它们可以相互合作，比如创建结构化的信息，并将它们存储在一个文件数据库中。
需要这种合作的一个通信接口是由输入和输出模块提供的，这些模块由Rsyslog

和journal 的通信socket 支持。
默认情况下，rsyslogd 使用imjournal 模块作为日志文件的默认的输入模
式。使用这个模块，您不仅可以导入消息，也可以导入由Journald 提供的结构
化 数 据 。 此 外 ， 旧 的 数 据 可 以 从 journald 导 入 （ 除 非 禁 止 用
#ImjournalIgnorePreviousMessages 指令）。参考7.3.1 从日志中导入数
据部分关于imjournal 的基本配置。
作为替代，配置rsyslogd 来读取由journal 提供的套接字作为一个基于
syslog 应用的输出。套接字的路径/run/systemd/journal/syslog。当您想维
护rsyslog 消息，请使用此选项。相比imjournal，套接字输入提供更多的功能，
如绑定规则集或过滤器。要通过套接字导入日志数据，在/etc/rsyslog.conf 中
使用以下配置：
$ModLoadimuxsock
$OmitLocalLoggingoff
上述语法加载imuxsock 模块并关闭$OmitLocalLogging 指令，使能通
过系统套接字导入。套接字的路径分别在/etc/rsyslog.d/listen.conf 中指定，
如下：
$SystemLogSocketName/run/systemd/journal/syslog
您 也 可 以 从Rsyslog 输 出消 息 到 使用omjournal 模 块的journal 。 在
/etc/rsyslog.conf 中配置输出如下：
$ModLoadomjournal
*.*:omjournal:
例如，下面的配置转发所有收到的信息通过TCP 端口10514 到journal：

$ModLoadimtcp
$ModLoadomjournal
$RuleSetremote
*.*:omjournal:
$InputTCPServerBindRulesetremote
$InputTCPServerRun10514
7.3. Syslogd 日志结构
在产生大量的日志数据的系统上，一个结构化格式的日志信息可以方便地被
维护。通过结构化的信息，很容易搜索特定信息，生成统计信息和处理消息的改
变和不一致。rsyslog 使用JSON（JavaScript 对象符号）格式提供日志信息的
结构化。
比较下面非结构化的日志消息：
Oct2510:20:37localhostanacron[1395]:Jobswillbeexecuted
sequentially
下面是结构化的日志消息：
{"timestamp":"2020-2-7T10:20:37","host":"localhost",
"program":"anacron", "pid":"1395", "msg":"Jobs will be
executed
sequentially"}
使用键值对搜索结构化数据比使用正则表达式搜索文本文件的速度更快更
精确。结构化消息还可以让您搜索各种应用程序生成的相同的消息。另外，JSON
文件可以存储在文档数据库，如MongoDB，它提供了额外的性能和分析功能。
另一方面，一个结构化的消息需要比非结构化的消息更多的磁盘空间。
在rsyslog，带有元数据的日志信息被使用imjournal 模块的journal 推出。
使用mmjsonparse 模块，可以解析来自journal 和其他来源导入的数据，并

进 一 步 对 其 进 行 处 理 ， 例 如 ， 作 为 一 个 数 据 库 输 出 。 为 了 使 解 析 成 功 ，
mmjsonparse 要求输入消息是结构化的，向其在lumberjack 项目定义的那样。
Lmberjack 项目的目的是以向后兼容的方式为rsyslog 增加结构化日志记
录。要确定一个结构化的消息，Lmberjack 指定@cee：字符串，其前置JSON
结构。另外，Lmberjack 定义了应该用在JSON 串标准字段名称的列表。有关
Lmberjack 的更多信息，请参见在线文档。
下面是lumberjack 格式消息的例子：
@ cee: {"pid":17055, "uid":1000, "gid":1000,
"appname":"logger",
"msg":"Messagetext."}
要在Rsyslog 中构建这个结构，一个模板会被使用，请参见7.3.2 过滤结
构化消息。应用程序和服务器可以采用libumberlog 库来生成lumberjack 兼
容形式的消息。有关libumberlog 的更多信息，请参见在线文档。
7.3.1. 从日志中导入数据
imjournal 模 块 是 rsyslog 的 输 入 模 块 用 来 读 取 日 志 文 件 （ 见
7.2.7SSyslogd 服务和日志的交互。作为其它的rsyslog 消息，日志消息以文
本格式被记录。然而，随着进一步的处理，其能够将由journal 提供的元数据翻
译成结构化的消息。
为了从Journal 导入数据到Rsyslog，在/etc/rsyslog.confi 文件中使用下
面配置：
$ModLoadimjournal
$imjournalPersistStateIntervalnumber_of_messages
$imjournalStateFilepath

$imjournalRatelimitIntervalseconds
$imjournalRatelimitBurstburst_number
$ImjournalIgnorePreviousMessagesoff/on
➢ 使用number_of_messages，您可以指定保存日志数据的频率。
每当达到number_of_messages 指定的消息数时，就会触发数据保存；
➢ 用状态文件的路径替代path。此文件跟踪journal 记录，其是处理
的最后一个记录；
➢ 使用seconds，设置的限制速率的时间间隔。此间隔期间处理的消
息的数量不能超过在burst_number 指定的值。默认设置为每600 秒
20000 的消息。Rsyslog 会丢弃在规定的时限内超过最大burst_number
值的消息；
➢ 使 用#ImjournalIgnorePreviousMessages 可 以 忽 略 当 前 在
journal 的消息和只导入新的消息，这些消息当未指定状态文件时被使用。
默认设置为关闭。请注意，如果该设置是关闭的，没有状态文件，所有journal
的消息会被处理，即使他们已经在rsyslog 以前的会话中被处理过。
注意：
您可以同时使用imjournal 和imuxsock 模块，imuxsock 模块是旧的系
统日志输入。然而，为了避免消息重复，您必须阻止imuxsock 读取journal
的系统套接字。要做到这一点，使用$OmitLocalLogging 指令：
$ModLoadimuxsock
$ModLoadimjournal
$OmitLocalLoggingon
$AddUnixListenSocket/run/systemd/journal/syslog

您可以通过将存储在journal 的数据和元数据翻译成结构化消息。其中的一
些元数据项在本章实例“journalctl 的verbose 输出”中列出，想获取journal
域的完整列表，请参阅systemd.journal-fields（7）手册页。例如，可以将重
点放在内核jouranl 字段，该域被内核初始生成的消息使用。
7.3.2. 过滤结构化消息
要创建一个rsyslog 解析模块需要的lumberjack 格式的消息，请使用以
下模板：
template(name="CEETemplate" type="string"
string="%TIMESTAMP%%HOSTNAME%
%syslogtag%@cee:%$!all-json%\n")
这个模板预先考虑@cee：可以应用的JSON 字符串，例如，当创建使用
omfile 模块的输出文件时。要访问JSON 字段名称，使用$！前缀。例如，搜索
符合下面的筛选条件的消息，指定了消息的主机名和UID。
($!hostname=="hostname"&&$!UID=="UID")
7.3.3. 解析 JSON
mmjsonparse 模块用于解析结构化消息。这些信息可以来自journal 或其
他输入源，并且必须由lumberjack 项目中定义的方式进行格式化。这些消息是
由@cee：字符串表示而被识别的。然后，mmjsonparse 会检查JSON 结构是
否是有效的，然后该消息被解析。
为 了 用mmjsonparse 模 块 解 析lumberjack 格 式 的JSON 消 息 ， 在
/etc/rsyslog.conf 文件下使用下面的配置：

$ModLoadmmjsonparse
*.*:mmjsonparse:
在这个例子中，mmjsonparse 模块在第一行被加载，所有的消息转发给它。
目前，没有可用的配置参数用于mmjsonparse。
7.3.4. 向 MongoDB 中存储消息
rsyslog 支持通过ommongodb 输出模块在MongoDB 的文档型数据库中
存储JSON 日志。
要 转 发日志消息到MongoDB 中，在/etc/rsyslog.conf 使用以下语法
（ommongodb 配置参数只适用于新配置格式;见7.2.3 使用新的配置格式）
$ModLoadommongodb
*.* action(type="ommongodb" server="DB_server"
serverport="port"
db="DB_name" collection="collection_name" uid="UID"
pwd="password")
➢ 用MongoDB 服务器的地址或者名字替代DB_server.配置端口需
要从MongoDB 的服务器选择一个非标准的端口。默认端口值是0，并且通
常没有必要改变该参数；
➢ 使用DB_NAME，确定您想直接输出到MongoDB 的服务器上的哪
个数据库。用这个数据库集合的名称替换collection_name。在MongoDB
中，集合是一组文档，它和一个RDBMS 表是等效的；
➢ 您通过设置UID 和password 来输入您的细节信息。
您可以使用模板来塑造最终数据库的输出形式。默认情况下，rsyslog 使用
的模板基于标准的lumberjack 字段名称。

7.4. 调试 Rsyslog
在debug 模式运行rsyslogd,使用如下命令：
rsyslogd-dn
使用此命令，rsyslogd 产生调试信息并打印到标准输出。-n 表示“nofork”。
您可以修改调试环境变量，例如，可以在日志文件中存储调试输出信息。在启动
rsyslogd 之前，在命令行上执行以下操作：
exportRSYSLOG_DEBUGLOG="path"
exportRSYSLOG_DEBUG="Debug"
用 所 需 记 录 调 试 信 息 文 件 的 位 置 替 换 path 。 对 于 可 用 于
RSYSLOG_DEBUG 变量选项的完整列表，请参阅rsyslogd（8）手册的相关章
节。
检查在/etc/rsyslog.conf 文件中使用的语法是否有效：
rsyslogd-N1
其中，1 表示输出消息的详细程度的级别。这是前向兼容性选项，因为目前，
只有一个级被提供。但是，您必须添加此参数来运行验证。
7.5. 使用日志
journal 是systemd 的一个组件，它负责查看和管理日志文件。它可并行
使用或者代替一个旧的syslog 守护进程，如rsyslogd。journal 的开发是为了
解决与旧的日志记录有关的问题。它紧密地与系统的其余部分集成，支持多种日
志记录技术和访问管理日志文件。
记录数据由journald 服务收集，存储，并处理。它创建和维护名为journals

的二进制文件，这些记录的信息来自内核，用户进程，标准输出，标准错误输出，
或通过原生API 的标准错误输出。这些journals 被结构化并被索引，它提供了
比较快的查询道时间。journal 条目有一个唯一的标识符。journald 服务收集每
个日志消息众多的元数据字段。日志文件是安全的，不能被手动编辑。
7.5.1. 查看日志文件
为了访问journal 日志，使用journalctl 工具。以root 身份查看日志类型
的基本信息：
journalctl
此命令的输出是系统所有日志文件的列表，包括系统组件和用户产生的消息。
该输出的结构类似于/var/log/messages 文件中消息结构，但有一定的改进：
➢ 记录优先级的标记是可见的。错误的优先级和更高的优先级使用了
红色，通知和警告优先线使用加粗的字体；
➢ 时间戳转换为系统的本地时区；
➢ 所有记录数据都会显示，包括轮替的日志；
➢ 引导的开始将被标上专用线。
7.5.2. 访问控制
默认情况下，没有root 权限的journal 用户，只能查看由它们产生的日志
文件。系统管理员可以添加选定用户到adm 组，授予他们访问完整日志文件的
权限。要做到这一点，以root 用户输入如下：
usermod-a-Gadmusername

用要添加到adm 组的用户名替代username。该用户随后接收journalctl
命令的输出同root 用户获取的输出一样。请注意，访问控制只有当为journal
启用了持久存储才会工作。
7.5.3. 使用 Live view
当不带参数调用，journalctl 从收集到的最早的条目开始，显示条目的完整
列表。有了liveview，您可以监控实时的日志消息，因为新的条目出现就会被
打印出来。要开启journalctl 的liveview 模式，输入：
journalctl-f
该命令返回十个最新的日志行列表。journalctl 工具将保持运行，等待新的
消息并立即显示他们。
7.5.4. 过滤消息
不带参数执行journalctl 命令的输出信息是很多的，因此您可以使用各种
过滤方法来提取信息，以满足您的需求。
通过优先级过滤
日志消息通常用于跟踪系统上错误的行为。要查看选定的或更高优先级消息，
请使用以下语法：
journalctl-ppriority
在这里，用下面的关键字之一（或数字）替换优先级：debug（7），info
（6），notice（5），warning（4），err（3），crit（2），alert（1），
和emerg（0）。

实例：通过优先级过滤
查看优先级为error 或更高的消息，使用：
journalctl-perr
通过时间过滤
要查看仅从当前引导的日志条目：
journalctl-b
如果您偶尔会重新启动系统，-b 不会显著减少journalctl 的输出。在这样
的情况下，基于时间的滤波是更有帮助：
journalctl--since=value--until=value
使用--since 和--until 选项。您可以查看指定时间范围内创建的日志信息。
您可以以time 的格式或者data 的格式或者两者都可传递value 给这两个选项，
如以下示例。
实例：通过优先级和时间过滤
根据具体的要求过滤选项可以组合，以减少的结果输出。例如，从某个时间
点，查看警告或更高优先级的消息，如下：
#journalctl-pwarning--since="2020-3-523:59:59"
高级过滤
在本章实例“journalctl 的verbose 输出”列出指定日志条目的一组字段，
其 都 可 以 用 于 过 滤 。 对 于systemd 可 存 储 的 元 数 据 的 完 整 说 明 ， 参 见
systemd.jounal-fields（7）手册页。每个日志信息的元数据都被收集，而无

需用户干预。值通常是基于文本的，但可以采取二进制和大的值;虽然不是很常
见的，但是字段可以分配多个值。
要查看发生在一个特定领域产生的唯一值的列表，请使用以下语法：
journalctl-Ffieldname
用您想要的字段名替代fieldname。
要显示出满足特定条件日志条目，请使用以下语法：
journalctlfieldname=value
用字段名和该字段包含的值替代fieldname 和value。结果，符合这个条
件的行会输出。
注意：
如通过systemd 存储的元数据字段的数量是相当大的，它很容易忘记感兴
趣的字段的确切名称。如果不能确定，请键入：
journalctl
按两次tab 键。这会显示出可用的字段名。Tab 键名称补齐是基于该字段的
上下文选项完成的，您可以输入一部分字母，然后按Tab 键来自动完成这个名
字。同样，您可以从一个字段中列出唯一值。如下：
journalctlfieldname=
按tab 键两次，就会像输入journalctl–Ffieldname 一样。
您可以在一个字段自定多个值，如下：
journalctlfieldname=value1fieldname=value2...

同一个字段指定两个可以匹配的值，像逻辑or 一样匹配。符合匹配值1 或
值2 的条目会显示。
当然，您可以指定多个field-value 对来进一步减少输出：
journalctlfieldname1=valuefieldname2=value...
如果指定了两个不同的字段名的匹配，它们将与逻辑AND 组合。必须匹配
这两个条件的条目才会显示。
使用+号，您可以设置逻辑or 组合匹配多字段：
journalctlfieldname1=value+fieldname2=value...
该命令返回至少匹配一个条件的条目，不仅是满足所有条件的条目。
实例：高级过滤器设置
要显示通过avahi-daemon 或crond.service 创建的条目。且该条目的用
户UID 为70，请使用以下命令：
journalctl _UID=70 _SYSTEMD_UNIT=avahi-daemon.service
_SYSTEMD_UNIT=crond.service
由于_SYSTEMD_UNIT 字段可以设置两个值，匹配两者的结果将显示，但
_UID=70 条件必须被满足。这可以简单地表示为（UID=70and(avahior
cron)）。
您可以应用到前面提到的过滤器，其以live-view 模式来跟踪选定组的日志
消息的变化：
journalctl-ffieldname=value...

7.5.5. 使能持续存储
默认情况下，journal 只在内存中或/run/log/journal/目录下的小环形缓冲
区中存储日志文件。使用journalctl 显示最近历史日志就足够了。该目录有易
失性，日志数据不会永久保存。在默认配置下，syslog 读取日志记录，并将其
存 储 在/var/log/ 目 录 下 。 若 持 续 性 日 志 记 录 开 启 ， 日 志 文 件 存 储 在
/var/log/journal，这意味着重新启动后他们仍然存在。对一些用户，journal
能代替rsyslog。
使能持续性存储有下面的优点：
➢ 较长时间的可用来解决问题的更丰富的数据会被记录；
➢ 对于需要立刻解决的问题，重启后可以获取更丰富的可用的数据；
➢ 服务器控制台当前从日志中读取数据，而不是日志文件。
持续性存储也有某些缺点：
➢ 持续性存储所存储的数据量取决于空闲的内存，不保证可以覆盖特定的
时间跨度；
➢ 需要更多的磁盘空间存储日志文件。
journal 启用持续性存储，按下面所示的例子手动创建journal 目录，以root
用户输入：
mkdir-p/var/log/journal
重启journald 来是设置生效。
systemctlrestartsystemd-journald

7.6. 自动化系统任务
任务，也被称为工作，可以通过配置，在一个指定的日期，在一个指定的时
间周期内，或者当系统的平均负载低于0.8 时，自动地运行。
银河麒麟高级服务器操作系统预先配置了运行一些重要的系统任务，以保持
系统的更新。例如，locate 命令使用的slocate 数据库每天都会更新。系统管
理员可以使用自动任务来执行周期性的备份、监控系统、运行自定义脚本等等。
银河麒麟高级服务器操作系统提供了以下自动任务工具：cron、anacron、
at 和batch。
每一种工具都是为了调度不同的任务类型而被设计的，Cron 和Anacron
调度重复发生的任务，At 和Batch 调度一次性的任务（请分别参考7.6.1Cron
和Anacron 和7.6.8At 和Batch）。
银河麒麟高级服务器操作系统V10 支持使用systemd.timer 在一个指定
的时间执行一个任务。参见systemd.timer(5)用户手册页面了解更多信息。
7.6.1. Cron 和 Anacron
Cron 和Anacron 都是能够调度重复性任务在一个确定的时间点执行的守
护进程，时间点是通过准确的时间、月份中的某天、月份、一周中的某天、周来
定义的。
Cron 的任务可以每分钟都运行。但是，这个工具假定系统是持续运行的，
如果在任务被安排的时间系统并没有运行，则任务不会被执行。
另一方面，如果系统在任务被安排的时间被没有运行，Anacron 会记住这
个已经被安排的任务。一旦系统开始运行，这个任务将被马上执行。然而，

Anacron 对于一个任务一天只能运行一次。
7.6.2. 安装 Cron 和 Anacron
要安装Cron 和Anacron，您需要安装Cron 的cronie 包和Anacron 的
cronie-anacron 包（cronie-anacron 是cronie 的一个子包）。
要确定您的系统上是否已经安装了相应的包，可以执行以下命令：
rpm-qcroniecronie-anacron
如果已经安装了，上述命令将返回cronie 包和cronie-anacron 包的完整
名称，否则将通知您相应的包不可用。
要安装这些包，以root 用户按照下面的格式来使用dnf 命令：
dnfinstallpackage
例如，要同时安装Cron 和Anacron，可以在命令行提示符下输入以下命
令：
#dnfinstallcroniecronie-anacron
要了解如何在银河麒麟高级服务器操作系统中安装新的包，请参见4.2.4 安
装软件包。
7.6.3. 运行 Crond 服务
cron 和anacron 任务都是由crond 服务来控制的。本节将说明如何启动、
停止和重启crond 服务，以及如何配置让其开机自启动。要了解更多有关在银
河麒麟高级服务器操作系统V10 中如何管理服务的通用方法，请参见5.1 使用
systemd 管理系统服务。

7.6.3.1. 启动Cron 服务
要确定服务是否正在运行，请使用以下命令：
systemctlstatuscrond.service
要在当前会话中运行crond 服务，请以root 用户在命令行提示符下输入以
下命令：
systemctlstartcrond.service
要配置服务开机自启动，请以root 用户执行以下命令：
systemctlenablecrond.service
7.6.3.2. 停止Cron 服务
要在当前会话中停止crond 服务，请以root 用户在命令行提示符下输入以
下命令：
systemctlstopcrond.service
要禁止服务开机自启动，请以root 用户执行以下命令：
systemctldisablecrond.service
7.6.3.3. 重启Cron 服务
要重启crond 服务，请以root 用户在命令行提示符下输入以下命令：
systemctlrestartcrond.service
该命令停止服务后将很快地再次启动服务。
7.6.4. 配置 Anacron 任务
调度任务的主要配置文件是/etc/anacrontab 文件，它只能通过root 用户

进行访问。该文件包含以下内容：
SHELL=/bin/sh
PATH=/sbin:/bin:/usr/sbin:/usr/bin
MAILTO=root
#themaximalrandomdelayaddedtothebasedelayofthe
jobs
RANDOM_DELAY=45
#thejobswillbestartedduringthefollowinghoursonly
START_HOURS_RANGE=3-22
#periodindays delayinminutes job-identifier
command
1 5 cron.daily nicerun-parts
/etc/cron.daily
7 25 cron.weekly nicerun-parts
/etc/cron.weekly
@monthly45 cron.monthly nicerun-parts
/etc/cron.monthly
前三行定义用来配置anacron 任务运行环境的相关变量：
● SHELL——用来运行任务的shell 环境（示例中是Bashshell）；
● PATH——可执行程序的路径；
● MAILTO——通过电子邮件接收anacron 任务输出的用户的名称；
如果没有定义MAILTO 变量（MAILTO=），则不会发送邮件。
接下来的两个变量用来修改已定义任务的调度时间：
● RANDOM_DELAY——将会加到为每个任务指定的delayinminutes
变量上的最大分钟数；
默认情况下，最小延迟的值被设置为6 分钟。

例如，如果RANDOM_DELAY 被设置为12，那么这个特定的anacrontab
中 的 每 一 个 任 务 的 delay in minutes 值 都 将 被 加 上 6 到 12 分 钟 。
RANDOM_DELAY 的值也可以设置为6 以下，包括0。当设置为0 的时候，则
不会增加随机延迟。随机延迟被证明是很有用的，例如，当多台共享一个网络连
接的计算机需要每天都下载相同的数据时。
● START_HOURS_RANGE——计划任务可以运行的时间段，以小时为单
位；
万一错过了这个时间段，例如，由于停电等原因，则当天的计划任务不会被
执行。
/etc/anacrontab 文件的剩余行代表计划任务，并且遵从以下格式：
periodindays delayinminutes job-identifier
command
● periodindays——按天数计的任务执行频率；
该属性值可以被定义为一个整数或者一个宏（@daily、@weekly 或者
@monthly），@daily 和整数1 表示的是同一个值，@weekly 和整数7 一样，
而@monthly 则表示任务一个月只运行一次，不管这个月的天数是多少。
● delayinminutes——在执行任务前，anacron 等待的分钟数；
该属性值可以被定义为一个整数。如果该值被设置为0，则表示没有延迟。
● job-identifier——用于日志文件中关联一个特定任务的唯一名称；
● command——将被执行的命令；
这里的命令既可以是一个类似ls/proc>>/tmp/proc 的命令，也可以是一

个执行自定义脚本的命令。
以井号（#）开头的所有行都是注释，不会被处理。
7.6.4.1. Anacron 任务示例
以下是一个简单的/etc/anacrontab 文件的示例：
SHELL=/bin/sh
PATH=/sbin:/bin:/usr/sbin:/usr/bin
MAILTO=root
#themaximalrandomdelayaddedtothebasedelayofthe
jobs
RANDOM_DELAY=30
#thejobswillbestartedduringthefollowinghoursonly
START_HOURS_RANGE=16-20
#periodindays delayinminutes job-identifier
command
1 20 dailyjob nicerun-parts
/etc/cron.daily
7 25 weeklyjob
/etc/weeklyjob.bash
@monthly45 monthlyjob ls/proc>>
/tmp/proc
在这个anacrontab 文件中定义的所有任务，都将随机的延迟6 到30 分钟，
并且将在16:00 到20:00 之间被运行。
第 一 个 被 定 义 的 任 务 将 在 每 天 16:26 到 16:50 间 被 触 发
（RANDOM_DELAY 是在6 到30 分钟之间；delayinminutes 属性值为20
分钟）。该任务所指定的命令将使用run-parts 脚本（run-parts 脚本接受一个

目录作为命令行参数，并顺序地执行目录中的每一个程序）执行/etc/cron.daily/
目录下的所有程序。参见run-parts 用户手册页面了解更多有关run-parts 脚
本的信息。
第二个任务每周执行一次/etc/目录下的weeklyjob.bash 脚本。
第三个任务运行一个命令，该命令把/proc 的内容写到/tmp/proc 文件里，
每个月一次（ls/proc>>/tmp/proc）。
7.6.5. 配置 Cron 任务
Cron 任务的配置文件是/etc/crontab 文件，它只能通过root 用户进行访
问。该文件包含以下内容：
SHELL=/bin/bash
PATH=/sbin:/bin:/usr/sbin:/usr/bin
MAILTO=root
HOME=/
#Fordetailsseeman4crontabs
#Exampleofjobdefinition:
#.----------------minute(0-59)
#| .-------------hour(0-23)
#| | .----------dayofmonth(1-31)
#| | | .-------month(1-12)ORjan,feb,mar,apr...
#| | | | .----dayofweek(0-6)(Sunday=0or7)OR
sun,mon,tue,wed,thu,fri,sat
#| | | | |
#* * * * *user-name commandtobeexecuted
前三行包含了和anacrontab 文件一样的变量定义：SHELL、PATH 和

MAILTO。要了解更多有关这几个变量的信息，请参见7.6.4 配置Anacron 任
务。
此外，该文件还可以定义HOME 变量。HOME 变量定义一个目录，该目录
在任务执行命令或脚本时将被作为家目录。
/etc/crontab 文件的剩余行代表计划任务，并遵从以下格式：
minute hour day month dayofweek
username command
以下各项定义了任务将要运行的时间：
● minute——从0 到59 的任意整数；
● hour——从0 到23 的任意整数；
● day——从1 到31 的任意整数（如果指定了月份，则这里的日期必须
是相对于该月有效的日期）；
● month——从1 到12 的任意整数（或者月份的简短名称，例如jan 或
者feb）；
● dayofweek——从0 到7 的任意整数，0 或7 代表星期日（或者使用
每周各天的简短名称，例如sun 或者mon）；
以下各项定义了任务的其它属性：
● username——指定执行任务的用户；
● command——将要执行的命令。
这里的命令既可以是一个类似ls/proc>>/tmp/proc 的命令，也可以是一
个执行自定义脚本的命令

对于以上的各项属性值，星号（*）可以用来表示所有的有效值。例如，如
果您将month 的值定义为星号，则该任务将在其他条件的约束下每个月都执行。
整数之间的连字号（-）代表了一个整数范围。例如，1-4 表示整数1、2、
3、4。
用逗号（,）分隔的值列表指定了一个列表。例如，3,4,6,8 就确实指明了这
四个整数。
斜杠（/）可以用来指定步长值。指定了步长值的项将按步长所定义的整数
来增长。例如，minute 项的值定义为0-59/2，则表示每隔一分钟。步长值也
可以和星号一起使用。例如，如果month 项的值被定义为*/3，则任务将每三
个月执行一次（每隔两个月）。
以井号（#）开头的所有行都是注释，不会被处理。
非root 用 户 可 以 使 用crontab 工 具 来 配 置cron 任 务 。 用 户 定 义 的
crontabs 被保存在/var/spool/cron/目录下，并且以创建它们的用户来执行。
要以一个特定用户来创建crontab，请以该用户进行登录，并且输入命令
crontab-e 来使用VISUAL 或者EDITOR 环境变量所指定的编辑器对用户的
crontab 进行编辑。该文件使用和/etc/crontab 完全相同的格式。当用户对
crontab 的 修 改 被 保 存 时 ，crontab 按 照 用 户 名 来 保 存 ， 并 写 入
/var/spool/cron/username 文件中。要列出当前用户的crontab 文件的内容，
使用crontab-l 命令。
/etc/cron.d/目录下包含的文件和/etc/crontab 文件具有相同的语法。只有
root 用户被允许在该目录下创建和修改文件。

cron 守护进程会每分钟检查/etc/anacrontab 文件、/etc/crontab 文
件、/etc/cron.d/目录和/var/spool/cron/目录的变化，并把检测到的修改加载
到内存中。因此，当一个anacrontab 或crontab 文件被修改后，并不需要重
启守护进程。
7.6.6. 控制对 Cron 的访问
要限制对Cron 的访问，您可以使用/etc/cron.allow 和/etc/cron.deny 文
件。这些访问控制文件使用相同的格式，都是每一行一个用户名。记住，两个文
件都不允许使用空格。
如果存在cron.allow 文件，只有在该文件中列出的用户才能使用cron，并
且cron.deny 文件将被忽略。
如果cron.allow 文件不存在，则cron.deny 文件中列出的用户将被禁止使
用Cron。
如果访问控制文件被修改了，不必重启Cron 守护进程（crond）。每次用
户试图添加或删除一个cron 任务时，都会检查访问控制文件。
不管访问控制文件中的用户名是什么，root 用户都始终能够使用cron。
您 也 可 以 通 过 插 入 式 认 证 模 块 （Pluggable Authentication
Modules,PAM）来控制访问。相应的设置保存在/etc/security/access.conf
文件中。例如，在文件中添加如下一行之后，将只有root 用户能够创建crontabs：
-:ALLEXCEPTroot:cron
被禁止的任务将被记录在适当的日志文件中，或者，当使用crontab-e 命

令时，返回到标准输出里。要了解更多信息，请参考access.conf.5 用户手册
页面。
7.6.7. Cron 任务的黑白名单
任务的黑白名单用来定义任务的某部分不需要被执行。当在一个Cron 目录
里（例如，/etc/cron.daily/）调用run-parts 脚本时这很有用：如果用户把这
个目录下的程序加入了黑名单，run-parts 脚本将不会执行这些程序。
要 创 建一个黑名单，请在run-parts 脚本将要执行的目录中创建一个
jobs.deny 文件。例如，如果您需要从/etc/cron.daily/目录中忽略一个特定的
程序，请创建/etc/cron.daily/jobs.deny 文件。在这个文件中，指定需要在执
行时被忽略的程序的名称（只有位于同一个目录下的程序能够加入列表）。如果
一 项 任 务 执 行 一 个 命 令 ， 该 命 令 从/etc/cron.daily/ 目 录 执 行 程 序 ， 例 如
run-parts/ect/cron.daily，则jobs.deny 文件中定义的程序不会被执行。
要定义一个白名单，请创建jobs.allow 文件。
jobs.deny 和jobs.allow 的使用规则和8.7.6 控制对Cron 的访问中描述
的cron.deny 和cron.allow 的使用规则一样。
7.6.8. At 和 Batch
Cron 被用来调度重复性任务，At 工具被用来在一个指定的时间调度一次性
任务，Batch 工具被用来调度将在系统平均负载低于0.8 时被执行的一次性任
务。

7.6.8.1. 安装At 和Batch
要确定您的系统上是否已经安装了at 包，请执行以下命令：
rpm-qat
如果已经安装了，上述命令将返回at 包的完整名称，否则将通知您包不可
用。
要安装程序包，以root 用户按照下面的格式来使用dnf 命令：
dnfinstallpackage
例如，要同时安装At 和Batch，可以在命令行提示符下输入以下命令：
#dnfinstallat
7.6.8.2. 运行At 服务
At 和Batch 任务都是由atd 服务来控制的。本节将说明如何启动、停止和
重启atd 服务，以及如何配置让其开机自启动。
启动At 服务
要确定服务是否正在运行，请使用以下命令：
systemctlstatusatd.service
要在当前会话中运行atd 服务，请以root 用户在命令行提示符下输入以下
命令：
systemctlstartatd.service
要配置服务开机自启动，请以root 用户执行以下命令：
systemctlenableatd.service

建议您将atd 服务在您的系统中配置为开机自启动。
停止At 服务
要在当前会话中停止atd 服务，请以root 用户在命令行提示符下输入以下
命令：
systemctlstopatd.service
要禁止服务开机自启动，请以root 用户执行以下命令：
systemctldisableatd.service
重启At 服务
要重启atd 服务，请以root 用户在命令行提示符下输入以下命令：
systemctlrestartatd.service
该命令停止服务后将很快地再次启动服务。
7.6.8.3. 配置At 任务
要使用At 工具调度一次性任务在指定时间执行，请按以下步骤操作：
1. 在命令行中输入命令atTIME，这里的TIME 表示命令执行的时间。
TIME 参数可以使用以下任意一种格式定义：
● HH:MM：指定确切的小时和分钟，例如，04:00 表示上午4 点；
● midnight：指定为午夜12 点；
● noon：指定为中午12 点；
● teatime：指定为下午4 点；
● MONTHDAYYEAR 格式：例如，January152020 表示2020 年1 月
15 日，年份的值是可选的；

● MMDDYY、MM/DD/YY 或者MM.DD.YY 格式：例如，011520 表示
2020 年1 月15 日；
● now+TIME：这里的TIME 由一个整数和minutes、hours、days
或者weeks 几个类型值来定义。例如，now+5days 表示命令将在从现在起五
天后的同一时间被执行。
必须首先指定时间，其后可以指定可选的日期。要了解更多关于时间格式的
信息，请参考/usr/share/doc/at-<version>/timespec 文本文件。
如果指定的时间已经过了，则任务将在明天的同一时间被执行。
2. 在显示出的at>命令行提示符下，定义任务命令：
A. 输入任务应该执行的命令，并按下回车键。可选地，可以重复此步骤，
提供多个命令。
B. 在命令行提示符下输入一个shell 脚本，并在脚本的每一行之后按下回
车键。
任务将使用用户的SHELL 环境变量所设置的shell、用户的登录shell，或
者/bin/sh（无论先找到哪一个）。
3. 一旦输入结束，请在一个空行中按下Ctrl+D 组合键，退出命令行提示
符。
如果这一系列命令或者脚本试图在标准输出中显示信息，则输出将会以电子
邮件的形式发送给用户。
要查看等待执行的任务列表，请使用atq 命令。请参见7.6.8.5 查看等待
执行的任务了解更多信息。

您也可以限制at 命令的使用。要了解更多信息，请参见7.6.8.7 控制对At
和Batch 的访问。
7.6.8.4. 配置Batch 任务
Batch 程序在系统平均负载降低到0.8 以下的时候执行已定义的一次性任
务。
要定义Batch 任务，请按以下步骤进行操作：
1. 在命令行中输入batch 命令。
2. 在显示出的at>命令行提示符下，定义任务命令：
A. 输入任务应该执行的命令，并按下回车键。可选地，可以重复此步
骤，提供多个命令。
B. 在命令行提示符下输入一个shell 脚本，并在脚本的每一行之后按
下回车键。
任务将使用用户的SHELL 环境变量所设置的shell、用户的登录
shell，或者/bin/sh（无论先找到哪一个）。
3. 一旦输入结束，请在一个空行中按下Ctrl+D 组合键，退出命令行
提示符。
如果这一系列命令或者脚本试图在标准输出中显示信息，则输出将会以电子
邮件的形式发送给用户。
要查看等待执行的任务列表，请使用atq 命令。请参见7.6.8.5 查看等待
执行的任务了解更多信息。
您也可以限制batch 命令的使用。要了解更多信息，请参见7.6.8.7 控制

对At 和Batch 的访问。
7.6.8.5. 查看等待执行的任务
要查看等待执行的At 和Batch 任务，可以执行atq 命令。atq 命令将显示
一个等待执行的任务的列表，每个任务单独显示为一行。每一行的格式为任务编
号、日期、任务类型和用户名称。用户只能查看他们自己的任务。如果root 用
户执行atq 命令，则会显示所有用户的所有任务。
7.6.8.6. 额外的命令行选项
at 和batch 命令的额外命令行选项如下所示：
表 7-5at 和 batch 命令行选项
选项 描述
-f
从一个文件中读取命令或shell 脚本，而不是在命
令行提示符下输入命令或脚本。
-m 当任务完成后向用户发送电子邮件。
-v 显示任务被执行的时间。
7.6.8.7. 控制对At 和Batch 的访问
您可以使用/etc/at.allow 和/etc/at.deny 文件来限制对at 和batch 命令
的访问。这些访问控制文件使用相同的格式，都是每一行一个用户名。记住，两
个文件都不允许使用空格。
如果存在at.allow 文件，只有在该文件中列出的用户才能使用at 或者
batch，并且at.deny 文件将被忽略。

如果at.allow 文件不存在，则at.deny 文件中列出的用户将被禁止使用at
或者batch。
如果访问控制文件被修改了，不必重启at 守护进程（atd）。每次用户试
图执行at 或batch 命令时，都会读取访问控制文件。
不管访问控制文件中的内容是什么，root 用户都始终能够执行at 和batch
命令。