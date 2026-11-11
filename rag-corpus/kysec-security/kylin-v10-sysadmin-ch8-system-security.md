---
title: 银河麒麟高级服务器操作系统 V10 系统管理员手册 — 第八章 系统安全
publisher: 麒麟软件有限公司 (KylinSoft)
source_url: https://market-isv-1258344699.cos.ap-shanghai.myqcloud.com/.../银河麒麟高级服务器操作系统V10系统管理员手册.pdf
date_published: 2024-03
retrieved: 2026-06-14
language: zh
license: 官方公开发布的系统管理员手册（节选）
topic: KYSEC, 防火墙firewalld, 安全增强组件, 三权分立, 强制访问控制MAC, 安全中心, 文件保护箱
note: Extracted security chapter (第八章 系统安全) from the official V10 sysadmin manual. Running headers/footers stripped.
---

# 银河麒麟高级服务器操作系统 V10 — 第八章 系统安全（KYSEC / 防火墙 / 三权分立 / 安全中心 / 文件保护箱）

> 节选自麒麟软件官方《系统管理员手册》(2024-03)。

第八章 系统安全
8.1. 安全基础服务
8.1.1. 防火墙
防火墙是系统的主要的安全工具，可以提供基本的安全防护。firewalld 支
持网络/防火墙区域(zone)定义网络链接、是接口安全等级的动态防火墙管理工
具。它支持IPv4,IPv6 防火墙设置以及以太网桥接，并且拥有运行时配置和永久
配置选项。它也支持允许服务或者应用程序直接添加防火墙规则的接口且可以动
态管理防火墙。
8.1.1.1. 主要功能
1) 实现动态管理，对于规则的更改不再需要重新创建整个防火墙；
2) 提供firewall-cmd 命令行界面进行管理及配置工作；
3) 实现firewall-config 图形化配置工具；
4) 实现系统全局及用户进程的防火墙规则配置管理；
5) 区域支持。
8.1.1.2. 基本命令
安装命令如下：
dnfinstallfirewalld
启动命令如下：
systemctlenablefirewalld.service
systemctlstartfirewalld.service
关闭命令如下：

systemctlstopfirewalld
systemctldisablefirewalld
查看状态命令如下：
systemctlstatusfirewalld
8.1.1.3. 区域管理
通过将网络划分成不同的区域（通常情况下称为zones），制定出不同区域
之间的访问控制策略来控制其间传送的数据流。例如互联网是不可信任的区域，
而内部网络是高度信任的区域,以避免安全策略中禁止的一些通信。典型信任的
区域包括互联网(一个没有信任的区域)和一个内部网络(一个高信任的区域)。最
终目标是提供受控连通性在不同水平的信任区域通过安全政策的运行和连通性
模型之间根据最少特权原则。例如：公共WIFI 网络连接应该不信任，而家庭有
线网络连接就应该完全信任。网络安全模型可以在安装、初次启动和首次建立网
络连接时选择初始化。该模型描述了主机所联的整个网络环境的可信级别，并定
义了新连接的处理方式。在/etc/firewalld/的区域设定是一系列可以被快速执行
到网络接口的预设定。有几种不同的初始化区域：
drop（丢弃）
任何接收的网络数据包都被丢弃，没有任何回复。仅能有发送出去的网络连
接。
block（限制）
任何接收的网络连接都被IPv4 的icmp-host-prohibited 信息和IPv6 的
icmp6-adm-prohibited 信息所拒绝。

public（公共）
在公共区域内使用，不能相信网络内的其他计算机，只能接收经过选取的连
接。
external（外部）
特别是为路由器启用了伪装功能的外部网。您不能信任来自网络的其他计算
机，只能接收经过选择的连接。
dmz（非军事区）
用于您的非军事区内的电脑，此区域内可公开访问，可以有限地进入您的内
部网络，仅仅接收经过选择的连接。
work（工作）
用于工作区。您可以基本相信网络内的其他电脑不会危害您的电脑。仅仅接
收经过选择的连接。
home（家庭）
用于家庭网络。您可以基本信任网络内的其他计算机不会危害您的计算机。
仅仅接收经过选择的连接。
internal（内部）
用于内部网络。您可以基本上信任网络内的其他计算机不会威胁您的计算机。
仅仅接受经过选择的连接。
trusted（信任）
可接受所有的网络连接。
说明：firewalld 的缺省区域是 public。

显示支持的区域列表
firewall-cmd--get-zones
设置为家庭区域
firewall-cmd--set-default-zone=home
查看当前的区域
firewall-cmd--get-active-zones
设置当前的区域的接口
firewall-cmd--get-zone-of-interface=enp03s
显示所有公共区域（public）
firewall-cmd--zone=public--list-all
临时修改网络接口 enp0s3 为内部区域（internal）
firewall-cmd--zone=internal--change-interface=enp03s
永久修改网络接口 enp0s3 为内部区域（internal）
firewall-cmd --permanent --zone=internal
--change-interface=enp03s
8.1.1.4. 服务管理
amanda、ftp、samba 和tftp 等重要的服务已被firewalld 提供相应的
服务，可以使用命令查看：
firewall-cmd--get-services
显示当前服务

firewall-cmd--list-services
添加http 服务到内部区域（internal）
firewall-cmd --permanent --zone=internal
--add-service=http
firewall-cmd--reload
将一个服务加入到分区
要把一个服务加入到分区，例如允许SMTP 接入工作区：
firewall-cmd--zone=work--add-service=smtp
firewall-cmd--reload
要从分区移除服务，比如从工作区移除SMTP：
firewall-cmd--zone=work--remove-service=smtp
firewall-cmd--reload
8.1.1.5. 端口管理
打开端口
打开443/tcp 端口在内部区域（internal）:
firewall-cmd--zone=internal--add-port=443/tcp
firewall-cmd--reload
端口转发
firewall-cmd--zone=external--add-masquerade
firewall-cmd--zone=external
--add-forward-port=port=22:proto=tcp:toport=3777
上面的两个命令的意思是，首先启用伪装（masquerade），然后把外部
区域（external）的22 端口转发到3777。
8.1.1.6. 直接接口
firewalld 有一个被称为“directinterface”（直接接口），它可以直接通

过iptables、ip6tables 和ebtables 的规则。它适用于应用程序，而不是用户。
firewalld 保持对所增加项目的追踪，所以它还能质询firewalld 和发现由使用
直接端口模式的程序造成的更改。直接端口由增加--direct 选项使用。直接端口
模式适用于服务或者程序，以便在运行时间内增加特定的防火墙规则。这些规则
不是永久性的。例如添加端口tcp9000 端口。
firewall-cmd --direct --add-rule ipv4 filter INPUT 0 -p tcp --dport
9000-jACCEPT
firewall-cmd--reload
8.1.1.7. 富规则（RichLanguage）
通过“richlanguage”语法，可以用比直接接口方式更易理解的方法建立
复杂防火墙规则。此外还能永久保留设置。这种语言可以用来配置分区，也仍然
支持现行的配置方式。所有命令都必须以root 用户身份运行。增加一项规则的
命令格式如下：
#firewall-cmd[--zone=zone]--add-rich-rule='rule'[--timeout
9=seconds]
移除一项规则：
firewall-cmd[--zone=zone]--remove-rich-rule='rule'
检查一项规则是否存在：
firewall-cmd[--zone=zone]--query-rich-rule='rule'
一个具体例子，假设在一个IP 地址（192.168.0.0）的服务器配置防火墙
允许如下服务http,https,vnc-server,PostgreSQL。
firewall-cmd --add-rich-rule 'rule family="ipv4" source
address="192.168.0.0/24"servicename="http"accept'
firewall-cmd --add-rich-rule 'rule family="ipv4"
source address="192.168.0.0/24" service name="http" accept'

