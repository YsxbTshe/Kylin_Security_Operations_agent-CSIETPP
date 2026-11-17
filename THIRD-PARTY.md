# 第三方依赖与许可证清单（THIRD-PARTY NOTICES）

本项目（麒麟安全智能运维 Agent）**未采用开源许可证**，著作权归参赛团队所有、保留所有权利，见仓库根 [`COPYRIGHT.md`](COPYRIGHT.md)。
本文件披露的是本项目**所依赖的第三方组件**及其各自的许可证——它们与本项目自身的版权状态相互独立。
本文件列出**直接依赖**及其许可证，供合规审查与供应链核查使用。

## 编制口径（重要）

- **只登记可核查的证据**。每条许可证均来自本机已下载的依赖元数据，而非记忆或推测：
  - Java / Maven：`~/.m2/repository/**/<artifact>-<version>.pom` 中的 `<licenses><license><name>`（父 POM 继承时向上追溯）。
  - Go：`$(go env GOMODCACHE)/<module>@<version>/LICENSE`（读取许可证正文首部标识）。
  - npm：`apps/<app>/node_modules/<pkg>/package.json` 的 `license` 字段（版本已与 `package-lock.json` 逐条比对一致）。
- 读不到元数据、或元数据本身含混的，一律标注 **`待确认`**，不做补白。
- 本清单只覆盖**直接依赖**（Go 侧额外列出 `go.mod` 中显式记录的 `// indirect`），传递闭包请以 SBOM 为准（见 [`scripts/generate-sbom.sh`](scripts/generate-sbom.sh)）。
- 编制日期：2026-08-15。版本随 `pom.xml` / `go.mod` / `package-lock.json` 变更后需重新生成。

许可证名称按各依赖元数据中的**原始写法**记录（如 `Apache License, Version 2.0` / `Apache 2.0` / `Apache-2.0` 同为 Apache-2.0），未做归一化改写，以便回溯。

---

## 1. 后端 Java（`services/backend/pom.xml`）

版本来源：`(pom)` = 本仓库 POM 中显式声明；`(managed)` = 由 `spring-boot-starter-parent 4.0.6` 或 `opentelemetry-bom 1.63.0` 的 `dependencyManagement` 解析得到。

| 依赖 | 版本 | 许可证 | scope |
|---|---|---|---|
| org.springframework.boot:spring-boot-starter-jdbc | 4.0.6 (managed) | Apache License, Version 2.0 | compile |
| org.springframework.boot:spring-boot-starter-validation | 4.0.6 (managed) | Apache License, Version 2.0 | compile |
| org.springframework.boot:spring-boot-starter-web | 4.0.6 (managed) | Apache License, Version 2.0 | compile |
| org.springframework.boot:spring-boot-starter-actuator | 4.0.6 (managed) | Apache License, Version 2.0 | compile |
| org.springframework.boot:spring-boot-starter-test | 4.0.6 (managed) | Apache License, Version 2.0 | test |
| org.flywaydb:flyway-core | 11.14.1 (managed) | Apache License, Version 2.0 | compile |
| org.flywaydb:flyway-database-postgresql | 11.14.1 (managed) | Apache License, Version 2.0 | compile |
| io.micrometer:micrometer-registry-prometheus | 1.16.5 (managed) | The Apache Software License, Version 2.0 | compile |
| io.opentelemetry:opentelemetry-api | 1.63.0 (managed) | The Apache License, Version 2.0 | compile |
| org.apache.pdfbox:pdfbox | 3.0.6 (pom) | Apache-2.0 | compile |
| org.postgresql:postgresql | 42.7.11 (pom) | BSD-2-Clause | runtime |
| com.h2database:h2 | 2.4.240 (managed) | MPL 2.0 **OR** EPL 1.0（双许可） | compile |
| org.bouncycastle:bcpkix-jdk18on | 1.82 (pom) | Bouncy Castle Licence（MIT 型） | compile |
| io.github.erdtman:java-json-canonicalization | 1.1 (pom) | Apache License, Version 2.0 | compile |
| io.grpc:grpc-netty-shaded | 1.76.0 (pom) | Apache 2.0 | compile |
| io.grpc:grpc-stub | 1.76.0 (pom) | Apache 2.0 | compile |
| io.grpc:grpc-protobuf | 1.76.0 (pom) | Apache 2.0 | compile |
| com.google.protobuf:protobuf-java | 4.33.2 (pom) | BSD-3-Clause | compile |

构建插件：

| 插件 | 版本 | 许可证 |
|---|---|---|
| org.codehaus.mojo:build-helper-maven-plugin | 3.6.1 | The MIT License |
| org.springframework.boot:spring-boot-maven-plugin | 4.0.6 (managed) | Apache License, Version 2.0 |

