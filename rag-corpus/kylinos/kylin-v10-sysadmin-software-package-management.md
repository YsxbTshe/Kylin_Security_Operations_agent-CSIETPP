---
title: 银河麒麟 V10 — 安装和管理软件（软件包管理/完整性）
publisher: 麒麟软件有限公司 (KylinSoft)
source_url: https://market-isv-1258344699.cos.ap-shanghai.myqcloud.com/.../银河麒麟高级服务器操作系统V10系统管理员手册.pdf
date_published: 2024-03
retrieved: 2026-06-14
language: zh
license: 官方公开发布的系统管理员手册（节选）
topic: 软件包管理, dnf/rpm, 软件包组, 操作记录, 完整性校验
note: Focused extract (第四章 安装和管理软件) from the official V10 sysadmin manual (2024-03). Running headers/footers stripped.
---

# 银河麒麟 V10 — 安装和管理软件（软件包管理/完整性）

> 节选自麒麟软件官方《银河麒麟高级服务器操作系统 V10 系统管理员手册》(2024-03)。

第四章 安装和管理软件
dnf 是新一代的软件包管理器，首先出现在Fedora18 这个发行版中。而
在Fedora22 中，它取代了yum，正式成为了Fedora22 的包管理器。
dnf 包管理器克服了yum 包管理器的一些瓶颈，提升了包括用户体验、内
存占用、依赖分析和运行速度等多方面的内容。dnf 使用rpm、libsolv 和
hawkey 库进行包管理操作，可以同yum 同时使用。
4.1. 检查和升级软件包
4.1.1. 软件包升级检查
查看系统里已经安装的软件包有哪些可以升级可以执行以下命令，以X86
平台示例如下：
示例说明：
➢ PackageKit——软件包名称；
➢ x86_64——该软件包支持的CPU 架构；
➢ 33.19-31.p12.ky10——可升级的软件包版本；
➢ ks10-adv-os——可升级的软件包所存储仓库。
4.1.2. 升级软件包
dnf 支持一次升级单个/批量软件包，并同时安装/更新相应的依赖包。

1. 升级单一软件包命令：
#dnfupdate{package_name}
升级kernel 软件包命令为例：
上述输出的说明如下：
a) Package：用户需要下载升级的软件包和依赖软件包。
b) Architecture：该软件包所属的架构。
c) Version：软件包升级后的版本。
d) Repository：可升级软件包所属仓库。
e) Size：软件包大小。
f) dnf 默认会显示升级软件包的基本信息，并提示是否确认安装，用户可
以在使用dnf 命令时添加参数–y，效果等同于出现Isthisok[y/N]:
时输入yes。
g) 安装过程中如果出现错误导致安装过程终止，可以使用dnfhistory 命
令查看详细描述。
如果需要安装一组软件包，可以以root 用户执行命令：
#dnfgroupupdategroup_name

2. 批量升级软件包及其依赖
如果需要升级系统所有软件包，可以使用以下命令：
#dnfupdate
4.1.3. 利用系统光盘与 dnf 离线升级系统
dnf 可 与 yum 使 用 相 同 的 配 置 文 件 ， 即 配 置 dnf 源 可 直 接 对
/etc/yum.repos.d/中的.repo 文件进行编辑。当系统处于离线状态或者无法访
问官方更新源时，可以利用更新的系统光盘创建本地源并进行升级。步骤如下：
1. 创建系统光盘挂载目录，以root 用户执行：
#mkdir{mount_dir}
2. 将系统安装光盘挂载至该目录，以root 用户执行
#mount-oloop{iso_name}{mount_dir}
3. 将 系 统 光 盘 中 的 media.repo 文 件 从 挂 载 目 录 拷 贝 至
/etc/yum.repos.d/目录下：
#cpmount_dir/media.repo/etc/yum.repos.d/new.repo
4. 编辑/etc/yum.repos.d/new.repo 配置文件以添加光盘路径：
#baseurl=file://mount_dir
5. 更新dnf 源并进行升级，以root 用户执行：
#dnfupdate
6. 升级成功后，卸载系统光盘挂载目录：
#umountmount_dir 或者rmdirmount_dir

如果不再使用这个dnf 源进行安装和升级，可以以root 用户删除文件：
#rm/etc/yum.repos.d/new.repo
4.2. 管理软件包
dnf 提供了完整操作系统软件包管理功能，包括检索、查看信息、安装和删
除。
4.2.1. 检索软件包
执行dnfsearch 命令可以检索软件包，例如检索包含“mesh”字段的软件
包，以X86 平台示例如下：
#dnfsearchmesh
如果dnf 检测的结果繁多，可以通过shell 本身的grep 或者正则表达式进
行过滤。
4.2.2. 安装包列表
显示已安装和可安装的软件包列表可以执行以下命令：
#dnflistall
显示包括某些字符的已安装和可安装软件包列表可以执行以下命令：
#dnflistglob_expression…
显示abrt 相关软件包列表的命令如下：
#dnflistabrt-addon\*abrt-plugin\*
显示包括某些字符的已安装软件包列表可以执行以下命令：