--permanent
firewall-cmd --add-rich-rule 'rule family="ipv4" source
address="192.168.0.0/24"servicename="https"accept'
firewall-cmd --add-rich-rule 'rule family="ipv4" source
address="192.168.0.0/24" service name="https" accept'
--permanent
firewall-cmd --add-rich-rule 'rule family="ipv4" source
address="192.168.0.0/24"servicename="vnc-server"accept'
firewall-cmd --add-rich-rule 'rule family="ipv4" source
address="192.168.0.0/24" service name="vnc-server" accept'
--permanent
firewall-cmd --add-rich-rule 'rule family="ipv4" source
address="192.168.0.0/24"servicename="postgresql" accept'
firewall-cmd --add-rich-rule 'rule family="ipv4" source
address="192.168.0.0/24" service name="postgresql" accept'
--permanent
firewall-cmd--reload
8.1.1.8. 创建服务
首先需要建立的服务是RTMP（RTMP 是RealTimeMessagingProtocol
（实时消息传输协议）的首字母缩写。该协议基于TCP）端口号1935。在
/etc/firewalld/services/目录中，利用现有的配置文件如nfs.xml 作为模板。
cd/etc/firewalld/services/
说明：该目录中存放的是定义好的网络服务和端口参数，只用于参考，不
能修改。这个目录中只定义了一部分通用网络服务。在该目录中没有定义的网络
服务，也不必再增加相关xml 定义，后续通过管理命令可以直接增加。
cp/usr/lib/firewalld/services/nfs.xml/etc/firewalld/services/
说明：从上面目录中将需要使用的服务的xml 文件拷至这个目录中，如果

端口有变化则可以修改文件中的数值。
#cd/etc/firewalld/services/
下面修改 nfs.xml 为 rtmp.xml
#mvnfs.xmlrtmp.xml
下面使用vi 编辑器修改rtmp.xml 文件为如下内容：
<?xmlversion="1.0"encoding="utf-8"?>
<service>
<short>rtmp</short>
<description>RTMPStream</description>
<portprotocol="tcp"port="1935"/>
</service>
每一个服务定义都需要一个简短的名字、描述和端口网络用于指定需要使
用的协议、端口和模块名。然后把此服务加入防火墙规则中。
systemctlrestartfirewalld.service
firewall-cmd--add-service=rtmp
firewall-cmd--add-service=rtmp--permanent
firewall-cmd--reload
8.1.1.9. firewall-config
firewall-config 支持防火墙的所有特性。管理员可以用它来改变系统或用
户策略。通过firewall-config 用户可以配置防火墙允许通过的服务、端口、伪
装、端口转发、和ICMP 过滤器和调整zone（区域）设置等功能以使防火墙设
置更加的自由、安全和强健。

图 8-1 防火墙配置
firewall-config 工作界面分成三个部分：上面是主菜单，中间是配置选项
卡。下面是区域、服务、ICMP 端口、白名单等设置选项卡。
说明：在左下方角落寻找“连接”字符，这标志着firewall-config 工具已经
连接到用户区后台程序firewalld。注意，ICMP 类型、直接配置 （Direct
Configuration）和锁定白名单 （LockdownWhitlist）标签只在从查看下拉
菜单中选择之后才能看见。
1) firewall-config 主菜单
firewall-config 主菜单包括四个选项：文件，选项，查看，帮助。其中选
项子菜单是最主要的，它包括几个部分：
重载防火墙：重载防火墙规则。例如所有现在运行的配置规则如果没有在永
久配置中操作，那么系统重载后会丢失。
更改连接区域：更改网络连接的默认区域。

改变默认区域：更改网络连接的所属区域和接口。
应急模式：应急模式意味着丢弃所有的数据包。
锁定：锁定可以对防火墙配置进行加锁，只允许白名单上的应用程序进行改
动。锁定特性为firewalld 增加了锁定本地应用或者服务配置的简单配置方式。
它是一种轻量级的应用程序策略。
2) 配置选项卡
firewall-config 配置选项卡包括：运行时和永久。
运行时：运行时配置为当前使用的配置规则。运行时配置并非永久有效，在
重新加载时可以被恢复，而系统或者服务重启、停止时，这些选项将会丢失。
永久：永久配置规则在系统或者服务重启的时候使用。永久配置存储在配置
文件种，每次机器重启或者服务重启、重新加载时将自动恢复。
3) 区域选项卡
区域选项卡是一个主要设置界面：
网络或者防火墙区域定义了连接的可信程度。firewalld 提供了几种预定义
的区域。这里的区域是服务、端口、协议、伪装、ICMP 过滤等组合的意思。区
域可以绑定到接口和源地址。服务子选项卡定义哪些区域的服务是可信的。可信
的服务可以绑定该区的任意连接、接口、和源地址；
i. 服务选项卡

图 8-2 服务选项卡
ii. 端口子选项卡
端口子选项卡用来设置允许主机或者网络访问的端口范围。
图 8-3 端口子选项卡
要允许流量通过防火墙到达某个端口，则启动firewall-config 并选择您想

更改设定的网络区域。选择端口图标并点击右边的添加按钮，PortandProtocol
就打开了。
输入端口数量或者端口号范围，获得许可。从下拉菜单中选择tcp 或者udp。
iii. 伪装子选项卡
伪装子选项卡用来把私有网络地址可以被映射到公开的IP 地址
图 8-4 伪装子选项卡
要将IPv4 地址转换为一个单一的外部地址，则启动firewall-config 工具
并选择需要转换地址的网络区域。选择 伪装标签和复选框以便把IPv4 地址转换
成一个单一的地址。
iv. 端口转发子选项卡
端口转发可以映射到另一个端口以及/或者其他主机;

图 8-5 端口转发子选项卡
为一个特定端口转发入站网络流量或“packets”到一个内部地址或者替代
端口，首先激活伪装IP 地址，然后选择端口转发标签。在窗口靠上部分选择入
站流量协议和端口或者端口范围。靠下部分是用于设置目的端口细节的。
要转发流量到一个本地端口即同一系统上的端口，需选择本地转发复选框，
输入要转发的流量的本地端口或者端口值范围。要转发流量到其他的IPv4 地址，
则选择转发到另一个端口复选框，输入目的地IP 地址和端口或者端口范围。如
果端口位置空缺则默认发送到同一个端口。点击确定按钮执行更改。
v. ICMP 过滤器子选项卡
ICMP 过滤器可以选择Internet 控制报文协议的报文。这些报文可以是信
息请求亦可是对信息请求或错误条件创建的响应；

