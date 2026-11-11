# 控制面后端镜像（多阶段：Maven 构建 → JRE 运行）。
#
# 构建上下文是 services/backend/，不是仓库根目录——见 stack.yml 的 build.context。
# 仓库里没有 mvnw/mvn wrapper，所以构建阶段直接用官方 maven 镜像，评委机器上
# 不需要预装 JDK 或 Maven。
#
# 单独构建（一般不用，交给 docker compose build backend）：
#   docker build -f deploy/docker/backend.Dockerfile -t kylinops-backend:local services/backend
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# pom 与源码一起 COPY：没有 dependency:go-offline 这一层，依赖缓存完全靠下面的
# cache mount（BuildKit / buildah 都支持），比"先 COPY pom 再 go-offline"更稳。
COPY pom.xml ./
COPY src ./src
# 跳过单测与 IT：镜像构建不是测试关口，测试在 CI 里跑（.github/workflows/backend-tests.yml）。
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests -DskipITs package \
    && cp target/backend-*.jar /tmp/app.jar

FROM eclipse-temurin:17-jre
# curl 只为 HEALTHCHECK；--no-install-recommends 保持镜像小。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /opt/kylinops/backend --shell /usr/sbin/nologin kylinops

WORKDIR /opt/kylinops/backend
COPY --from=build /tmp/app.jar app.jar
# data/ 必须是卷：H2 库、自签 CA（host-agent-certs）、制品目录都落在这里，
# 丢了 host-agent-certs 等于把所有已纳管节点踢下线。
RUN mkdir -p data && chown -R kylinops:kylinops /opt/kylinops/backend
USER kylinops

# 8080 HTTP / 9090 gRPC control / 9091 gRPC enroll。节点是**主动拨入**这两个 gRPC 端口的，
# 所以它们必须发布到宿主机，否则 Kylin 节点连不上控制面。
EXPOSE 8080 9090 9091

# 容器内存上限交给 JVM 自己识别 cgroup，不写死 -Xmx。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/system/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
