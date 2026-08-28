#!/usr/bin/env bash
# 阶段 1 前置：创建演练知识库。
# 优先尝试用数据集 API Key 通过 API 创建（POST /v1/datasets）；
# 若无 Key 或 API 无建库权限，则给出 UI 操作指引。
# 依赖：curl、jq；环境变量 DIFY_BASE_URL / DIFY_DATASET_API_KEY。
set -euo pipefail

: "${DIFY_BASE_URL:=http://localhost:8080}"
NAME="${1:-研发知识库演练}"
API_KEY="${DIFY_DATASET_API_KEY:-}"

if [ -z "${API_KEY}" ]; then
  cat <<'EOF'
未提供数据集 API Key（环境变量 DIFY_DATASET_API_KEY）。请先完成 UI 操作：
  1) 打开 Dify 控制台 → 知识库 → 创建知识库
     - 索引方式: 高质量（High Quality，需要 Embedding 模型）
  2) 打开该知识库 → 设置 → API 访问 → 创建 API Key
  3) 设置环境变量 DIFY_DATASET_API_KEY / DIFY_DATASET_ID 后重试
EOF
  exit 0
fi

body=$(jq -nc \
  --arg name "${NAME}" \
  '{name:$name, description:"Dify RAG Lab 演练知识库", indexing_technique:"high_quality", permission:"only_me"}')

resp=$(curl -fsS -X POST "${DIFY_BASE_URL}/v1/datasets" \
  -H "Authorization: Bearer ${API_KEY}" \
  -H 'Content-Type: application/json' \
  -d "${body}") || {
    echo "API 创建失败（数据集 API Key 可能无建库权限），请改用 UI 创建。" >&2
    exit 1
  }

id=$(jq -r '.id' <<<"${resp}")
echo "知识库创建成功: id=${id}"
echo "请设置环境变量 DIFY_DATASET_ID=${id}"
