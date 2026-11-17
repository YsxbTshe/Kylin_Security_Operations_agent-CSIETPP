---
title: 银河麒麟 V10 — 基础服务（systemd 服务管理）
publisher: 麒麟软件有限公司 (KylinSoft)
source_url: https://market-isv-1258344699.cos.ap-shanghai.myqcloud.com/.../银河麒麟高级服务器操作系统V10系统管理员手册.pdf
date_published: 2024-03
retrieved: 2026-06-14
language: zh
license: 官方公开发布的系统管理员手册（节选）
topic: 服务与进程管理 systemd / systemctl
note: Focused extract (第五章 §5.1 使用 systemd 管理系统服务) from the official V10 sysadmin manual (2024-03). Running headers/footers stripped.
---

# 银河麒麟 V10 — 基础服务（systemd 服务管理）

> 节选自麒麟软件官方《银河麒麟高级服务器操作系统 V10 系统管理员手册》(2024-03)。

4.4.3. 恢复与重复操作
如果想要撤销某个dnf 操作，可以以root 权限执行以下操作：
#dnfhistoryundo{id}
如果需要重复某个dnf 操作，可以以root 权限执行以下操作：
#dnfhistoryredo{id}
第五章 基础服务
该章节介绍如何配置系统服务、后台daemon 以及远程访问KylinLinux
AdvancedServerV10 系统。
5.1. 使用 systemd 管理系统服务
5.1.1. Systemd 介绍
systemd 是Linux 下一个与SysV 和LSB 初始化脚本兼容的系统和服务管

理器。systemd 使用socket 和D-Bus 来开启服务，提供基于守护进程的按需
启动策略，保留了Linuxcgroups 的进程追踪功能，支持快照和系统状态恢复，
维护挂载和自挂载点，实现了各服务间基于从属关系的一个更为精细的逻辑控制，
拥有前卫的并行性能。systemd 无需经过任何修改便可以替代sysvinit。
systemd 开启和监督整个系统是基于unit 的概念。unit 是由一个与配置
文件对应的名字和类型组成的(例如：avahi.serviceunit 有一个具有相同名字
的配置文件，是守护进程Avahi 的一个封装单元)。unit 有以下几种类型：
表 5-1unit 类型介绍
Unit 类型 文件后缀 描述
Serviceunit .service 守护进程的启动、停止、重启和重载是此类
型
Targetunit .target 此类unit 为其他unit 进行逻辑分组
Automount
unit
.automount 此类unit 封装系统结构层次中的一个自挂
载点
Deviceunit .device 此类unit 封装一个存在于Linux 设备树中
的设备
Mountunit .mount 此类unit 封装系统结构层次中的一个挂载
点
Pathunit .path 此类unit 为文件系统中的一个文件或者目
录

Scopeunit .scope 此类unit 为外部创建进行
Sliceunit .slice 此类unit 为一组管理系统进程的分层unit
Snapshotunit .snapshot 与targetunit 相似，快照本身不做什么，
唯一的目的就是引用其他 unit
Socketunit .socket 此类unit 封装系统和互联网中的一个
socket
Swapunit .swap 此类unit 封装swap 设备或者swap 文件
Timerunit .timer 此类unit 封装系统时间
Systemdunit 的文件目录说明如下：
表 5-2 Systemd unit 介绍
目录 描述
/usr/lib/systemd/system/ RPM 包安装时发布的Systemdunits
/run/systemd/system/ 运行时创建的Systemdunits，该目录任务会覆
盖安装时自带的Systemdunits
/etc/systemd/system/ 系统创建与管理的Systemdunits，该目录任务
会覆盖运行时Systemdunits
5.1.1.1. 主要特性
systemd 提供以下特性：
➢ 基于socket 的并行性能：为了加速整个系统启动和并行启动更多的进
程，systemd 在实际启动守护进程之前创建socket，然后传递socket 给守护

