#!/usr/bin/env python3
"""Seed a compact, truthful and repeatable project demonstration dataset."""

from __future__ import annotations

import pathlib
import subprocess
import time

from demo_lib import DemoApi, REPO_ROOT, data, load_env, require

SSH_KEY = REPO_ROOT / "vms" / "kylin" / "kylin_vm_key"
SSH_BASE = [
    "ssh",
    "-i",
    str(SSH_KEY),
    "-o",
    "StrictHostKeyChecking=no",
    "-o",
    "UserKnownHostsFile=/dev/null",
    "-o",
    "LogLevel=ERROR",
]

DOCUMENTS = [
    {
        "title": "项目演示：业务服务安全重启规范",
        "sourceType": "DEMO_VERIFIED",
        "content": (
            "business-service 是项目演示专用 systemd 服务。读取状态使用 kylin.service.status，"
            "属于只读操作；重启使用 kylin.service.restart，风险为 R3，必须由第二人审批。"
            "发起人不得自批。审批令牌由控制面使用 SM2-with-SM3 签发，节点 privhelper "
            "只持公钥并逐字段复核，令牌一次性消费。"
        ),
    },
    {
        "title": "项目演示：麒麟节点安全姿态",
        "sourceType": "DEMO_VERIFIED",
        "content": (
            "受纳管节点为银河麒麟 V11 aarch64。Native Guard 采集 KYSEC、SELinux、auditd "
            "和安全开关姿态。R3 以上写操作要求姿态新鲜；姿态未知、过期或 CRITICAL 时默认拒绝。"
        ),
    },
    {
        "title": "项目演示：模型与执行权边界",
        "sourceType": "DEMO_VERIFIED",
        "content": (
            "模型负责理解目标和提出工具调用，不持有 root 权限、审批签发私钥或通用 shell。"
            "所有调用依次经过 grant、PolicyEngine、意图一致性、ABAC、Native Guard 和审批门禁；"
            "节点执行器及 privhelper 保留最终拒绝权。"
        ),
    },
    {
        "title": "项目演示：审计与哈希链",
        "sourceType": "DEMO_VERIFIED",
        "content": (
            "Agent run 的关键事件按顺序写入 SM3 哈希链。每个事件哈希覆盖前序哈希与规范化载荷。"
            "在线验链接口可以证明链完整，若任一事件被修改则返回断裂序号。"
        ),
    },
    {
        "title": "项目演示：安全事件闭环",
        "sourceType": "DEMO_VERIFIED",
        "content": (
            "SYNTHETIC 告警可转换为事件，经过研判后发起 CAUTIOUS 受控处置任务。"
            "事件响应沿用同一套策略、ABAC、双人审批、节点执行与审计链，不绕过 Agent 门禁。"
        ),
    },
]


def ssh(port: int, command: str) -> None:
    subprocess.run(
        [*SSH_BASE, "-p", str(port), f"{'ubuntu' if port == 22110 else 'kylin'}@127.0.0.1", command],
        check=True,
        text=True,
    )


def ssh_script(port: int, script: str) -> None:
    subprocess.run(
        [
            *SSH_BASE,
            "-p",
            str(port),
            f"{'ubuntu' if port == 22110 else 'kylin'}@127.0.0.1",
            "bash -s",
        ],
        input=script,
        check=True,
        text=True,
    )


def seed_alerts() -> None:
    sql = """
insert into app_alert_event (organization_id, level, type, host, status, suggestion)
select 'org-alpha','HIGH','AUTH_ANOMALY','node-kylin-01','OPEN','发起受控研判并核查异常登录'
where not exists (select 1 from app_alert_event where organization_id='org-alpha' and type='AUTH_ANOMALY' and host='node-kylin-01');
insert into app_alert_event (organization_id, level, type, host, status, suggestion)
select 'org-alpha','MEDIUM','SERVICE_HEALTH','node-kylin-02','OPEN','检查 business-service 状态与近期日志'
where not exists (select 1 from app_alert_event where organization_id='org-alpha' and type='SERVICE_HEALTH' and host='node-kylin-02');
insert into app_alert_event (organization_id, level, type, host, status, suggestion)
select 'org-alpha','LOW','DISK_USAGE','node-kylin-03','ACKNOWLEDGED','检查演示日志目录容量'
where not exists (select 1 from app_alert_event where organization_id='org-alpha' and type='DISK_USAGE' and host='node-kylin-03');
update app_user_account
set must_change_password = false
where username in ('admin', 'secops', 'auditor', 'analyst');
"""
    remote = f"""set -e
sudo systemctl stop kylinops-backend
sudo -u kylinops /usr/lib/jvm/java-17-openjdk-arm64/bin/java -cp /opt/kylinops/tools/h2.jar org.h2.tools.Shell \
  -url 'jdbc:h2:file:/var/lib/kylinops-demo/data/dev-db;MODE=PostgreSQL;AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false' \
  -user sa -password '' -sql "$(cat <<'SQL'
{sql}
SQL
)"
sudo systemctl start kylinops-backend
"""
    ssh_script(22110, remote)


