#!/usr/bin/env bash
# 引导工具：从同机部署的 Dify api 容器读取 WEAVIATE_API_KEY 并注入当前 shell。
# 适用：网关与 Dify 同机部署（docker 可达），需要直查向量库。
# 注意：仅用于同机引导；生产建议走密钥管理/同一 .env 注入；只读环境变量，不修改容器。
set -euo pipefail

CONTAINER_NAME="${1:-docker-api-1}"

key=$(docker exec "${CONTAINER_NAME}" printenv WEAVIATE_API_KEY 2>/dev/null || true)
if [ -z "${key}" ]; then
  echo "未能在容器 ${CONTAINER_NAME} 中找到 WEAVIATE_API_KEY（确认是 Dify api 容器且已配置 VECTOR_STORE=weaviate）" >&2
  exit 1
fi

export WEAVIATE_API_KEY="${key}"
echo "已从容器 ${CONTAINER_NAME} 读取 WEAVIATE_API_KEY 并注入当前 shell：${key}"
echo "提示：仅用于同机引导；生产环境请从密钥管理/同一 .env 注入。"
