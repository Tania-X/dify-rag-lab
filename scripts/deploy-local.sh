#!/usr/bin/env bash
# 一键编排本地部署：Dify + dify-rag-lab 前后端
#
# 用法：
#   ./scripts/deploy-local.sh
#
# 环境变量：
#   DIFY_HOME        Dify 仓库目录，默认 ../dify（相对于本仓库根目录）
#   DIFY_COMPOSE     Dify docker-compose 文件，默认 $DIFY_HOME/docker/docker-compose.yaml
#   DIFY_ENV_FILE    Dify .env 文件，默认 $DIFY_HOME/docker/.env
#   RAG_COMPOSE      dify-rag-lab 自己的 docker-compose 文件，默认 ./docker-compose.yml
#   RAG_ENV_FILE     本仓库 .env 文件，默认 ./.env
#   WAIT_TIMEOUT     健康检查超时秒数，默认 120
#
# 说明：
#   - 该脚本会先启动 Dify，再构建并启动 dify-rag-lab backend/frontend
#   - 两个 compose 共享 docker_default 网络
#   - 不会删除已有容器/数据
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIFY_HOME="${DIFY_HOME:-$(cd "$REPO_ROOT/.." && pwd)/dify}"
DIFY_COMPOSE="${DIFY_COMPOSE:-$DIFY_HOME/docker/docker-compose.yaml}"
DIFY_ENV_FILE="${DIFY_ENV_FILE:-$DIFY_HOME/docker/.env}"
RAG_COMPOSE="${RAG_COMPOSE:-$REPO_ROOT/docker-compose.yml}"
RAG_ENV_FILE="${RAG_ENV_FILE:-$REPO_ROOT/.env}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-120}"

echo "==> 检查 Docker 环境..."
docker info >/dev/null 2>&1 || { echo "Docker daemon 未运行，请先启动 Docker/Colima" >&2; exit 1; }

if [ ! -f "$DIFY_COMPOSE" ]; then
  echo "找不到 Dify compose: $DIFY_COMPOSE" >&2
  echo "请设置 DIFY_HOME 或先 clone Dify 到 $DIFY_HOME" >&2
  exit 1
fi

if [ ! -f "$DIFY_ENV_FILE" ]; then
  echo "找不到 Dify .env: $DIFY_ENV_FILE" >&2
  echo "请先执行: cd $DIFY_HOME/docker && cp .env.example .env" >&2
  exit 1
fi

echo "==> 启动 Dify..."
(
  cd "$(dirname "$DIFY_COMPOSE")"
  docker compose --env-file "$DIFY_ENV_FILE" -f "$(basename "$DIFY_COMPOSE")" up -d
)

echo "==> 等待 Dify 就绪..."
ready=0
for i in $(seq 1 "$WAIT_TIMEOUT"); do
  if curl -fsS --max-time 3 "http://localhost/console/api/setup" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "Dify 未在 ${WAIT_TIMEOUT}s 内就绪，请检查 Dify 日志" >&2
  exit 1
fi
echo "Dify 已就绪: http://localhost"

if [ ! -f "$RAG_ENV_FILE" ]; then
  echo "==> 创建 dify-rag-lab .env 模板..."
  cp "$REPO_ROOT/.env.example" "$RAG_ENV_FILE"
  echo "请编辑 $RAG_ENV_FILE 填入 DIFY_DATASET_ID / API Key / EMBEDDING / REWRITE 等配置后重新运行本脚本"
  exit 0
fi

echo "==> 构建并启动 dify-rag-lab 前后端..."
(
  cd "$REPO_ROOT"
  docker compose --env-file "$RAG_ENV_FILE" -f "$(basename "$RAG_COMPOSE")" up -d --build
)

echo "==> 等待后端就绪..."
backend_ready=0
for i in $(seq 1 "$WAIT_TIMEOUT"); do
  if curl -fsS --max-time 3 "http://localhost:8081/api/vdb/collections" >/dev/null 2>&1; then
    backend_ready=1
    break
  fi
  sleep 1
done
if [ "$backend_ready" != "1" ]; then
  echo "后端未在 ${WAIT_TIMEOUT}s 内就绪，请检查: docker compose logs backend" >&2
  exit 1
fi

echo ""
echo "部署完成："
echo "  Dify 控制台:  http://localhost"
echo "  前端:         http://localhost:8080"
echo "  后端:         http://localhost:8081"