图 8-6ICMP 过滤器子选项卡
要使用或者禁用一个ICMP 过滤，则启动firewall-config 工具并选择要过
滤其信息的网络区域。选择ICMPFilter 图标并选择每种您需要过滤的ICMP 信
息类型的复选框。清除复选框以禁用过滤。这种设定是单向的，默认允许全部。
vi. 直接配置选项卡
直接配置选项卡包括三个子选项卡：链、规则和穿通。说明以下这个选项卡
主要用于服务或者应用程序增加特定的防火墙规则。这些规则并非永久有效，并
且在收到firewalld 通过D-Bus 传递的启动、重启、重载信号后需要重新应用。

图 8-7 直接配置选项卡
4) 改变防火墙设置
要立刻改变现在的防火墙设置，须确定当前视图设定在运行时或者从下拉菜
单中选择永久（Permanent），编辑下次启动系统或者防火墙重新加载时执行
的设定。
在运行时（Runtime）模式下更改防火墙的设定时，一旦您启动或者清除连
接服务器的复选框，选择立即生效。在Permanent 模式下更改防火墙的设定，
仅仅在重新加载防火墙或者系统重启之后生效。可以使用文件菜单下的重新加载
图标，或者点击选项菜单，选择重新加载防火墙。

图 8-8 改变防火墙设置
5) 修改默认分区
要设定一个将要被分配新接口的分区作为默认值，则启动firewall-config，
从菜单栏选择选项卡，由下拉菜单中选择修改默认区域，从给出的列表中选择您
需要用的分区作为默认分区，点击确定按钮即可。

图 8-9 修改默认分区
8.1.2. 审计管理(audit)
8.1.2.1. 简介
audit 工具可以将审计记录写入日志文件。包括记录系统调用和文件访问。
管理员可以检查这些日志，确定是否存在安全漏洞（如多次失败的登录尝试，或
者用户对系统文件失败访问）。
8.1.2.2. 架构及原理分析
图 8-10 架构示意图
说明：实线代表数据流，虚线代表组件之间的控制关系。包括有两大部分：
中间的是内核中的几种系统调用（user,task,exit,exclude），右侧是一系列应
用程序（auditd、audispd、auditctl、autrace、ausearch 和aureport 等）。
Linux 内核中的几种系统调用是：
User:记录用户空间中产生的事件；它的作用是过滤消息的，内核传递给审
计后台进程之前先查询它。

Task：跟踪应用程序的子进程（fork）；当一个任务被创建时，也就是父
进程通过fork 和克隆创建子进程时记录该事件；
Exit：当一个系统调用结束时判断是否记录该调用；
Exclude：删除不合格事件；Exclude 是用来过滤消息的，也就是不想看到
的消息可以在这里写规则进行过滤。
audit 是内核中的一个模块，内核的运行情况都会在audit 中记录，这个记
录的规则是由超级用户来设置的。内核的audit 模块是由应用层的一个应用程序
auditd 来控制的。audit 产生的数据都会传送到auditd 中，然后再由auditd
进行其它操作。auditd.conf 是auditd 的配置文件。audit.rules 是audit 的
规则文件，确定audit 的日志中记录哪些操作。它通过一个对audit 进行控制的
应用程序auditctl 进行操作。root 用户也可以直接调用auditctl 进行操作。
auditd 收到的数据后会有两个去处。默认的是将日志保存在audit.log 文件中，
默认路径/var/log/audit/audit.log。另一个通过audispd 将日志进行分发。
要使用安全审计系统可采用下面的步骤：首先安装软件包。然后设置配置文
件、配置常用命令，添加审计规则，然后启用audit 守护进程并开始进行日志记
录，最后通过生成审计报表和搜索日志来周期性地分析数据。
用户空间审计系统由auditd、audispd、auditctl、autrace、ausearch
和aureport 等应用程序组成。下面依次说明：
auditctl:即时控制审计守护进程的行为的工具，如添加规则等。
auditd：audit 守护进程负责把内核产生的信息写入到硬盘上，这些信息由
应用程序和系统活动触发产生。用户空间审计系统通过auditd 后台进程接收内

核审计系统传送来的审计信息，将信息写入到/var/log/audit/audit.log。
aureport:查看和生成审计报告的工具。
ausearch:查找审计事件的工具
auditspd:转发事件通知给其他应用程序，而不是写入到审计日志文件中。
autrace:一个用于跟踪进程的命令。类似于strace，跟踪某一个进程，并
将跟踪的结果写入日志文件之中。
8.1.2.3. 安装及配置
1) 使用dnf 工具安装软件包
dnfinstallaudit-y
2) audit 配置文件
audit 安 装 后 会 生 成 2 个 配 置 文 件:/etc/audit/auditd.conf 和
/etc/audit/audit.rules。/etc/audit/auditd.conf 是守护程序的默认配置文件。
/etc/audit/audit.rules 是记录审计规则的文件。首次安装audit 后,审计规则文
件是空的。
/etc/audit/auditd.conf 守护程序的默认配置文件是其关键文件。
下面简单设置一个/etc/audit/auditd.conf
#vi/etc/audit/auditd.conf
#设置日志文件
log_file=/var/log/audit/audit.log
#设置日志文件轮询的数目，它是0~99 之间的数。如果设置为小于2，则
不会循环日志。如果没有设置num_logs 值，它就默认为0，意味着从来不循

环日志文件
num_logs=5
#设置日志文件是否使用主机名称
name_format=NONE
#设置日志文件大小，以兆字节表示的最大日志文件容量。当达到这个容量
时，会执行 max_log_file_action 指定的动作
max_log_file=6
#设置日志文件到达最大值后的动作，这里选择ROTATE（轮询）
max_log_file_action=ROTATE
8.1.2.4. auditctl 命令简介
auditctl 命令格式如下：
auditctl[选项]filter,action-Ssyscall-Fcondition-klabel
表 8-1 auditctl 命令选项
项目 可选参数 说明
filter user,exit,task,exclude
filter 详细说明哪个内核规则匹配过
滤器应用在事件中。过滤器:task、
exit、user 以及exclude
action always,never
是 否 审 核 事 件 （always 表 示 是 ）
（never 表示否）
syscall all,2,open 等
所 有 的 系 统 调 用 都 可 以 在
/usr/include/asm/unistd_64.h 文
件中找到。

condition euid=0,arch=b64
详细说明其他选项，进一步修改规则
来与特定架构、组ID、进程ID 和其
他内容为基础的事件相匹配
label 任意文字 标记审核事件并检索日志
-S 表示系统调用号或名字；
-F 表示规则域；
-k 表示设置审计规则上的过滤关键。
8.1.2.5. audit 审计规则
audit 审计规则分成三个部分：
1) 控制规则：这些规则用于更改审计系统本身的配置和设置。
控制规则可以在/etc/audit/audit.rules 中设置。主要包括:
-D#删除所有当前装载的审核规则#
-b8192#在内核中设定最大数量的已存在的审核缓冲区为8Mb#
-e2#锁定审核配置#
2) 文件系统规则：这些是文件或目录监视。使用这些规则，我们可以审
核对特定文件或目录的任何类型的访问。
可以通过auditctl 命令设置。监控文件系统行为（依靠文件、目录的权限
属性来识别）
规则格式：
-w 路径；
-p 权限；

