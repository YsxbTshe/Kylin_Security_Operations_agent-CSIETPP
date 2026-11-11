# 审批令牌 SM2 密钥（不要提交真实私钥）

`stack.yml` 把本目录只读挂载到后端容器的 `/etc/kylinops/keys`，后端用
`APPROVAL_SIGNING_PRIVATE_KEY_REF` / `APPROVAL_SIGNING_PUBLIC_KEY_REF` 按路径读取。

生成一对（PKCS#8 私钥 + PKIX 公钥）：

```bash
cd deploy/docker/secrets
# 不要用 `openssl ecparam -genkey -name SM2`：Ubuntu OpenSSL 3.0.2 产出的
# PKCS#8 算法 OID 是 sm2，后端解析不了。
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:SM2 -out approval-private.pem
openssl pkey -in approval-private.pem -pubout -out approval.pub
chmod 600 approval-private.pem
# 自检：应看到 id-ecPublicKey，而不是单独的 sm2 算法 OID
openssl asn1parse -in approval-private.pem | head
```

公钥还要下发到每个被纳管节点：`/etc/kylin-privhelper/approval.pub`（root:root 0644），
并保证节点侧 `KYLIN_PRIVHELPER_APPROVAL_KEY_ID` 与后端 `APPROVAL_SIGNING_KEY_ID` 相同。
私钥**只**留在控制面，Agent 与特权助手都不持有签发能力（ADR-002）。

离线试跑可以先用 `contracts/test-vectors/TEST_ONLY_sm2_private.pem` /
`TEST_ONLY_sm2_public.pem`，但那是公开测试向量，禁止用于任何对外演示或生产。

> 路径已配置时后端启动即加载并试签，配错会直接起不来。三个变量都空时仍可启动
> （仅开发），但第一次审批会失败。正式部署不要留空。
