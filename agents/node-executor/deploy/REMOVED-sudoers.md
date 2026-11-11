# sudoers 授权已移除（ADR-002）

原 `deploy/sudoers.d/kylin-agent` 已删除。**若目标机上装过它，必须手工清理：**

```bash
sudo rm -f /etc/sudoers.d/kylin-agent
sudo visudo -c            # 确认语法仍完好
sudo -l -U kylin-agent    # 应显示该用户无任何免密命令
```

## 为什么删

那份白名单按命令粒度放行、不限制参数，实测是 **root 等价**的：

```console
kylin-agent$ sudo -n openssl rand -out /root/_p 8
kylin-agent$ sudo ls -l /root/_p
-rw-r--r-- 1 root root 8 Aug  9 09:10 /root/_p
```

写 `/etc/sudoers.d/` 或 `/etc/cron.d/` 即完整提权。清单里的 `sysctl`
（`-w kernel.core_pattern='|...'`）与 `systemctl`（`link` 任意 unit 后 `start`）
同样各是一条独立的 root 代码执行路径。

一旦 Agent 进程被攻陷，攻击者立即获得 root——应用层的风险分级、审批门控、
本地 guardrail 在这一步之后都不再有意义。

收紧参数也救不回来：`sudoers(5)` 明确说明命令行参数是**拼接成单个字符串**后匹配的，
`*` 会跨词边界，因此表达不了「这里是一个 IPv4 地址」这类约束。

## 现在怎么做

- 只读工具：以 `kylin-agent` 身份直接执行，实测无需任何特权；
- 需要特权的写操作：经 `kylin-privhelper`，它只认识一张固定的操作映射表，
  自行校验参数、自行拼装命令行，并独立校验审批令牌。

详见 `docs/01-架构设计/ADR-002-特权分离与审批下沉.md`。