进程。在系统初始化时，首先为所有守护进程创建socket，然后再启动所有的
守护进程。如果一个服务因为需要另一个服务的支持而没有完全启动，而这个连
接可能正在提供服务的队列中排队，那么这个客户端进程在这次请求中就处于阻
塞状态。不过只会有这一个客户端进程会被阻塞，而且仅是在这一次请求中被阻
塞。服务间的依赖关系也不再需要通过配置来实现真正的并行启动(因为一次开
启了所有的socket，如果一个服务需要其他的服务，它显然可以连接到相应的
socket)。
➢ D-Bus 激活策略启动服务：通过使用总线激活策略，服务可以在接入时
马上启动。同时，总线激活策略使得系统可以用微小的消耗实现D-Bus 服务的
提供者与消费者的同步开启请求。(同时开启多个服务，如果一个比总线激活策
略中其他服务快就在D-Bus 中排队其请求，直到其他管理确定自己的服务信息
为止)。
➢ 提供守护进程的按需启动策略。
➢ 保留了使用Linuxcgroups 进程的追踪功能：每一个执行了的进程获得
它自己的一个cgroup，配置sysytemd 使其可以存放在cgroup 中已经经过外
部配置的服务非常简单。（如使用libcgroupsutilities）。
➢ 支持快照和系统状态恢复：快照可以用来保存/恢复系统初始化时所有的
服务和unit 的状态。它有两种主要的使用情况：允许用户暂时进入一个像
"EmergencyShell"的特殊状态，终止当前的服务；提供一个回到先前状态的
简单方法，重新启动先前暂时终止的服务。
➢ 维护挂载和自挂载点：systemd 监视所有的挂载点的进出情况，也可以

用来挂载或卸载挂载点。/etc/fstab 也可以作为这些挂载点的一个附加配置源。
通过使用comment=fstab 选项您甚至可以标记/etc/fstab 条目使其成为由
systemd 控制的自挂载点。
➢ 现了各服务间基于依赖关系的一个精细的逻辑控制：systemd 支持服务
(或unit)间的多种依赖关系。在unit 配置文件中使用After/Before、Requires
和Wants 选项可以固定unit 激活的顺序。Requires 和Wants 表示一个正向(强
制或可选)的需求和依赖关系，Conflicts 表示一个负向的需求和依赖关系。其他
选项较少用到。如果一个unit 需要启动或关闭，systemd 就把它和它的依赖关
系添加到临时执行列表，然后确认它们的相互关系是否一致(或所有unit 的先后
顺序是否含有循环)。如果答案是否的话，systemd 将尝试修复它，删除可以消
除循环的无用工作。
5.1.1.2. 兼容性
➢ 与SysV 初始化脚本兼容：如果可能，它会利用LSB 和chkconfig 的
头信息内容，否则，就使用其他可用信息，如：/etc/rc.d。这些初始化脚本仅
仅作为一个附加的配置源，以减少sysytemd 服务固有的路径数目。
➢ /etc/fstab 配置文件：这只是另一个配置源。通过使用comment=fstab
选项标记/etc/fstab 条目，使systemd 可以控制自挂载点。
➢ 支持简单的模板/实例机制：例如只有一个作为示例的getty@.service
文件，而不是为六个getty 都准备一个配置文件。接口部分甚至可以被直接继
承 ， 也 就 是 说 ， 可 以 简 单 的 调 用avahi-autoipd@eth0.service 服 务 配 置
dhcpcd@eth0.service，使得字符串eth0 的值可以直接通过通配符匹配得到。

➢ 在 一 定 程 度 上 兼 容/dev/initctl 。 这 个 兼 容 性 实 际 上 是 为 了 执 行
FIFO-activated 服务。(只是简单地把原先的请求转换成为D-Bus 请求)事实上，
这也意味着旧的Upstart 和sysvinit 中的shutdown、poweroff 和其他相似
命令可以在systemd 中继续使用。
➢ 与utmp 和wtmp 兼容。
➢ 它可以控制由它催生的每一个程序。
➢ 本地配置文件使用与.desktop 文件相近的语法：很多软件架构中都有这
个简单的语法的分析器。它也可以借用已有的国际化的服务描述工具，语法都是
相似的，没有必要再学习新的语法。
5.1.2. 管理系统服务
systemctl 是最主要的工具。它融合service 和chkconfig 的功能于一体。
您可以使用它永久性或只在当前会话中启用/禁用服务。
表 5-3service 与 systemctl 的区别
service systemctl 描述
servicenamestart systemctlstart
name.service
用来启动一个服务(并不
会重启现有的)
servicenamestop systemctlstop
name.service
用来停止一个服务(并不
会重启现有的)
servicename
restart
systemctlrestart
name.service
用来停止并启动一个服
务

