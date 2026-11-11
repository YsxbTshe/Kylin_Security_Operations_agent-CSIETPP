# admin-web 与 dashboard-screen 共用的前端镜像（多阶段：Vite 构建 → nginx 静态托管）。
#
# 构建上下文是具体的 apps/<app>/ 目录，靠 stack.yml 的 build.context 区分；
# 两个应用的目录结构完全一致（package.json / index.html / vite.config.ts / src / public），
# 所以一份 Dockerfile 够用。
#
# ⚠️ Vite 的 VITE_* 是**构建期**变量，不是运行期变量：它们被内联进 JS 产物。
# 因此改 API 地址或大屏 token 必须重新 build，改 compose 的 environment 没有任何效果。
# Vite 的 loadEnv 会优先采用进程里以 VITE_ 开头的变量，所以下面用 ARG→ENV 注入即可，
# 不需要生成 .env.production。
FROM node:22-alpine AS build
WORKDIR /app

# 先装依赖再拷源码：源码变动时不必重新 npm ci。
COPY package.json package-lock.json ./
RUN npm ci

COPY index.html vite.config.ts tsconfig.json tsconfig.app.json tsconfig.node.json ./
COPY src ./src
COPY public ./public

# 默认值对应"同源反代"部署形态（见 nginx-spa.conf）。
ARG VITE_API_BASE_URL=/api/v1
# 仅 dashboard-screen 用：必须与后端 SCREEN_SHARED_TOKEN 一致，否则大屏拿到 401。
ARG VITE_SCREEN_TOKEN=
ARG VITE_SCREEN_ORGANIZATION_ID=org-alpha
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_SCREEN_TOKEN=$VITE_SCREEN_TOKEN \
    VITE_SCREEN_ORGANIZATION_ID=$VITE_SCREEN_ORGANIZATION_ID

RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# server 段由 stack.yml 挂载 deploy/docker/nginx-spa.conf 到 /etc/nginx/conf.d/default.conf。
# 不 COPY 进镜像是因为它在 deploy/docker/ 下，不在本镜像的构建上下文里；
# 挂载的额外好处是改反代规则不用重建镜像。
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=5 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
