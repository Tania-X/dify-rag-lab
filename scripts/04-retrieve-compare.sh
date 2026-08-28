#!/usr/bin/env bash
# 阶段 2：对比三种检索方式（semantic / full_text / hybrid）。
# 依赖：curl、jq。
# 用法：./04-retrieve-compare.sh [query] [dataset_id] [api_key]
# 环境变量：DIFY_BASE_URL / DIFY_DATASET_ID / DIFY_DATASET_API_KEY / TOP_K
set -euo pipefail

: "${DIFY_BASE_URL:=http://localhost:8080}"
: "${DIFY_DATASET_ID:=}"
: "${DIFY_DATASET_API_KEY:=}"
: "${TOP_K:=3}"

QUERY="${1:-支付网关调用超时应该怎么办}"
DATASET_ID="${2:-${DIFY_DATASET_ID}}"
API_KEY="${3:-${DIFY_DATASET_API_KEY}}"

[ -n "${DATASET_ID}" ] || { echo "缺少 dataset_id" >&2; exit 1; }
[ -n "${API_KEY}" ] || { echo "缺少 api_key" >&2; exit 1; }

for m in semantic_search full_text_search hybrid_search; do
  body=$(jq -nc --arg q "${QUERY}" --arg m "${m}" --argjson k "${TOP_K}" \
    '{query:$q, retrieval_model:{search_method:$m, reranking_enable:false, top_k:$k, score_threshold_enabled:false}}')
  echo "[${m}]  query=${QUERY}"
  curl -fsS -X POST "${DIFY_BASE_URL}/v1/datasets/${DATASET_ID}/retrieve" \
    -H "Authorization: Bearer ${API_KEY}" \
    -H 'Content-Type: application/json' \
    -d "${body}" \
    | jq -r --argjson k "${TOP_K}" '
        if (.records | length) == 0 then "  (无命中)"
        else .records[:$k] | to_entries[] |
          "  top" + ((.key + 1) | tostring) +
          "  score=" + (.value.score | tostring) +
          "  " + (.value.segment.content[0:70] | gsub("\n"; " "))
        end'
done

echo ""
echo "提示：semantic/hybrid 的 score 是 1-余弦距离（[-1,1]），full_text 是 BM25 原始分，不可跨方法比较。"