servicename
condrestart
systemctltry-restart
name.service
重启一个正在运行的服
务
servicename
reload
systemctlreload
name.service
当支持时，重新装载配置
文件而不中断等待操作
servicename
status
systemctlstatus
name.service
systemctlis-enabled
name.service
查询服务状态
service--status-all systemctllist-units
--type=service--all
显示所有服务状态
表 5-4 chkconfig 与 systemctl 的区别
在下次启动时或满足其
他触发条件时设置服务
为启用
chkconfigname
on
systemctlenable
name.service
在下次启动时或满足其
他触发条件时设置服务
为禁用
chkconfigname
off
systemctldisable
name.service
用来检查一个服务在当
前环境下被配置为启用
还是禁用。
chkconfig--list
name
systemctlstatus
name.service
systemctlis-enabled
name.service
输出在各个运行级别下
服务的启用和禁用情况
chkconfig--list systemctllist-unit-files
--typeservice

显示unit 前置启动服
务
chkconfig--list systemctl
list-dependencies--after
显示unit 后导启动服
务
chkconfig--list systemctl
list-dependencies--before
5.1.2.1. 显示服务
输出激活的单元：
#systemctl
以下命令等效：
#systemctllist-units
如需输出激活服务的单元，执行以下命令：
#systemctllist-units--typeservice
输出加载服务的单元，执行以下命令：
#systemctllist-units--typeservice--all
输出所有服务的单元，执行以下命令：
#systemctllist-unit-files--typeservice
以下为显示当前所有激活服务的命令：
#systemctllist-units--typeservice
显示所有已安装服务单元命令如下：
#systemctllist-unit-files--typeservice

5.1.2.2. 显示服务状态
显示服务状态命令：
#systemctlstatusname.service
表 5-5 服务单元信息列表
文件 描述
Loaded 服务是否加载
Active 服务是否激活
MainPID 当前系统服务的PID
Status 当前系统服务的附加信息
Process 相关进程附加信息
CGroup 相关CGroup 附加信息
显示firewalld.service 服务状态示例：
#systemctlstatusfirewalld.service
显示firewalld.service 服务启动前置依赖服务示例：
#systemctllist-dependencies--afterfirewalld.service

显示firewalld.service 服务启动后导服务示例：
#systemctllist-dependencies--beforefirewalld.service
5.1.2.3. 启动服务
启动服务命令：
#systemctlstartname.service
启动httpd 服务示例：
#systemctlstarthttpd.service
5.1.2.4. 停止服务
停止服务命令：
#systemctlstopname.service
停止httpd 服务示例：
#systemctlstophttpd.service

5.1.2.5. 重启服务
重启服务命令：
#systemctlrestartname.service
重启httpd 服务示例：
#systemctlrestarthttpd.service
仅重启正在运行的服务命令：
#systemctltry-restartname.service
重载服务命令：
#systemctlreloadname.service
重载httpd 服务示例：
#systemctlreloadhttpd.service
5.1.2.6. 启用服务
启用服务命令：
#systemctlenablename.service
重新启用服务命令：
#systemctlreenablename.service
启用httpd 服务示例：
#systemctlenablehttpd.service
Createdsymlink
/etc/systemd/system/multi-user.target.wants/httpd.service→
/usr/lib/systemd/system/httpd.service.

