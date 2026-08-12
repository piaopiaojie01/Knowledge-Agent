# Vue 前端 —— 多阶段构建（Node 构建 → nginx 静态托管）

# ---- 构建阶段 ----
FROM node:20-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- 运行阶段 ----
# 用 nginx:alpine（不锁小版本，避免 1.27 小版本 tag 拉取不稳定）
FROM nginx:alpine
COPY --from=build /build/dist /usr/share/nginx/html
# 容器版 nginx 配置（反代 backend，root 指向镜像内静态目录）
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1/"] || true