> H2 为双许可（MPL-2.0 / EPL-1.0），本项目仅在测试与本地开发中作为嵌入式数据库**原样使用**、未修改其源码，因此不触发 MPL 的文件级 copyleft 义务；生产库为 PostgreSQL + pgvector。

---

## 2. Go 节点执行端（`agents/node-executor/go.mod`）

模块路径：`gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor`（`go 1.26.3`）。

### 2.1 直接依赖

| 模块 | 版本 | 许可证 |
|---|---|---|
| github.com/cyberphone/json-canonicalization | v0.0.0-20241213102144-19d51d7fe467 | Apache-2.0 |
| github.com/emmansun/gmsm | v0.43.0 | MIT |
| github.com/santhosh-tekuri/jsonschema/v6 | v6.0.2 | Apache-2.0 |
| go.opentelemetry.io/otel | v1.44.0 | Apache-2.0 |
| go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc | v1.44.0 | Apache-2.0 |
| go.opentelemetry.io/otel/sdk | v1.44.0 | Apache-2.0 |
| go.opentelemetry.io/otel/trace | v1.44.0 | Apache-2.0 |
| google.golang.org/grpc | v1.81.1 | Apache-2.0 |
| google.golang.org/protobuf | v1.36.11 | BSD-3-Clause |

### 2.2 `go.mod` 中显式记录的间接依赖（`// indirect`）

| 模块 | 版本 | 许可证 |
|---|---|---|
| github.com/cenkalti/backoff/v5 | v5.0.3 | MIT |
| github.com/cespare/xxhash/v2 | v2.3.0 | MIT |
| github.com/go-logr/logr | v1.4.3 | Apache-2.0 |
| github.com/go-logr/stdr | v1.2.2 | Apache-2.0 |
| github.com/google/uuid | v1.6.0 | BSD-3-Clause |
| github.com/grpc-ecosystem/grpc-gateway/v2 | v2.29.0 | BSD-3-Clause |
| go.opentelemetry.io/auto/sdk | v1.2.1 | Apache-2.0 |
| go.opentelemetry.io/otel/exporters/otlp/otlptrace | v1.44.0 | Apache-2.0 |
| go.opentelemetry.io/otel/metric | v1.44.0 | Apache-2.0 |
| go.opentelemetry.io/proto/otlp | v1.10.0 | Apache-2.0 |
| golang.org/x/crypto | v0.51.0 | BSD-3-Clause |
| golang.org/x/net | v0.55.0 | BSD-3-Clause |
| golang.org/x/sys | v0.45.0 | BSD-3-Clause |
| golang.org/x/text | v0.37.0 | BSD-3-Clause |
| google.golang.org/genproto/googleapis/api | v0.0.0-20260526163538-3dc84a4a5aaa | Apache-2.0 |
| google.golang.org/genproto/googleapis/rpc | v0.0.0-20260526163538-3dc84a4a5aaa | Apache-2.0 |

> 国密实现 `github.com/emmansun/gmsm`（SM2/SM3/SM4）为 MIT，可自由用于本作品的签名与哈希链路。

---

## 3. Go 沙箱运行器（`agents/workspace-runner/go.mod`）

模块路径：`gitee.com/SuperSkidder/kylin-security-ops-agent/agents/workspace-runner`（`go 1.26.3`）。

**零第三方依赖** —— `go.mod` 无任何 `require`，仅使用 Go 标准库（该模块亦无 `go.sum`）。

---

## 4. 前端 `apps/admin-web`（`package.json` / `package-lock.json`）

| 包 | 版本（lockfile 解析） | 许可证 | 类型 |
|---|---|---|---|
| axios | 1.16.0 | MIT | runtime |
| echarts | 6.1.0 | Apache-2.0 | runtime |
| lucide-vue-next | 1.0.0 | ISC | runtime |
| markdown-it | 14.1.1 | MIT | runtime |
| pinia | 3.0.4 | MIT | runtime |
| reka-ui | 2.9.8 | MIT | runtime |
| three | 0.178.0 | MIT | runtime |
| vue | 3.5.32 | MIT | runtime |
| vue-router | 5.0.4 | MIT | runtime |
| @types/markdown-it | 14.1.2 | MIT | dev |
| @types/node | 24.12.2 | MIT | dev |
| @types/three | 0.178.0 | MIT | dev |
| @vitejs/plugin-vue | 6.0.5 | MIT | dev |
| @vue/tsconfig | 0.9.1 | MIT | dev |
| typescript | 6.0.2 | Apache-2.0 | dev |
| vite | 8.0.11 | MIT | dev |
| vue-tsc | 3.2.6 | MIT | dev |

