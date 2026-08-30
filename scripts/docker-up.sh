#!/usr/bin/env bash
# 用项目 .env 中的环境变量启动/更新 Dify RAG Lab 容器。
# 用法：
#   ./scripts/docker-up.sh
#   ./scripts/docker-up.sh --build
#   ./scripts/docker-up.sh --force-recreate backend frontend
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "缺少 .env，请先执行: cp .env.example .env 并填写真实配置。" >&2
  exit 1
fi

# 将 .env 中的变量导入当前 shell 环境，覆盖可能残留的旧 export。
set -a
# shellcheck disable=SC1091
source .env
set +a

exec docker compose up -d "$@"
