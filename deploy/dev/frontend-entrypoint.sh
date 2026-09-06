#!/bin/sh
# 开发服务器的前端入口：装依赖后跑 Vite dev server，对外监听。
#
# node_modules 用的是容器自己的卷（compose 里盖在 bind mount 上面），不能共用宿主机那份——
# rollup / esbuild 装的是平台相关的原生二进制，macOS 的那份在 Linux 容器里跑不起来。
set -eu

cd /workspace/frontend

corepack enable >/dev/null 2>&1 || true

if [ ! -d node_modules/vite ]; then
    echo "[dev] 安装前端依赖（首次启动会慢一些）..."
    pnpm install --frozen-lockfile
fi

echo "[dev] Vite dev server 启动中，/api/v1 反代到 ${DEV_API_TARGET:-http://backend:8080}"
exec pnpm exec vite --host 0.0.0.0 --port "${VITE_PORT:-5173}"