## 5. 前端 `apps/dashboard-screen`

| 包 | 版本（lockfile 解析） | 许可证 | 类型 |
|---|---|---|---|
| echarts | 6.0.0 | Apache-2.0 | runtime |
| vue | 3.5.32 | MIT | runtime |
| @types/node | 24.12.2 | MIT | dev |
| @vitejs/plugin-vue | 6.0.5 | MIT | dev |
| @vue/tsconfig | 0.9.1 | MIT | dev |
| typescript | 6.0.2 | Apache-2.0 | dev |
| vite | 8.0.11 | MIT | dev |
| vue-tsc | 3.2.6 | MIT | dev |

## 6. 前端 `apps/finals-concept-lab`（概念验证原型）

| 包 | 版本（lockfile 解析） | 许可证 | 类型 |
|---|---|---|---|
| three | 0.178.0 | MIT | runtime |
| vue | 3.5.41 | MIT | runtime |
| @vitejs/plugin-vue | 6.0.8 | MIT | dev |
| vite | 7.3.6 | MIT | dev |

---

## 7. 构建 / 协议工具链（非产物依赖）

| 工具 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| github.com/bufbuild/buf | v1.72.0 | Apache-2.0 | `proto/v2` 的 lint / breaking / generate（`scripts/verify-agent-contracts.sh`） |

---

## 8. 演示 / 集成环境容器镜像（`deploy/`、CI service container）

这些镜像**不打包进本作品的分发物**，仅用于本地演示栈与集成测试。镜像内含大量组件，其整体许可以镜像发布方声明为准，本清单不做推断：

| 镜像 | 许可证 |
|---|---|
| pgvector/pgvector:pg16 | 待确认 |
| prom/prometheus:v3.5.0 | 待确认 |
| prom/node-exporter:v1.8.2 | 待确认 |
| grafana/grafana:12.1.1 | 待确认 |
| grafana/tempo:2.8.2 | 待确认 |
| otel/opentelemetry-collector-contrib:0.139.0 | 待确认 |
| docker.elastic.co/elasticsearch/elasticsearch:8.15.3 | 待确认 |
| docker.elastic.co/elasticsearch/elasticsearch:9.2.1 | 待确认 |
| milvusdb/milvus:v2.6.0 | 待确认 |
| minio/minio:RELEASE.2025-07-23T15-54-02Z | 待确认 |
| quay.io/coreos/etcd:v3.5.18 | 待确认 |
| redis/redis-stack-server:7.4.0-v8 | 待确认 |

> Grafana / Elasticsearch / MinIO 等在近年存在许可证变更（AGPL、SSPL、Elastic License 等），必须以**所用具体 tag** 的官方声明为准，因此此处一律标 `待确认`，待有网环境用 SBOM 工具核实后回填。

---

## 9. 非代码第三方材料

| 材料 | 位置 | 权利人 | 说明 |
|---|---|---|---|
| 麒麟 V10 官方手册 / 白皮书 / FAQ（PDF 及其抽取文本） | `rag-corpus/kylinos/`、`rag-corpus/kysec-security/` | 麒麟软件有限公司 | 见 [`rag-corpus/MANIFEST.md`](rag-corpus/MANIFEST.md)：仅用于本参赛作品的 RAG 检索实验，版权归原权利人 |
| openKylin 社区文档 | `rag-corpus/openkylin/` | openKylin 社区 | 同上 |
| firewalld / systemd / audit / sudo / SELinux / libpwquality 上游文档 | `rag-corpus/linux-ops/` | 各上游项目 | 上游文档，许可证见 `MANIFEST.md` 逐条标注 |
| 官方赛题解析 PPT 截图（13 张） | `docs/ppt/*.png` | 赛事主办方 / 麒麟软件有限公司 | 见 [`docs/ppt/SOURCE.md`](docs/ppt/SOURCE.md)：仅供参赛团队内部对齐赛题要求，不随作品对外分发 |

---

## 复核方式

```bash
# Maven：解析并打印直接依赖树（需可访问 Maven 仓库或已有 ~/.m2 缓存）
cd services/backend && mvn -B dependency:list

# Go：列出全部模块（含传递闭包），许可证正文在 $(go env GOMODCACHE)/<module>@<ver>/LICENSE
cd agents/node-executor && go list -m -json all

# npm：许可证字段来自已安装的 node_modules
cd apps/admin-web && npm ls --all --json

# 完整 SBOM（CycloneDX）：
./scripts/generate-sbom.sh
```