5.1.2.7. 禁用服务
禁用服务命令：
#systemctldisablename.service
禁用bluetooth 服务示例：
#systemctldisablebluetooth.service
Removed/etc/systemd/system/dbus-org.bluez.service.
Removed
/etc/systemd/system/bluetooth.target.wants/bluetooth.
service.
5.1.3. 管理目标
启动级别（runlevel）是一个旧的概念。现在，systemd 引入了一个和启
动级别功能相似又不同的概念——目标（target）。不像数字表示的启动级别，
每个目标都有名字和独特的功能，并且能同时启用多个。一些目标继承其他目标
的服务，并启动新服务。systemd 提供了一些模仿sysvinit 启动级别的目标，
仍可以使用旧的telinit 启动级别命令切换。
启动级别0、1、3、5、6 都被赋予特定用途，并且都对应一个systemd
的目标。要实现用户自定义启动级别功能，可以以原有的启动级别为基础，创建
一 个 新 的 目 标 /etc/systemd/system/< 新 目 标 > （ 可 以 参 考
/usr/lib/systemd/system/graphical.target ） ， 创 建
/etc/systemd/system/<新目标>.wants 目录，向其中加入额外服务的链接
（指向/usr/lib/systemd/system/中的单元文件）。

表 5-6 SysV 启动级别与 systemd 目标对比
运行级别 目标单元 描述
0 runlevel0.target,
poweroff.target
中断系统（halt）
1 runlevel1.target,
rescue.target
单用户模式
2 runlevel2.target 用户自定义启动级别，通常识别为级
别3
3 runlevel3.target,
multi-user.target
多用户，无图形界面。用户可以通过
终端或网络登录
4 runlevel4.target,
multi-user.target
用户自定义启动级别，通常识别为级
别3
5 runlevel5.target,
graphical.target
多用户，图形界面。继承级别3 的服
务，并启动图形界面服务
6 runlevel6.target,
reboot.target
重启
emergency emergency.targe
t
急救模式（Emergencyshell）
表 5-7 SysV 初始化命令与 systemctl 对比
旧命令 新命令 描述
runlevel systemctllist-units--type
target
显示当前目标
telinitrunlevel systemctlisolate
name.target
变更当前目标

5.1.3.1. 查看默认目标
查看默认目标命令：
#systemctlget-default
以下为查看默认目标单元的示例：
#systemctlget-default
graphical.target
5.1.3.2. 查看当前目标
查看当前目标命令：
#systemctllist-units--typetarget
输出加载目标的单元，执行以下命令：
#systemctllist-units--typetarget--all
以下为显示当前所有激活目标的示例：
#systemctllist-units--typetarget
5.1.3.3. 变更默认目标
变更默认目标命令：
#systemctlset-defaultname.target
以下变更多用户目标示例：
#systemctlset-defaultmulti-user.target
Remove/etc/systemd/system/default.target
Createdsymlink
/etc/systemd/system/default.target→/usr/lib/systemd/syste

m/multi-user.target.
5.1.3.4. 变更当前目标
变更当前目标命令：
#systemctlisolatename.target
以下变更多用户目标示例：
#systemctlisolatemulti-user.target
5.1.3.5. 切换救援模式
systemd.unit=rescue.target 是一个设置基本系统和救援shell 的特殊
targetunit(与运行级1 相似)；
进入救援模式命令：
#systemctlrescue
如果需要进入救援模式且不输出日志，输以下命令：
#systemctl--no-wallrescue
切换救援模式示例：
#systemctlrescue
5.1.3.6. 切换紧急模式
systemd.unit=emergency.target 与传递保留参数的init=/bin/sh 给系
统使系统从该状态启动相似。
进入紧急模式命令：
#systemctlemergency

如果需要进入紧急模式且不输出日志，输以下命令：
#systemctl--no-wallemergency
5.1.4. 在远程机器上使用 systemd
连接远程机器使用systemd 命令如下：
#systemctl--hostuser_name@host_namecommand
远程管理命令举例：
#systemctl-Hroot@server-01.example.comstatus
httpd.service
5.1.5. 创建和修改 systemd 单元文件
5.1.5.1. 单元文件介绍
单元文件通常包括三个部分：
【Unit】：通用配置项，包括该unit 的基本信息。
【Unittype】：Unit 类型，不同类型定义可以参考systemd 简介内容。
【Install】：包括需要被安装、启用和禁用的服务内容。
表 5-8 【Unit】字段配置项说明
配置项 描述
Description 一些描述，显示给用户界面看的，可以是任何字符串，
一般是关于服务的说明。
Documentation 指定参考文档的列表，以空格分开的URL 形式，如