-k 关键字；
其中-p 权限的动作分为四种：
r— 读取文件或者目录；
w— 写入文件或者目录；
x— 运行文件或者目录；
a— 改变在文件或者目录中的属性。
例如要监控/etc/passwd 文件的修改行为，可以使用这个命令：
auditctl-w/etc/passwd-pwa
也可以自己将上述内容加入到文件/etc/audit/rules.d/audit.rules 中即可
实现对该文件的监视。
下面审核规则记录了每次读取或者修改/etc/hosts 文件的尝试
auditctl-w/etc/hosts-pwa-khosts_change
3) 系统调用规则：这些规则用于监视由任何进程或特定用户进行的系统
调用。
监控系统调用可能会引起高负荷的日志活动，这会让内核承受更大的负荷。
所以要慎重衡量哪些系统调用需要放到audit.rules 中。如果审计的是目录的话，
只能对该目录本身的属性进行审计。如果想审计下面的文件，需要一一列出。
系统调用的监控：
-a 添加一条系统调用监控规则；
-S 显示需要监测的系统调用的名称。
显示规则和删除规则：

-D 删除所有规则；
-d 删除一条规则和-a 对应；
-w 写入文件或者目录；
-W 删除一条规则和-w 对应；
-l 列出所有规则。
举例:定义记录有哪些文件，特定用户（UID 为10001）访问和标签的日志
条目的规则：
auditctl -a always,exit -F arch=b64 -F auid=10001 -S
open-kuserfile
说明：userfile 是用户自己设置的一个规则名字，以上的都设置完毕了，就
可以生成报告了。另外通过auditctl 命令添加的规则不是永久有效的。为了让
他 们 在 重 新 启 动 后 有 效 的 ， 可 以 将 其 添 加 到 文 件
/etc/audit/rules.d/audit.rules 中。
8.1.2.6. 守护进程
启动：
systemctlstartauditd
自动启动：
systemctlenableauditd
停止：
serviceauditdstop
重启：

serviceauditdrestart
在/var/log/audit/目录中旋转日志文件：
serviceauditdrotate
推迟审核事件日志之后重新开始，例如存在没有足够的磁盘分区空间来保存。
审核日志文件情况：
serviceauditdresume
显示运行状态：
serviceauditdstatus
列出所有活动的规则和观察器：
auditctl-l
配置auditd 的日志文件到远程主机。
假设audit 服务器名称是kylin1,ip 地址192.168.0.1;audit 客户端名称
是kylin2，ip 地址192.168.0.2。
修改服务器端配置文件：
vi/etc/audit/auditd.conf
#设置监听端口为60
tcp_listen_port=60
重启服务
#serviceauditdrestart
客户端安装相关软件包并且配置文件：
#dnf-yinstallaudispd-plugins

修改配置文件
#vi/etc/audit/plugins.d/au-remote.conf
#把审核日志发送到日志服务器
active=yes
#vi/etc/audit/audisp-remote.conf
remote_server=kylin1
#设置监听端口为60
port=60
#vi/etc/audit/auditd.conf
log_format=NOLOG
然后重启服务
#serviceauditdrestart
8.1.2.7. aureport
要生成审计消息的报表，可使用aureport 命令。如果执行aureport 时没
有使用任何选项，则会显示如汇总报表。下面是几个例子:
生成一段特定时间内的报告：
aureport-ts8:00-te17:30-f-i
生成所有用户失败事件的总结报告：
aureport-u--failed--summary-i
生成系统调用事件报告：
aureport-s-i--summary

8.1.2.8. ausearch
使用ausearch，您可以过滤和搜索事件类型。它还可以通过将数值转换
为更加直观的值（如系统调用或用户名）来解释事件。以root 用户执行ausearch
命令，当显示结果时，每个记录用4 条虚线组成的一行隔开，每个记录前均显
示时间标记。
示例：ausearch-f/etc/passwd
图 8-11 输出结果
输出结果介绍:
time:审计时间；
name:审计对象；
cwd:当前路径；
syscall:相关的系统调用；
auid:审计用户ID；

uid 和gid:访问文件的用户ID 和用户组ID；
comm:用户访问文件的命令；
exe:上面命令的可执行文件路径。
下面是几个例子：
搜索系统登录失败,使用如下命令：
ausearch--messageUSER_LOGIN--successno–interpret
搜索所有的账户，群组，角色变更，使用以下命令：
ausearch -m ADD_USER -m DEL_USER -m ADD_GROUP -m
USER_CHAUTHTOK -m DEL_GROUP -m CHGRP_ID -m
ROLE_ASSIGN-mROLE_REMOVE-i
搜索从制定时间段的失败的系统调用，使用以下命令：
ausearch--startyesterday--endnow-mSYSCALL-svno-i
使用关键字（key）搜索审计事件记录。
auditd 生成的日志文件内容比较多，如果没有设置关键字，要查找搜索审
计事件记录,使用关键字（key）可以提高效率。
首先使用auditctl 命令建立一个规则：
auditctl-w/etc/passwd-prwa-kpasswd_changes
上面这个规则的意思是记录访问或修改/etc/passwd 用户帐户数据库的任
何尝试。一旦有人修改了数据库文件，可以使用如下关键字（key）搜索审计事
件记录。
使用关键字（key）搜索审计事件记录：

ausearch-kpasswd_changes|less
上面的命令显示访问或修改/etc/passwd 文件的日志信息，其他信息忽略。
8.2. 安全增强组件
在配置系统之外，掌握收集基本的系统信息的方法也很重要。譬如，您应该
知道如何找出空闲内存的数量、可用硬盘空间，硬盘分区方案，以及正在运行进
程的信息等等。本节将说明如何使用几个简单程序来从您的银河麒麟服务器操作
系统中检索这类信息。
8.2.1. KYSEC 安全机制
银河麒麟高级服务器操作系统V10 结合国产操作系统特点和现有国内外操
作系统的安全机制，提出了基于标记的软件执行控制机制，实现对系统应用程序
标记识别和执行约束，确保应用来源的可靠性和应用本身的完整性。执行控制机
制控制文件执行、模块加载和共享库使用，分为系统文件、第三方应用程序，其
中只允许具有合法标记的文件执行，任何网络下载、拷贝等外来软件均被禁止执
行。
8.2.1.1. 安全状态设置
KYSEC 共有两种状态；强制模式（normal）、软模式（softmode）。强
制模式下不能执行非法应用程序，即禁止用户执行没有合法标记的应用程序，而
软模式下，只是记录非法应用程序的执行行为，不禁止用户操作。系统默认
KYSEC 安全状态为关闭状态，且执行控制、文件保护、内核保护状态都处于关
闭状态，三权分立关闭。

查看kysec 安全状态：
#getstatus
KySecstatus:Normal
execcontrol:on
fileprotect:on
kmodprotect:on
threeadmin:off
设置kysec 安全状态为软模式：
#sudosetstatussoftmode
设置kysec 安全状态为强制模式：
#sudosetstatusnormal
8.2.1.2. 执行控制
银河麒麟高级服务器操作系统V10 添加了对文件执行、模块加载和共享库
使用的控制，分为系统文件、第三方应用程序，其中只允许具有合法标记的文件
执行，任何网络下载、拷贝等外来软件均被禁止执行。本来可运行的可执行文件
在删除、修改、拷贝、移动、重命名等操作后，会被禁止执行。
（1）可执行文件的执行控制
如下情况的/tmp/ls 则执行失败。
#cp/bin/ls/tmp
#/tmp/ls
但管理员设置/tmp/ls 的 kysec 标记后，用户则可执行/tmp/ls 程序：

