# ============================================================================
#  IPAS 智能助手 —— 前端构建 + 托管镜像（多阶段）
#
#  阶段 1（build）：用 Node 构建 Vite 项目，产出纯静态文件到 dist/
#  阶段 2（run）：用 nginx 托管 dist/，并把 /api 反代到 backend 服务。
#
#  ⚠️ SSE / 流式对话必须关闭 nginx 缓冲（proxy_buffering off），
#     这对应开发环境 vite.config.ts 里那段「流式连接永不结束」的修复——
#     生产环境由 nginx 承担反代，同样要关缓冲，否则 chat 流式会卡在「一直转圈」。
# ============================================================================
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
# 用国内镜像加速，避免 npm 卡死（本机环境曾因 hosts 劫持导致 install 挂起）。
# 若你的网络直连 npmjs 正常，可去掉 --registry 参数。
RUN npm install --no-audit --no-fund --registry https://registry.npmmirror.com
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