http://,https://,file:,info:,man，这是有顺序的，最好
是先解释这个服务的目的是什么，然后是它是如何配置
的，再然后是其它文件，这个选项可以多次指定，会将
多行的合并，如果指定了一个空的，那么会重置此项，
之前的配置不再起作用。
After/Before 配置服务间的启动顺序，比如一个foo.service 包含了
一行Before=bar.service，那么当他们同时启动时，
bar.service 会等待foo.service 启动完成后才启动。注
意这个设置和Requires=是相互独立的，同时包含
After=和Requires=也是常见的。此选项可以指定一次
以上，这时是按顺序全部启动。
Requires 指定此服务依赖的其它服务，如果本服务被激活，那么
Requires 后面的服务也会被激活，反之，如果Requires
后面的服务被停止或无法启动，则本服务也会停止。这
个选项可以指定多次，那么就要求所有指定的服务都被
激活。需要注意的是这个选项不影响启动或停止的顺序，
启动顺序使用单句的After=和Before=来配置。例如，
如果foo.service 依赖bar.serivce，但是只配置了
Requires=而没有After=或Before=，那么
foo.service 启动时会同时激活foo.service 和
bar.service。通常使用Wants=代替Requires=是更

好的选择，因为系统会更好的处理服务失败的情况。
Wants 相对弱化的Requires=，这里列出的服务会被启动，但
如果无法启动或无法添加到事务处理，并不影响本服务
做为一个整体的启动。这是推荐的两个服务关联的方式。
这种依赖也可以配置文件外，通过.wants/目录添加，具
体可以看上面的说明。
Conflicts 配置一个依赖冲突，如果配置了某些项，那么，当一个
服务启动时，或停止此处列出的服务，反过来，如果这
里列出的服务启动，那么本服务就会停止，即后启动的
才起作用。注意，此设置和After=和Before=是互相
独立的。如果服务A 和B 冲突，且在B 启动的时候同时
启动，那么有可能会启动失败（两者都是必需的）或修
改以修复它（两者之一或两者都不是必需的），后一种
情况，会将不需要的依赖删除，或停止冲突。
表 5-9【Service】字段配置项
配置项 描述
Type 设置进程的启动类型，必须是下列值之一：
simple,forking,oneshot,dbus,notify,idle
➢ 如果设为"simple"(设置了ExecStart=但未设置
BusName=时的默认值)，那么表示ExecStart=所设定

的进程就是该服务的主进程。
➢ 如果此进程需要为其他进程提供服务，那么必须在该
进程启动之前先建立好通信渠道(例如套接字)，以加快后
继单元的启动速度。
➢ "dbus"(设置了ExecStart=与BusName=时的默认
值)与"simple"类似，不同之处在于该进程需要在D-Bus
上获得一个由BusName=指定的名称。systemd 将会在
启动后继单元之前，首先确保该进程已经成功的获取了指
定的D-Bus 名称。设置为此类型相当于隐含的依赖于
dbus.socket 单元。
➢ "oneshot"(未设置ExecStart=时的默认值)与
"simple"类似，不同之处在于该进程必须在systemd 启
动后继单元之前退出。此种类型通常需要设置
RemainAfterExit=选项。
➢ 如果设为"forking"，那么表示ExecStart=所设定的
进程将会在启动过程中使用fork()系统调用。这是传统
UNIX 守护进程的经典做法。也就是当所有的通信渠道都
已建好、启动亦已成功之后，父进程将会退出，而子进程
将作为该服务的主进程继续运行。对于此种进程，建议同
时设置PIDFile=选项，以帮助systemd 准确定位该服务
的主进程，进而加快后继单元的启动速度。