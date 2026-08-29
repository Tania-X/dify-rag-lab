#!/usr/bin/env bash
# 阶段 2（评测）：用评测集给检索效果打分（hit@1 / hit@5）。
# 读取 CSV（question + expected_keyword），每题跑 hybrid_search，
# 检查 top-k 命中的 chunk 正文是否包含期望关键词。
# 依赖：curl、jq。
# 用法：./04b-evaluate.sh [评测集CSV] [dataset_id] [api_key]
# 环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY / TOP_K
set -euo pipefail

: "${DIFY_BASE_URL:=http://localhost:8080}"
: "${DIFY_DATASET_ID:=}"
: "${DIFY_DATASET_API_KEY:=}"
: "${TOP_K:=5}"
# Rerank 默认开启；可用 RERANK_ENABLE=false 关闭，或覆盖 RERANK_PROVIDER / RERANK_MODEL
: "${RERANK_ENABLE:=true}"
: "${RERANK_PROVIDER:=langgenius/siliconflow/siliconflow}"
: "${RERANK_MODEL:=BAAI/bge-reranker-v2-m3}"

CSV_PATH="${1:-sample-data/评测集-questions.csv}"
DATASET_ID="${2:-${DIFY_DATASET_ID}}"
API_KEY="${3:-${DIFY_DATASET_API_KEY}}"

[ -n "${DATASET_ID}" ] || { echo "缺少 dataset_id" >&2; exit 1; }
[ -n "${API_KEY}" ] || { echo "缺少 api_key" >&2; exit 1; }
[ -f "${CSV_PATH}" ] || { echo "评测集不存在: ${CSV_PATH}" >&2; exit 1; }

hit1=0
hit5=0
total=0

while IFS=',' read -r id question keyword note; do
  [ "${id}" = "id" ] && continue
  total=$((total + 1))

  if [ "${RERANK_ENABLE}" = "true" ]; then
    body=$(jq -nc --arg q "${question}" --argjson k "${TOP_K}" \
      --arg p "${RERANK_PROVIDER}" --arg m "${RERANK_MODEL}" \
      '{query:$q, retrieval_model:{search_method:"hybrid_search", reranking_enable:true,
        reranking_model:{reranking_provider_name:$p, reranking_model_name:$m},
        reranking_mode:"reranking_model", top_k:$k, score_threshold_enabled:false}}')
  else
    body=$(jq -nc --arg q "${question}" --argjson k "${TOP_K}" \
      '{query:$q, retrieval_model:{search_method:"hybrid_search", reranking_enable:false, top_k:$k, score_threshold_enabled:false}}')
  fi
  resp=$(curl -fsS -X POST "${DIFY_BASE_URL}/v1/datasets/${DATASET_ID}/retrieve" \
    -H "Authorization: Bearer ${API_KEY}" \
    -H 'Content-Type: application/json' \
    -d "${body}")

  h1=$(jq -r --arg kw "${keyword}" '.records[0].segment.content | contains($kw)' <<<"${resp}")
  h5=$(jq -r --arg kw "${keyword}" '[.records[].segment.content | contains($kw)] | any' <<<"${resp}")
  [ "${h1}" = "true" ] && hit1=$((hit1 + 1))
  [ "${h5}" = "true" ] && hit5=$((hit5 + 1))

  top1=$(jq -r '.records[0].segment.content[0:40] | gsub("\n"; " ")' <<<"${resp}")
  if [ "${h1}" = "true" ]; then m1="✅"; else m1="❌"; fi
  if [ "${h5}" = "true" ]; then m5="✅"; else m5="❌"; fi
  printf '%-3s %-36s %-12s hit@1=%s hit@%s=%s top1=%s\n' \
    "${id}" "${question:0:34}" "${keyword}" "${m1}" "${TOP_K}" "${m5}" "${top1}"
done < "${CSV_PATH}"

echo ""
echo "评测结果: hit@1 = ${hit1}/${total}    hit@${TOP_K} = ${hit5}/${total}"