#dnflistinstalledglob_expression…
显示包括krb 的所有已安装软件包示例如下：
#dnflistinstalled"krb?-*"
显示包括某些字符的可安装软件包列表可以执行以下命令：
#dnflistavailableglob_expression…
显示所有可用的gstreamerplug-ins 软件包列表：
#dnflistavailablegstreamer\*plugin\*
查看软件仓库
成功注册后，可使用dnf 来管理软件包。
查看可用的软件仓库可以使用以下命令：
#dnfrepolist
如果想显示更多信息可以加上-v 选项，或者用dnfrepoinfo 命令输出信息。
#dnfrepolist-v
#dnfrepoinfo
如果需要显示所有可用和不可用的软件仓库，可以使用以下命令：
#dnfrepolistall
4.2.3. 显示软件包信息
显示一个或多个软件包可以使用以下命令：
#dnfinfopackage_name…
显示软件包abrt 详细信息的命令：

#dnfinfoabrt
显示软件包yum 详细信息的命令：
#dnfinfoyum
4.2.4. 安装软件包
用户可以以root 用户使用以下命令安装软件包
#dnfinstallpackage_name
安装sqlite 的i686 架构的软件包示例：
#dnfinstallsqlite.i686
除了安装软件包，还可以安装具体的二进制文件，您可以输入文件地址，以
root 用户执行安装：
#dnfinstall/usr/sbin/named
安装命令如下：
#dnfinstallhttpd
如果要安装本地软件包，可以执行：
#dnflocalinstallpath
4.2.5. 下载软件包
在执行安装流程中，显示以下选项是：
...
Totalsize:1.2M
Isthisok[y/N]:

...
输入y，可以执行软件包下载。
4.2.6. 删除软件包
删除软件包可以执行以下命令：
dnfremovepackage_name…
删除totem 软件包示例：
dnfremovetotem
4.3. 管理软件包组
软件包组可以搜集一系列特定功能软件包，比如系统工具和视频软件包组。
安装软件包组可以一起安装其依赖。
4.3.1. 软件包组列表
Summary 选项可以显示软件包可用组的数量：
dnf groups summary
以下为输出示例：
#dnfgroupssummary
可用组： 8
显示某个软件包组的全部信息可以用以下命令：
#dnfgroupsinfoglob_expression…
以下为Server 组输出示例：

#dnfgroupsinfoServer
4.3.2. 安装软件包组
每个软件包组都有自己的组ID，要显示包组id 可以使用以下命令：
#dnfgrouplistids
查找开发软件包组列表的示例：
#dnfgroupslistidsdeve\*
软件包组的安装可以通过软件包组名称安装，也可通过包组id 安装。
#dnfgroupinstall"groupname"
#dnfgroupinstallgroupid
也可用通过以下两种命令安装：
#dnfinstall@group

#dnfinstall@^group
下面是4 种安装开发工具软件分组的示例：
#dnfgroupinstall"DevelopmentTools"
#dnfgroupinstalldevelopment
#dnfinstall@"DevelopmentTools"
#dnfinstall@development
4.3.3. 删除软件包组
可以通过软件包组名或者软件包组id 删除软件包。以root 权限执行：
#dnfgroupremovegroup_name
#dnfgroupremovegroupid
如果软件分组有@标签，也可用以下命令删除。以root 身份执行：
#dnfremove@group
#dnfremove@^group
删除KDE 桌面软件分组示例：
#dnfgroupremove"DevelopmentTools"
#dnfgroupremovedevelopment
#dnfremove@"DevelopmentTools"
#dnfremove@development
4.4. 软件包操作记录管理
dnf 可以使用dnfhistory 命令进行管理操作。
4.4.1. 查看操作
显示以往20 条dnf 操作记录，可以使用以下命令。以root 权限执行：

#dnfhistorylist1..20
如果想显示某一部分dnf 操作记录，可以使用以下命令。以root 权限执行：
#dnfhistoryliststart_id..end_id
显示过去5 条dnf 信息示例如下：
#dnfhistorylist1..5
以上dnfhistorylist 输出显示内容说明如下：
ID——识别特定记录的标示数；
Commandline——简要描述操作内容；
Dateandtime——该条记录的日期和时间；
Action(s)——描述操作类型；
Altered——记录操作影响的条目数。
下表是Action 的不同说明：

表 4-1Action 说明
Action 缩写 描述
Downgrade D 下载更新包
Erase E 删除软件包
Install I 安装软件包
Obsoleting O 软件包标注废弃
Reinstall R 软件包重装
Update U 升级软件包
4.4.2. 审查操作
需要显示某条操作记录的具体综述信息，可以执行以下命令：
#dnfhistory{id}
其中id 是操作的id。
如果需要显示某条操作记录的详细信息，可以使用以下命令：
#dnfhistoryinfo{id}
如果需要显示某一阶段操作记录的详细信息，可以使用以下命令：
#dnfhistoryinfostart_id..end_id
示例如下：
#dnfhistoryinfo4..5