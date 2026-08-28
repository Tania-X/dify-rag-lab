#!/usr/bin/env bash
# 阶段 3：直查 Weaviate，亲眼验证 Dify 在向量库里存了什么。
# 输出：collection 列表+对象数、属性、样本对象（chunk 正文与元数据）。
# 依赖：curl、jq；先运行 01-publish-weaviate-port.sh（宿主 8090 端口）。
# 环境变量：WEAVIATE_URL / WEAVIATE_API_KEY
set -euo pipefail

: "${WEAVIATE_URL:=http://localhost:8090}"
: "${WEAVIATE_API_KEY:=}"

[ -n "${WEAVIATE_API_KEY}" ] || { echo "缺少 Weaviate API Key（环境变量 WEAVIATE_API_KEY）" >&2; exit 1; }
AUTH=(-H "Authorization: Bearer ${WEAVIATE_API_KEY}")

schema=$(curl -fsS "${WEAVIATE_URL}/v1/schema" "${AUTH[@]}" --max-time 10)
classes=$(jq -r '.classes[]?.class' <<<"${schema}")
if [ -z "${classes}" ]; then
  echo "向量库为空：还没有任何 collection（先执行 03-upload-document.sh 入库）。"
  exit 0
fi

echo "=== Weaviate collections ==="
while IFS= read -r class; do
  [ -z "${class}" ] && continue

  gql=$(jq -nc --arg c "${class}" '{query: ("{ Aggregate { " + $c + " { meta { count } } } }")}')
  agg=$(curl -fsS -X POST "${WEAVIATE_URL}/v1/graphql" "${AUTH[@]}" \
    -H 'Content-Type: application/json' -d "${gql}" --max-time 10)
  count=$(jq -r --arg c "${class}" '.data.Aggregate[$c][0].meta.count' <<<"${agg}")

  props=$(curl -fsS "${WEAVIATE_URL}/v1/schema/${class}" "${AUTH[@]}" --max-time 10 \
    | jq -r '[.properties[].name] | join(", ")')

  echo "--- class=${class}  objects=${count} ---"
  echo "properties: ${props}"

  g2=$(jq -nc --arg c "${class}" \
    '{query: ("{ Get { " + $c + "(limit: 1) { text doc_id document_id chunk_index is_summary _additional { id } } } }")}')
  obj=$(curl -fsS -X POST "${WEAVIATE_URL}/v1/graphql" "${AUTH[@]}" \
    -H 'Content-Type: application/json' -d "${g2}" --max-time 10)
  jq -r --arg c "${class}" '
    .data.Get[$c][0] as $o |
    if $o == null then "  (无对象)"
    else "  sample: id=" + $o._additional.id +
         " doc_id=" + ($o.doc_id // "null") +
         " chunk_index=" + (($o.chunk_index // -1) | tostring) +
         " is_summary=" + (($o.is_summary // false) | tostring) +
         " text=" + ($o.text[0:80] // "")
    end' <<<"${obj}"
done <<<"${classes}"

echo ""
echo "提示：对象数 ≈ 文档分段数；doc_id 是 uuid5(内容) 的确定性 ID（重复内容不会重复入库）。"
