---
title: 银河麒麟 V10 — 用户配置与获取特权（账号/权限/sudo）
publisher: 麒麟软件有限公司 (KylinSoft)
source_url: https://market-isv-1258344699.cos.ap-shanghai.myqcloud.com/.../银河麒麟高级服务器操作系统V10系统管理员手册.pdf
date_published: 2024-03
retrieved: 2026-06-14
language: zh
license: 官方公开发布的系统管理员手册（节选）
topic: 账号与权限管理, 用户配置, 获取特权 sudo
note: Focused extract (§1.4 用户配置, §1.6 获取特权) from the official V10 sysadmin manual (2024-03). Running headers/footers stripped.
---

# 银河麒麟 V10 — 用户配置与获取特权（账号/权限/sudo）

> 节选自麒麟软件官方《银河麒麟高级服务器操作系统 V10 系统管理员手册》(2024-03)。

如下。
#hwclock--set--date"20Feb202021:17"--utc
1.3.3.3. 同步系统与远程服务器时间
以root 用户运行以下命令同步远程时间：
#hwclock--systohc
1.4. 用户配置
用户配置可以对用户进行创建与管理，点击开始->控制面板->用户账户->
创建一个新账户可以新建用户，并且可以配置头像、是否自动登录以及用户类型，
输入用户名以及密码，点击创建用户即可创建成功。
图 1-1 创建账户
用户创建成功后，可以对账户密码以及头像进行重新更改，点击“更换密码”
以及“更换头像”即可更改，点击“删除用户”即可将用户删除。

图 1-2 用户账户
1.5. Kdump 机制
Kdump 是基于kexec 的内核崩溃转储机制，在系统崩溃、死锁或死机时
用来转储内存运行参数的一个工具和服务，用来捕获内核崩溃的时候产生的
crashdump。Kdump 是迄今为止最可靠的内核转存机制，最大的优点在于崩
溃转储数据可从一个新启动内核的上下文中获取，而不是从已经崩溃内核的上下
文。
Kdump 需要两个不同目的的内核，生产内核和捕获内核。生产内核是捕获
内核服务的对像：如果系统一旦崩溃，那么正常的内核就没有办法工作了，在这
个时候将由Kdump 产生一个用于捕获当前运行信息的内核，该内核会与相应的
ramdisk（虚拟内存盘：将内存模拟成硬盘的技术）一起组建一个微环境，将此

时的内存中的所有运行状态和数据信息收集到一个dumpcore 文件中，一旦内
存信息收集完成，系统将会自动重启。
Kdump 机制主要包括两个组件：kdump 和kexec。
kdump 使用kexec 启动到捕获内核，以很小内存启动以捕获转储镜像。生
产内核保留了内存的一部分给捕获内核启动用。由于kdump 利用kexec 启动
捕获内核，绕过了BIOS，所以第一个内核的内存得以保留。这是内核崩溃转储
的本质。
kexec 是一个快速启动kernel 的机制，它运行在某一正在运行的kernel
中，启动一个新的kernel 而且不用重新经过BIOS 就可以完成启动。因为一般
BIOS 都会花费很长的时间，尤其是在大型并且同时连接许多外部设备的Server
上的环境下，BIOS 会花费更多的时间。
kexec 包括2 个组成部分：一是内核空间的系统调用kexec_load，负责在
生产内核启动时将捕获内核加载到指定地址。二是用户空间的工具kexec-tools，
他将捕获内核的地址传递给生产内核，从而在系统崩溃的时候能够找到捕获内核
的地址并运行。
1.5.1. Kdump 命令行配置
1.5.2.1.安装Kdump 需要的软件包
表 1-6 Kdump 所需的软件包
软件包名称 软件包说明
kdump kdump软件包
kexec-tools kexec软件包，kdump用到的各种工具都在此包中

kernel-debuginf
o
用来分析vmcore文件
crash vmcore分析工具
使用Kdump服务，需先安装这些工具包。安装命令如下：
dnfinstallkdumpkexec-toolskernel-debuginfo-common
kernel-debuginfo
1.5.2.2.配置grub
Kdump 的使用需要配置kdumpkernel 的内存区域。Kdump 要求操作系
统正常使用的时候，不能使用kdumpkernel 所占用的内存，配置这个需要修
改/boot/grub/grub.conf 文件，修改用到的引导部分，加入crashkernel。
Crashkernel 的格式如下：
crashkernel=nn[KMG]@ss[KMG]
其中nn 表示要为crashkernel 预留多少内存，ss 表示为crashkernel 预
留内存的起始位置。
修改完成并重启后，可以通过cat/proc/cmdline 查看kernel 启动配置选
项，其中已经加入了crashkernel 项。
1.5.2.3.启动和查看Kdump 服务
查看Kdump 服务命令如下：
#systemctlstatuskdump.service
如果Kdump 服务未开启，使用如下命令来启动kdump 服务:
#systemctlstartkdump.service

1.6. 获取特权
系统普通用户的权限有不同的限制，某些情况下普通需用需要执行管理员用
户权限才能执行的命令，此时可以通过su 或者sudo 命令获得管理员权限特权。
1.6.1. su 命令工具
用户使用su 命令时，需要输入root 用户密码，验证通过后可以获取root
的脚本环境。一旦通过su 命令登入，这个用户的所有操作均视为root 用户操
作。由于su 可以获取root 全部权限，并因此获取其他用户的权限，可能存在
一定安全问题。因此可以通过管理员组群wheel 来进行限制。以root 用户执行
以下命令：
#usermod-Gwheelusername
当将用户加入wheel 组群后，可以限制只有这个组群的用户可以使用su
命令访问。配置su 的PAM 可以编辑/etc/pam.d/su 文件，通过添加删除#字
符来确认添加或删除相应内容。
#authrequiredpam_wheel.souse_uid
上述内容表示管理员组群wheel 内的用户可以通过su 访问其他用户。
1.6.2. sudo 命令工具
sudo 命令允许系统管理员让普通用户执行一些或者全部的root 命令。当
可信用户执行sudo 命令时，需要提供他们自己的用户密码，然后以root 权限
执行命令。
基本的sudo 命令如下：

#sudocommand
sudo 命令有很大的弹性，只有在/etc/sudoers 文件中被允许的用户可以执
行在他们自己的shell 环境中执行sudo 命令，而不是root 的shell 环境。这意
味着在7 系列中root 的shell 环境是被禁止访问的。
配置sudo 必须通过编辑/etc/sudoers 文件，而且只有管理员用户才可以
修改它，必须使用visudo 编辑。之所以使用visudo 有两个原因，一是它能够
防止两个用户同时修改它；二是它也能进行一些的语法检查。以root 身份用
visudo 打开配置文件，输入以下内容：
#juanALL=(ALL) ALL
这条信息意思是juan 用户可以以任何主机连接并通过sudo 执行任何命令。
下面这条信息说明users 用户可以本地主机可以执行/sbin/shutdown-h
now 命令：
%userslocalhost=/sbin/shutdown-hnow