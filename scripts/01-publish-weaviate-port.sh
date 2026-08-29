#!/usr/bin/env bash
# 阶段 0.3：把 Weaviate 端口发布到宿主机 127.0.0.1:8090（不修改 Dify compose）。
# 原理：在 Docker 网络里跑一个 alpine/socat 透明代理，把 weaviate:8080 映射到宿主。
# 依赖：docker；网络名默认 docker_default（compose 项目网络），可用 DIFY_NETWORK 覆盖。
set -euo pipefail

NETWORK="${DIFY_NETWORK:-docker_default}"
PROXY_NAME="${WEAVIATE_PROXY_NAME:-weaviate-host-proxy}"
HOST_PORT="${WEAVIATE_PROXY_PORT:-8090}"

if docker ps -a --format '{{.Names}}' | grep -qx "${PROXY_NAME}"; then
  echo "代理容器已存在，直接启动: ${PROXY_NAME}"
  docker start "${PROXY_NAME}" >/dev/null
else
  echo "创建代理容器 ${PROXY_NAME} (socat) ..."
  docker run -d --name "${PROXY_NAME}" --restart unless-stopped \
    --network "${NETWORK}" \
    -p "127.0.0.1:${HOST_PORT}:${HOST_PORT}" \
    alpine/socat "TCP-LISTEN:${HOST_PORT},fork,reuseaddr" "TCP:weaviate:8080" >/dev/null
fi

sleep 2
if curl -fsS --max-time 5 "http://127.0.0.1:${HOST_PORT}/v1/meta" >/dev/null 2>&1; then
  echo "OK: Weaviate 已可通过宿主访问 http://127.0.0.1:${HOST_PORT}"
else
  echo "WARN: 代理已启动，但 Weaviate 未响应（请确认 Dify 容器组在运行）" >&2
fi