def wait_backend(base_url: str) -> None:
    for _ in range(90):
        try:
            DemoApi(base_url).request("/actuator/health", timeout=3)
            return
        except Exception:
            time.sleep(2)
    raise RuntimeError("后端在 seed 后未恢复")


def seed_node_files() -> None:
    for port, index in ((22121, "01"), (22122, "02"), (22123, "03")):
        ssh(
            port,
            "sudo install -d -m 0755 -o root -g root /var/log/aiops-demo /tmp/aiops-demo-logs; "
            f"printf 'project-demo node-{index} normal service log\\n' | "
            f"sudo tee /var/log/aiops-demo/business-service-node-{index}.log >/dev/null; "
            "sudo touch -d '45 days ago' /tmp/aiops-demo-logs/expired-demo.log; "
            "sudo systemctl restart business-service",
        )


def main() -> int:
    env = load_env()
    require(
        env,
        "DEMO_BASE_URL",
        "DEMO_ADMIN_USERNAME",
        "DEMO_ADMIN_PASSWORD",
        "SILICONFLOW_CHAT_MODEL",
    )
    seed_alerts()
    wait_backend(env["DEMO_BASE_URL"])
    seed_node_files()

    api = DemoApi(env["DEMO_BASE_URL"])
    api.login(env["DEMO_ADMIN_USERNAME"], env["DEMO_ADMIN_PASSWORD"])
    configs = data(api.request("/api/v1/platform/llm-config"))
    flash = next(item for item in configs if item["model"] == env["SILICONFLOW_CHAT_MODEL"])
    for config in configs:
        if config["enabled"]:
            api.request(f"/api/v1/platform/llm-config/{config['id']}", "PUT", {"enabled": False})
    try:
        # 文档入库时图谱构建会调用 LLM。Seed 阶段临时关闭模型，使用确定性图谱抽取，
        # 避免供应商 JSON 输出重试拖慢一键初始化；完成后恢复真实 Flash。
        existing = {item["title"]: item for item in data(api.request("/api/v1/rag/documents"))}
        created = []
        for document in DOCUMENTS:
            if document["title"] in existing:
                created.append(existing[document["title"]])
                continue
            created.append(data(api.request("/api/v1/rag/documents", "POST", document, timeout=60)))
            print("RAG +", document["title"])

        memories = data(api.request("/api/v1/rag/memories"))
        if not any(item["title"] == "录制任务安全边界" for item in memories):
            api.request(
                "/api/v1/rag/memories",
                "POST",
                {
                    "memoryType": "OPERATIONS",
                    "title": "录制任务安全边界",
                    "content": "写操作只允许重启 business-service；禁止现场变更防火墙、软件包和 KYSEC。",
                    "importance": 5,
                    "sourceDocumentId": created[0]["id"],
                },
                timeout=60,
            )

        api.request(
            "/api/v1/incidents/intake",
            "POST",
            {
                "title": "项目演示：node-kylin-01 异常登录",
                "severity": "HIGH",
                "category": "AUTH_ANOMALY",
                "sourceIp": "203.0.113.66",
                "targetNodeId": "node-kylin-01",
                "dataMode": "SYNTHETIC",
                "summary": "用于演示告警、事件、研判与受控处置闭环。",
                "evidenceJson": '{"failedLogins":12,"window":"5m","mode":"SYNTHETIC"}',
                "runbookId": "rb-auth-anomaly-100",
            },
        )
        api.request("/api/v1/rag/graph/rebuild", "POST", timeout=60)
        indexed = data(api.request("/api/v1/rag/reindex-external", "POST", timeout=60))
    finally:
        api.request(f"/api/v1/platform/llm-config/{flash['id']}", "PUT", {"enabled": True})
    print(f"演示 seed 完成：RAG 文档 {len(DOCUMENTS)}，ES 重建 {indexed} 个分片，3 台节点日志已准备。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