#sudokysec_set-nexectl-voriginal/tmp/ls
#/tmp/ls
篡改/tmp/ls 程序内容后，该程序不能再执行：
#echo“”>>/tmp/ls
#/tmp/ls
（2）可执行脚本的执行控制
创建脚本文件/tmp/test.sh 并添加执行权限,内容如下：
#!/bin/bash
echo “test”
#chmod+x/tmp/test.sh
但用户使用如下命令，都无法执行程序：
#/tmp/test.sh
#bash/tmp/test.sh
管理员用户设置该文件的kysec 标记后，程序就能正常执行。
#sudokysec_set-nexectl-voriginal/tmp/test.sh
（3）内核模块的执行控制
用户将可加载的内核模块复制到主目录，再尝试加载拷贝的内核模块，因为
KYSEC 功能，则加载失败：
#cp /lib/modules/`uname
-r`/kernel/net/netfilter/nf_conntrack_ftp.ko~
#sudoinsmod~/nf_conntrack_ftp.ko

修改拷贝的内核模块标记，则可以加载成功。
#sudokysec_set-nexectl-voriginal~/nf_conntrack_ftp.ko
#sudoinsmod~/nf_conntrack_ftp.ko
8.2.1.3. 文件保护功能
文件保护功能开启时，受保护的文件不能被修改、重命名、删除。这样
能保护系统重要的配置文件不被误操作修改。
给文件设置文件保护的标记：
#sudokysec_set-nprotect-vreadonly/tmp/testfile
给文件移除文件保护的标记：
#sudokysec_set-xnprotect/tmp/testfile
给文件/tmp/testfile 设置文件保护的标记后，则禁止修改,重命名,删除
/tmp/testfile 文件。如下操作则会提示失败。
#echo“test1”>>/tmp/testfile
#mv/tmp/testfile/tmp/abc
#rm-f/tmp/testfile
8.2.2. 数据隔离保护机制
银河麒麟高级服务器操作系统V10 实现用户私有数据的机密性隔离存储；
并基于该保护机制，研制了文件保护箱功能，确保每个用户的文件保护箱相
互隔离，即使非法用户获得管理员权限也无法获取其他用户的私有数据；同
时也提供文件保护箱的数据共享机制。
● 隔离隐藏，用户私有数据仅对自己或共享的系统用户可见，对没有授

权的用户实现隐藏隔离；
● 数据共享，每个用户均可对自己的数据进行共享配置，可设置赋予其
它用户读、写权限，实现数据共享。
8.2.2.1. 隔离隐藏
数据隔离保护机制的操作目录为/box。用户使用boxadm 创建一个麒麟
文件保护箱，其中的文件、目录等就不能被别的用户访问。
test 用户创建一个文件保护箱testbox，并新建一个文件：
#boxadm-ctestbox
#touch/box/test/testbox/testfile
test 用户查看/box/test 的目录及文件：
#ls-l/box/test
testbox
#ls-l/box/test/testbox
testfile
但root 用户查看/box 下的子目录，如：
（1）ls-l/box/test 命令输出中没有/box/test/testbox，提示“总用
量0”；
（2）ls-ld/box/test/testbox 命令提示”Nosuchfileordirectory”；
（3）ls -l /box/test/testbox/testfile 命 令 提 示 ”No such file or
directory”
#ls-l/box/test

#ls-ld/box/test/testbox
#ls-l/box/test/testbox/testfile
8.2.2.2. 数据共享
用户使用boxadm 设置文件保护箱中的目录共享权限，如只读共享权限、
读写共享权限，被授权的用户就能访问这个目录。
test 用户设置root 对/box/test/testbox 目录的只读共享权限后，root
用户才能访问/box/test/testbox 目录：
#boxadm-s-uroot-prxtestbox
root 用户执行如下命令，则可以看到/box/test 目录下存在testbox 目
录、/box/test/testbox 目录下存在testfile 文件。
#ls-l/box/test/
#ls-l/box/test/testbox
8.2.2.3. boxadm 的使用方法
test 用 户 创 建 一 个 麒 麟 文 件 保 护 箱 testbox ， 会 生 成 目 录
/box/test/testbox：
#boxadm-ctestbox
设置root 对/box/test/testbox 目录的只读共享权限后，root 用户才能
访问/box/test/testbox 目录：
#boxadm-s-uroot-prxtestbox
设置root 对/box/test/testbox 目录的读写共享权限后，root 用户才能

读写/box/test/testbox 目录：
#boxadm-s-uroot-prwxtestbox
查看/box/test/testbox 目录的Box 信息：
#boxadm-itestbox
Access:user:root:rwx
移除root 用户对/box/test/testbox 目录的共享权限：
#boxadm-s-uroot-Xtestbox
删除/box/test/testbox 目录：
#boxadm-rtestbox
8.2.3. 强制访问控制
在计算机安全领域指一种由操作系统约束的访问控制，目标是限制主体或发
起者访问或对对象或目标执行某种操作的能力。在实践中，主体通常是一个进程
或线程，对象可能是文件、目录、TCP/UDP 端口、共享内存段、I/O 设备等。
主体和对象各自具有一组安全属性。每当主体尝试访问对象时，都会由操作系统
内核强制施行授权规则——检查安全属性并决定是否可进行访问。任何主体对任
何客体的任何操作都将根据一组授权规则（也称策略）进行测试，决定操作是否
允许。
主体和客体：主体指的是访问者(一般为一个进程)，客体指的是被访问的资
源。根据资源的类型不同，在策略配置语言中被分为不同的客体，如dir、file、
process 等。

安全上下文：指标记在所有事物上的扩展属性，包括SELinux 用户、角色、
域或类型，如果使用MLS(多级安全，以下会有介绍)策略还包括安全级。
类型和域：是分配给一个对象并决定谁可以访问这个对象，域对应进程，类
型对应其他对象。
域转换：进程以给定的进程类型运行的能力称为域转换，需满足三个转换条
件：一个进程新的域类型有对一个可执行文件类型的entrypoint 访问权限，进
程的当前（或以前）域类型对入口点文件类型拥有execute 访问权限，进程的
当前域类型对新的域类型拥有transition 访问权限。
角色：和一些域相关联，决定哪些域可以使用。
安全策略用户：和标准linux 用户对应，影响哪个域可以进入。
8.2.3.1. SELinux 基础策略
银河麒麟高级服务器操作系统V10 根据系统安全需求，定制了系统图形登
录功能策略、三权分立功能策略、审计服务策略、执行控制功能策略、白名单功
能策略、kvm/lxc 等系统功能策略、系统使用修订桌面常用工具策略、系统启
动时自动标记脚本功能。根据不同应用场景，定制了ukmls、ukmcs 和target
三套SE 基础策略。
8.2.3.2. 安全策略模式切换
系统在正常启动后就进入了允许模式。允许模式是为了方便我们服务器配置，
即使我们的安全策略没有允许这样的操作仍可以执行，但会被审计下来；强制模
式是只要没有这样的规则操作就不被允许。我们的安全服务器系统在普通模式下
使用的就是允许模式的策略，在默认模式下则使用强制模式的策略来加固我们服

务器的安全。
（1）当前安全策略模式查询
我 们 提 供 了 一 些 工 具 命 令 可 以 查 询 当 前 的 安 全 策 略 模 式 ， 如
/usr/sbin/getenforce 和/usr/sbin/sestatus。所有用户都可以通过这些命令
进行查询。
getenforce 命 令 输 出 若 为Permissive 则 表 示 当 前 处 于 允 许 模 式 ， 若 为
Enforcing 则表示当前处于强制模式。
sestatus 命令的输出可能为以下内容：
#sestatus
SELinuxstatus: enabled
SELinuxfsmount: /sys/fs/selinux
SELinuxrootdirectory: /etc/selinux
Loadedpolicyname: ukmcs
Currentmode: permissive
Modefromconfigfile: enforcing
PolicyMLSstatus: enabled
Policydeny_unknownstatus: allowed
Memoryprotectionchecking: actual(secure)
Maxkernelpolicyversion: 30
它不仅反应了当前安全策略的模式，同时也可以得到其他安全策略的信息：
安全策略开启状态、安全策略文件系统挂载位置、配置文件中的默认模式、安全
策略版本和安全策略目录名称。
（2）安全策略模式修改

为了安全考虑所以系统一启动都会进入到强制模式，但是也提供了可以修改
当前安全策略模式的命令。
setenforce1 设置为强制模式；
setenforce0 设置为允许模式。
系统三个管理员中只有安全管理员可以使用setenforce1 进入到强制模式，
也只有安全管理员可以使用setenforce0 进入到允许模式。
（3）禁用安全策略
在系统出现故障时我们也可以先禁用我们的安全策略进入到维护模式进行
修复。进入方法如下：
a) 系统启动时进入grub 菜单。
b) 输入grub 密码修改内核启动参数，将参数selinux=1enforcing=1
修改为selinux=1enforcing=0 。
（4）启动系统。
这样系统就直接进入到维护模式，由root 用户对系统进行修复。
8.2.3.3. 策略配置命令
为了方便用户对安全策略进行配置，我们也提供了以下命令对当前安全策略
进行配置：
8.2.3.3.1. 查看标记命令
系统中一些常用命令的-Z 选项都提供了查看文件或进程安全上下文的功能，
如：ls-Zid-Z 和ps-Z，这些命令加了-Z 选项后就会显示该文件或进程的安全
上下文，如ls-Z 一个文件，输出以下内容：

-rw-r--r--.rootrootsysadm_u:object_r:admin_home_t:s0
其中sysadm_u 就表示该文件属于sysadm_u 安全策略用户，object_r
表示该文件属于客体类型的角色，admin_home_t 表示该文件的类型，我们的
策略就是根据这些标记来制定规则进行访问控制的。
8.2.3.3.2. 修改标记命令
安全管理员可以通过一些命令对系统中所有文件的安全上下文进行操作，如：
chcon、setfiles 和restorecon 命令。
/bin/ls 命令为bin_t 类型，安全管理员可以输入chcon-tsbin_t/bin/ls
将bin_t 修改为sbin_t 类型。
chcon 命令的-u 参数可以修改该文件安全上下文中的安全策略用户，-r 参
数可以修改它的角色，-l 参数可以修改它的安全级别，其他具体使用可以查看它
的帮助手册。
setfiles 命令可以根据我们的安全策略的标记文件对系统中所有文件进行
标记。如：
setfiles/etc/selinux/targeted/contexts/files/file_contexts/
restorecon 命令可以恢复该文件的默认安全上下文，如：restorecon-r
filename。
在对文件的安全上下文标记进行操作时首先都会检查这个标记的正确性，然
后再去进行操作，具体使用可以查看用户手册。

8.2.3.3.3. 用户、角色和域的关系
登录程序如（login，sshd）负责映射Linux 用户到安全策略用户，在登录
时，如果安全策略用户标识符恰好和一个Linux 用户标识符完全相同，匹配的
安全策略用户标识符成为初始shell 进程安全上下文的用户标识符。安全管理员
也可使用semanage 命令完成映射，如：semanagelogin-a-scharlie_u
charlie，表示Linux 用户charlie 作为安全策略用户charlie_u 登录系统。
使用semanagelogin-l 命令可以查看，如下图：
#semanagelogin-l
登录名 SELinux 用户 MLS/MCS 范围 服务
__default__ user_u s0-s0:c0.c1023 *
auditadm auditadm_u s0-s0:c0.c1023 *
root root s0-s0:c0.c1023 *
secadm secadm_u s0-s0:c0.c1023 *
一个用户可以对应多个角色，但在同一时刻只能作为其中一个角色，可以通
过策略管理工具semanage 输入semanageuser-a-Rmgr_r-Rcharlie_r
-Pusercharlie_u 来创建一个可以作为角色mgr_r 和charlie_r 的安全策略用
户charlie_u。
可通过semanageuser-l 进行查看，如下图：
#semanageuser-l
标记中 MLS/ MLS/

SELinux 用户 前缀 MCS 级别 MCS 范围 SELinux 角色
auditadm_u audadm s0 s0-s0:c0.c1023 auditadm_r
guest_u user s0 s0 guest_r
root sysadm s0 s0-s0:c0.c1023 sysadm_r
secadm_u secadm s0 s0-s0:c0.c1023 secadm_r
角色仅仅是一套域类型的集合，方便与用户建立联系，具体的访问控制都是
通过进程的类型和客体的类型根据制定的规则来进行控制。
8.2.3.3.4. 端口配置
系统中所有端口同样也被标记了类型，安全管理员可以通过：
semanageport-a-tvnc_port_t-pudp222
来添加一个标记了类型的端口。
可通过semanageport-l 进行查看，如下图：
#semanageport-l
SELinux 端口类型 协议 端口号
afs3_callback_port_t tcp 7001
afs3_callback_port_t udp 7001
afs_bos_port_t udp 7007
afs_fs_port_t tcp 2040
afs_fs_port_t udp 7000,7005
afs_ka_port_t udp 7004
8.2.4. 三权分立机制
三权分立系统在满足安全系统标准要求的同时，尽量减少对系统的改动，大
大的提高了系统的可用性和引用性。

主要设计思想是，将传统意义上超级管理员root 的权能进行划分，分别为
root(uid=0)、secadm(uid=600)和auditadm(uid=700)分别作为超级用户
的别名存在，它们分别对应selinux 三权分立角色中的一个角色，从而保持传统
系统用户身份鉴别系统结构的同时，达到三权分立的目的。
三权分立系统是对linux 现有用户身份认证机制的扩展，增加部分功能主要
是为解决传统认证机制无法支持三权分立的用户身份的鉴别。三权系统使用传统
linux 用户身份鉴别机制的认证系统之外可以额外采用双因子验证方式进行身份
鉴别，这套系统是专门定制开发，且符合公安部安全操作系统相关标准。
8.2.4.1. 功能
三权系统功能包括两部分：
1、实现对从一个UID 为0 的超级用户衍生出的三个管理员用户的身份认证
功能，这个认证系统要支持包括登录系统和用户身份切换等所有可能涉及身份权
限切换的情况，同时本三权系统对其他非管理员用户是透明的。
2 、实现 在每次切 换管理员 身份时， 可以按照 不同管理 员指派不 同的
SELinux 角色给用户。
上面两个功能实现的前提是，本三权系统不能影响到系统中传统身份鉴别系
统对非管理员用户的所有验证操作，即新三权系统只对三个管理员用户有作用，
对任何其他用户是透明的。
8.2.4.2. 示例
只有系统管理员具有“分区管理功能”。该功能使系统中只有系统管理员可
对系统磁盘进行分区管理。当三权系统的安全状态为strict 模式时。系统管理员

可在图形界面下，点击应用程序->工具->磁盘。系统管理员可以成功打开分区
编辑器。安全管理员、审计管理员的开始菜单中没有分区编辑器的图标显示。
8.2.4.3. 三权用户命令集
表 8-2 三权用户命令集
用户 命令 备注
系统
管理
员
reboot 系统重启命令。
init 管理系统运行模式。
ifconfig 网络接口管理。
rpm 软件包管理（但不能删除我们的策略包）。
useradd 添加用户。如：useraddabc
passwd 修改普通用户密码。如：passwdabc
userdel 删除用户。如：userdel-rabc
insmod 插入模块。
rmmod 删除模块。
iptables 防火墙相关。
system-config-date
配置系统时间（配置网络时间用到ntpd
服务需要在允许模式下进行）。
system-config-keyboard 键盘配置。
system-config-language 语言配置。
system-config-network
system-config-network-
cmd
网络配置命令。

system-config-network-
tui
system-config-printer
system-config-printer-a
pplet
打印机配置。
setup
整体配置（其中有关服务配置需要切换到
允许模式下进行）。
mount 分区挂载。
baobab 磁盘分析工具。
安全
管理
员
setenforce
开启和关闭selinux 的强制模式
如： setenforce1 开启强制模式
（其他管理员都可执行）；
setenforce0 关闭强制模式
（只有安全管理员才能执行）。
checkpolicy
将可读策略文件编译生成二进制策略文
件 ， 该 文 件 和 所 在 文 件 夹 必 须 为
policy_src_t 类型。
如：checkpolicy-Mpolicy.conf-o
policy.24
load_policy
如：load_policy-bq(服务器)更换内核中
的安全策略，保持使用当前的Boolean
值。

chcon
修改文件和文件夹的安全上下文。如：
chcon-tbin_tfilename
chcat
修改文件和文件夹的敏感级。如：chcat
s0:c0.c255filename
fixfiles
检查修复文件安全上下文。如：fixfiles-F
restorefilename
setfiles
初始化文件标记，检查标记正确性。
如：setfiles
/etc/selinux/targeted/contexts/files/f
ile_contexts/
restorecon
恢复默认文件安全上下文。
如：restorecon-rfilename
semanage
SELinux 管理工具命令
如：semanageuser-l
semanagelogin-l
semanage user -m -R secadm_r
secadm_u
semodule
管理SELinux 策略模块。
如：semodule-l
semodule-imodulename
setsebool
修改SELinux 中的bool 值来修改策略。
如：setseboolxdm_sysadm_login＝1
审计 audit2allow 显示访问被拒绝后应添加的允许规则。如：

管理
员
audit2allow-a
audit2allow-i/var/log/dmesg
audit2why
根据访问被拒绝的审计信息显示被拒绝的
原因。
如 ： audit2why <
/var/log/audit/audit.log
aureport
根据审计信息文件统计登录情况，avc，
pid，file，event，config 等众多信息。
如：aureport-au
aureport-a
aureport-p
ausearch
搜索审计文件中的信息。
如：ausearch-gi0
auditd
启动审计服务。
如：auditd-senable
auditctl
显示服务状态，审计规则相关。
如：auditctl-s
audispd
审计调度器。
如：audispd
8.2.4.4. 启用与关闭
采用如下方式开启三权分立安全功能：
１）执行下述命令开启三权分立安全功能：

2）重启系统，使得修改生效
3）通过security-switch--get 命令查看当前修改后的增强安全状态中“三
权分立”为启用，命令如下：
显示当前安全级别信息：
采用切换其他安全模式可以关闭三权分立安全功能：
8.2.5. 核外安全功能及配置
8.2.5.1. 用户UID 唯一性
操作系统中用户名和用户标识（下称UID）是存在对应关系，一般接口调用
中也是通过UID 判断对应用户，通常情况下UID 在用户创建时会自动分配，也
可以在创建用户时手动设置未被使用的UID，该UID 可以是已删除用户使用过
security-switch--setstrict
security-switch--get
当前安全级别:strict
------------------------------------------------------------------
安全模块 | 当前状态 | 默认状态
------------------------------------------------------------------
SELinux 启用(Enforcing) 启用(Enforcing)
三权分立 启用 启用
执行控制 启用(Normal) 启用(Normal)
security-switch--setdefault

的。而本功能提供对用户UID 的唯一性检查，确保在整个操作系统生命周期内
用户UID 数字仅且只能使用一次。
若系统已经存在kylin_1 用户，且该用户下的UID（可通过命令：id kylin_1
查看kylin_1 用户的uid）为kylin_1-UID，进行如下操作：
1.root 删除用户kylin_1
#userdelkylin_1
2.root 创建kylin_2 用户且指定uid 为kylin_1-UID
#adduserkylin_2–ukylin_1-UID
若没有用户UID 唯一性检查，则添加kylin_2 用户操作可以成功；该功能
使用后步骤2 中的操作将失败，提示:“adduser：无效的用户ID”异常信息。
8.2.5.2. 安全切换工具
security-switch 是系统下提供的一个用于安全配置的工具，通过该工具可
进行如下3 种安全模式的切换：
● default 模式：启用系统kysec 安全机制；
● strict 模式：启用kysec、selinux、三权分立，且selinux 为ukmcs
策略；
● custom 模式:用户自定义安全模式。
8.2.5.2.1. default 模式
设置 default 模式
1.kylin 登录系统，执行：

$sudosecurity-switch--setdefault
2.重启系统
模式状态：
------------------------------------------------------------------
安全模块 | 当前状态 | 默认状态
------------------------------------------------------------------
执行控制 用(Normal) 启用(Normal)
8.2.5.2.2. strict 模式
设置 strict 模式
1.kylin 登录系统，执行：
#sudosecurity-switch--setstrict
（然后分别设置root,secadm,auditadm 三个管理员用户的密码）
2.重启系统
模式状态
------------------------------------------------------------------
安全模块 | 当前状态 | 默认状态
------------------------------------------------------------------
SELinux 启用(Enforcing) 启用(Enforcing)
三权分立 启用 启用
执行控制 启用(Normal) 启用(Normal)
8.2.5.2.3. 自定义模式
设置自定义模式

1.kylin 登录系统，设置自定义安全机制，仅启用selinux 安全机制：
#sudosecurity-switch--setcustom–listselinux
2.重启系统；
模式状态
------------------------------------------------------------------
安全模块 | 当前状态 | 默认状态
------------------------------------------------------------------
SELinux 启用(Enforcing) 启用(Enforcing)
8.2.5.3. 审计系统权限管理
基于基础安全审计系统功能，麒麟操作系统根据三权分立要求，实现管理员
分权机制，修订只允许审计管理员具有审计服务管理权限和审计规则修改权限。
根据其系统安全机制要求，增加审计服务容错机制，当审计服务出错时，审计服
务将自动重启，确保审计服务正常运行；移除SU 到审计管理员的权限，限制系
统管理员通过SU 命令执行auditctl 等审计管理工具。同时，添加了不同等级
的审计规则、修复在启用或不启用三权分立时对用户的判断等功能。
审计系统权限需要在安全状态为 strict 模式时相应功能才会开启，另外要确
保系统的审计服务处于开启状态（systemctl statsu auditd）。
8.2.5.3.1. 登录审计
审计用户登录时，无论成功与否，均会在审计日志中有信息输出。审计日志
包括事件类型、时间、用户、事件成功与否、身份鉴别请求的源等，如：
AuthenticationReport
=====================================

=======
#datetimeaccthosttermexesuccessevent
=====================================
========
2020 年 02 月 09 日 13:21:21 test localhost localdomain
/dev/tty1/usr/libexec/gdm-session-workeryes309
8.2.5.3.2. 行为审计
添加用户审计规则后，用户的行为会被系统审计。
示例（如对uid 为600 的用户添加行为审计规则）：
1.审计管理员添加审计规则，对uid=600 的用户行为进行审计：
#auditctl-aexit,always-Sall-Fuid=600
2.安全管理员执行操作：
#cat/etc/uid_list
3.审计管理员以uid 进行审计信息查看
#ausearch-ui600|grepuid_list
此时可在在审计日志中查看到针对步骤2 的审计信息。
8.2.5.3.3. 账号管理审计
所有用户账号的增加、删除（useradd、passwd、userdel）等行为都会
被系统审计。
示例如下，在审计用户下通过$aureport-m 可以查看到用户账户操作的审
计日志。
1.root 创建一个用户：
#useraddtestuser

2.root 修改用户密码：
#passwdtestuser
3.root 删除用户
#userdeltestuser
8.2.5.3.4. 文件、目录监视审计
添加文件、目录监视规则后，任何对文件、目录的操作都会被系统审计。
示例如下：
步骤1 添加规则后，步骤2、3、4、5 等对于文件及目录的操作均会在审
计日志中体现；步骤6 删除规则后不再记录相关操作审计日志。
存在kylin 用户创建的/tmp/file1 文件及/tmp/test.dir 目录：
1.审计管理员登录系统，添加审计规则：
$auditctl-w/tmp/file1-kfile
$auditctl–w/tmp/test.dir–ktest_dir
2.kylin 用户cat 查看/tmp/file1 文件
3.kylin 用户使用rm 删除/tmp/file1 文件
4.kylin 用户对/tmp/test.dir 进行读操作
5.kylin 用户对/tmp/test.dir 进行写操作
6.审计管理员登录系统，删除步骤1 中审计规则：
$auditctl-W/tmp/file1-kfile
$auditctl–W/tmp/test.dir–ktest_dir

8.2.5.3.5. 规则配置
添加排除规则后可以排除指定的审计信息。
8.2.5.3.6. 审计日志保护
只有审计管理员才可以查看审计日志。
8.2.5.3.7. 审计日志转储
当审计日志达到设置的最大值时，会覆盖所存储的最早的审计记录。该部分
需要审计管理员对配置文件/etc/audit/auditd.conf 进行手动编辑。
示例：
1. 审 计 管 理 员 编 辑 /etc/audit/auditd.conf 文 件 ， 使
max_log_file=1，num_logs=3 即日志文件最大为1MB，备份文
件数为3
2.审计管理员重启服务
#systemctlrestartauditd
3.审计管理员添加审计规则，
$auditctl-aexit,always-Sall-Fsys
4.安全管理员随便执行任何操作，以便产生大量日志
$find/
5.审计管理员查看审计日志文件大小和数目
$ls-lh/var/log/audit
经步骤5 查看，系统审计日志有：audit.log、audit.log.1、audit.log.2，
其中audit.log.1、audit.log.2 文件大小都为1M 左右。

8.2.5.3.8. 审计日志超阈值警报
当审计跟踪的磁盘空间已到达极限时，有审计报警日志提醒。对于磁盘空间
极限值的设定，需要审计用户手动调整/etc/audit/auditd.conf。
示例，假设系统日志分区总大小50G，将30G 设置为剩余空间阈值，即当
用系统日志占用20G 以上时会有审计日志产生：
1. 审 计 管 理 员 编 辑 /etc/audit/auditd.conf 文 件 ， 使
space_left=30000 ， 即 日 志 磁 盘 空 间 阈 值 为 30G ；
space_left_action=SYSLOG，即向日志中写入一条报警日志；（具
体磁盘空间阀值根据测试系统实际日志磁盘空间确定）
2.审计管理员重启服务
#systemctlrestartauditd
步骤2 后，如果日志空间剩余容量小于30G，则审计日志中将产生审计日
志信息。
8.2.5.4. 支持安全机制审计日志类别
目前审计服务会根据系统中不同的安全机制功能写入不同类型的审计日志
类型。
如kysec 类的审计日志类型为：
type=KYSEC_STATUS msg=audit(1502938410.303:182384):
status=2 old_status=4 loginuid=0 session=4294967295
id=1502938410.303:182384
type=KYSEC_AVC msg=audit(1502886638.943:132300):
denied { execute } for pid=15325 comm='bash'

name='/secadm/ls' ssid=0x01 osid=0x00 loginuid=0
session=4294967295 kysec_status=4
id=1502886638.943:132300
8.3. 麒麟安全管理工具-安全中心
安全中心是一款基于麒麟安全框架KYSEC 的管理工具，提供系统安全加固、
账户保护配置、联网控制、应用执行控制和应用防护等功能，保障系统运行环境
的安全和稳定。
安全中心集安全加固、账户保护、网络保护和应用保护等功能于一体，全面
保障系统运行环境的安全。
安全加固：提供安全服务、内核参数、安全网络、系统命令、系统审计、系
统设置、潜在危险、文件权限、风险账户、磁盘检查、密码强度、账户锁定、系
统安全、系统维护、资源分配等多维度的扫描与一键加固，及时发现并处理系统
安全隐患。
账户保护：提供系统账户密码强度检查和账户锁定机制，实现对系统账户的
统一管控，提升系统账户安全防御能力，有效防止密码被暴力破解。
网络保护：提供应用联网控制功能，实时防护未知应用网络行为，阻断主动
外联及其它异常网络活动，提高网络访问安全性。
应用控制：提供应用程序执行控制功能，阻止未知软件、应用程序的恶意执
行，避免木马病毒攻击，保障系统运行环境的安全可靠。
应用防护：提供进程防杀死、内核模块防卸载和文件防篡改功能，保护系统
关键文件完整性，阻止系统关键应用服务异常中断。

详细内容请参考《银河麒麟安全中心用户手册》。
8.4. 麒麟文件保护箱
麒麟文件保护箱是基于内核级数据隔离机制的保护工具，提供用户间数据隔
离和加密保护功能，支持国密算法，实现一箱一密、一文一密的细粒度控制，保
障用户数据安全。
具有如下特点：
多重防护：支持用户间数据隔离以及细粒度的权限控制，保障数据安
全。
安全加密：支持一箱一密、一文一密的透明加密机制，且对密钥进行
安全管理，能够满足政企和金融级客户的核心安全诉求。
丰富算法：支持标准国际算法、国密算法和硬件级加密算法，能够满
足不同安全等级的加密应用场景。
高兼容性：支持保护箱版本兼容机制，用户升级适配无感知，保证用
户数据安全存储、永不丢失。
简单易用：支持内置文件管理器，实现统一管理，操作简单、易于上
手。
详细内容请参考《银河麒麟文件保护箱用户手册》。