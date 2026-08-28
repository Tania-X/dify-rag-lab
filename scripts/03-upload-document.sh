#!/usr/bin/env bash
# 阶段 1：上传文档到知识库并等待索引完成（create-by-file + 轮询）。
# 依赖：curl、jq。
# 用法：./03-upload-document.sh <文档路径> [doc_form] [dataset_id] [api_key]
# 环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY / TIMEOUT_SEC
set -euo pipefail

: "${DIFY_BASE_URL:=http://localhost:8080}"
: "${DIFY_DATASET_ID:=}"
: "${DIFY_DATASET_API_KEY:=}"
: "${TIMEOUT_SEC:=180}"

DOC_PATH="${1:?用法: $0 <文档路径> [doc_form] [dataset_id] [api_key]}"
DOC_FORM="${2:-text_model}"                # text_model | hierarchical_model | qa_model
DATASET_ID="${3:-${DIFY_DATASET_ID}}"
API_KEY="${4:-${DIFY_DATASET_API_KEY}}"

[ -n "${DATASET_ID}" ] || { echo "缺少 dataset_id（参数或环境变量 DIFY_DATASET_ID）" >&2; exit 1; }
[ -n "${API_KEY}" ] || { echo "缺少 api_key（参数或环境变量 DIFY_DATASET_API_KEY）" >&2; exit 1; }
[ -f "${DOC_PATH}" ] || { echo "文件不存在: ${DOC_PATH}" >&2; exit 1; }

# multipart 中的 data JSON（与 Dify 1.17 create-by-file 契约一致）
data=$(jq -nc --arg doc_form "${DOC_FORM}" \
  '{indexing_technique:"high_quality", doc_form:$doc_form, doc_language:"Chinese",
    process_rule:{mode:"custom", rules:{segmentation:{separator:"\n", max_tokens:500, chunk_overlap:50}}}}')

echo "上传文档: ${DOC_PATH} → dataset=${DATASET_ID}"
resp=$(curl -fsS -X POST "${DIFY_BASE_URL}/v1/datasets/${DATASET_ID}/document/create-by-file" \
  -H "Authorization: Bearer ${API_KEY}" \
  -F "file=@${DOC_PATH}" \
  -F "data=${data}")

doc_id=$(jq -r '.document.id' <<<"${resp}")
echo "Document created: document_id=${doc_id} batch=$(jq -r '.batch' <<<"${resp}")"

# 轮询索引状态
status=""
elapsed=0
while :; do
  sleep 3
  elapsed=$((elapsed + 3))
  st=$(curl -fsS "${DIFY_BASE_URL}/v1/datasets/${DATASET_ID}/documents/${doc_id}/indexing-status" \
    -H "Authorization: Bearer ${API_KEY}")
  status=$(jq -r '.indexing_status' <<<"${st}")
  echo "  indexing_status=${status}"
  case "${status}" in
    waiting|parsing|cleaning|splitting|indexing)
      if [ "${elapsed}" -ge "${TIMEOUT_SEC}" ]; then
        echo "索引超时（${TIMEOUT_SEC}s）" >&2
        exit 1
      fi
      ;;
    completed) echo "✅ 索引完成"; exit 0 ;;
    *) echo "❌ 索引异常: ${st}" >&2; exit 1 ;;
  esac
done
